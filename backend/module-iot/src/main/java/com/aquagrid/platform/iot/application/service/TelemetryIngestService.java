package com.aquagrid.platform.iot.application.service;

import com.aquagrid.platform.common.audit.AuditCategory;
import com.aquagrid.platform.common.audit.AuditEvent;
import com.aquagrid.platform.common.audit.AuditService;
import com.aquagrid.platform.common.audit.AuditSeverity;
import com.aquagrid.platform.iot.api.DeviceMessage;
import com.aquagrid.platform.iot.dataconfig.api.DeviceParameterApi;
import com.aquagrid.platform.iot.dataconfig.api.ParameterDefinition;
import com.aquagrid.platform.iot.dataconfig.domain.model.QualityStatus;
import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.domain.model.DeviceReading;
import com.aquagrid.platform.iot.domain.model.MetricCatalog;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceReadingRepository;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceRepository;
import com.aquagrid.platform.iot.spi.IngestResult;
import com.aquagrid.platform.iot.spi.TelemetryIngestPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The in-process implementation of {@link TelemetryIngestPort}.
 *
 * <p>This is where the canonical {@link DeviceMessage} becomes durable state. The flow is the same
 * regardless of transport:
 * <ol>
 *   <li>Resolve the device by (tenant, deviceEui). Unknown → REJECTED, never an exception: a rogue
 *       device on the wire is normal operation, not a fault.</li>
 *   <li>Dedup by frame counter / observedAt window. LoRaWAN replay is the common duplicate source;
 *       a duplicate must be acked (so the network stops retransmitting) but must not double-count.</li>
 *   <li>Persist one {@link DeviceReading} per metric, stamped with the unit and the quality its
 *       configured parameter declares — see {@link DeviceParameterApi}.</li>
 *   <li>Refresh the device's last-known radio state, so the health dashboard is current without a
 *       telemetry scan.</li>
 * </ol>
 *
 * <p>The transaction boundary is one message. Batching is a Module 13 concern (the hypertable
 * ingester uses COPY); the per-message path here is correct first, fast enough for v1.
 *
 * <h2>Where data configuration applies, and where it does not</h2>
 *
 * <p>Every reading written here carries a {@link QualityStatus}, and no reading is ever withheld
 * because of one. A value outside its configured range is stored and marked
 * {@code OUT_OF_RANGE}; a value that cannot be read as its declared type is stored and marked
 * {@code INVALID}; a metric nobody has configured is stored and marked {@code UNKNOWN}. There is no
 * branch in this class that drops a value on the strength of a configuration row, because a
 * pressure of 47 bar on a 10 bar main is the most important reading of the day and the packet cannot
 * be re-requested.
 *
 * <p>Validation lives here rather than in the receiver's {@code ParameterConfigurationStage} because
 * this is where the reading row is written, and because this path also serves the older transport
 * adapters that do not go through the receiver pipeline. Judging in two places would risk the two
 * verdicts disagreeing; judging where the row is written cannot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryIngestService implements TelemetryIngestPort {

    /**
     * A message whose observedAt is older than this relative to its receivedAt is treated as a
     * potential replay and deduped. Generous, because real devices drift and store-and-forward
     * delays (NB-IoT PSM) legitimately span minutes.
     */
    private static final Duration REPLAY_WINDOW = Duration.ofHours(1);

    private final DeviceRepository deviceRepository;
    private final DeviceReadingRepository readingRepository;
    private final AuditService auditService;
    private final DedupCache dedupCache;
    private final DeviceParameterApi parameterApi;

    @Override
    @Transactional
    public IngestResult ingest(DeviceMessage message) {
        if (message.deviceEui() == null || message.deviceEui().isBlank()
                || message.observedAt() == null || message.receivedAt() == null) {
            return new IngestResult.Rejected("Malformed message: missing deviceEui or timestamps");
        }

        // Devices are tenant-scoped, but ingestion is not authenticated — it arrives from gateways.
        // Resolution must therefore consider all tenants and pin the tenant from the resolved row.
        //
        // The wire field is called deviceEui because that is what LoRaWAN gateways call it; it is
        // matched against the device's networkAddress, which holds whichever identifier the
        // device's communication technology addresses it by (DevEUI, or IMEI for NB-IoT and 4G).
        Device device = deviceRepository.findFirstByNetworkAddressIgnoreCase(message.deviceEui())
                .orElse(null);
        if (device == null) {
            log.debug("Rejected uplink from unknown device {}", message.deviceEui());
            return new IngestResult.Rejected("Unknown device: " + message.deviceEui());
        }
        if ("DECOMMISSIONED".equals(device.getStatus())) {
            return new IngestResult.Rejected("Device decommissioned: " + message.deviceEui());
        }

        // Dedup. The cache keys on (deviceId, fCnt) when present, else on a content hash. A hit is
        // safe to acknowledge — see IngestResult.Duplicate.
        String dedupKey = dedupKey(device.getId(), message);
        if (dedupCache.wasRecentlySeen(dedupKey)) {
            return new IngestResult.Duplicate(device.getId().toString());
        }

        persistReadings(device, message);
        device.registerUplink(toBd(message.batteryV()), toBd(message.rssi()),
                toBd(message.snr()), message.receivedAt());
        deviceRepository.save(device);
        dedupCache.remember(dedupKey);

        audit(device, message);
        return new IngestResult.Accepted(device.getId().toString());
    }

    private void persistReadings(Device device, DeviceMessage message) {
        Map<String, ParameterDefinition> configured = configurationFor(device);
        Set<String> seen = new HashSet<>();

        for (Map.Entry<String, Double> metric : message.metrics().entrySet()) {
            ParameterDefinition definition = configured.get(metric.getKey());
            if (definition != null) {
                seen.add(definition.parameterName());
            }
            DeviceReading reading = new DeviceReading();
            reading.setOrganizationId(device.getOrganizationId());
            reading.setDeviceId(device.getId());
            reading.setObservedAt(message.observedAt());
            reading.setReceivedAt(message.receivedAt());
            /*
             * The configured name wins over the wire name. A parameter declared as `volume` with a
             * source key of `totalVolume` must land in the readings table as `volume`, or a chart
             * built on the configured name would find nothing — which is the whole purpose of
             * letting a vendor spelling be configured separately.
             */
            reading.setMetric(definition == null ? metric.getKey() : definition.parameterName());
            reading.setValue(definition == null
                    ? metric.getValue()
                    : definition.dataType().round(metric.getValue(), definition.decimalPrecision()));
            reading.setUnit(unitFor(metric.getKey(), definition));
            reading.setQuality(definition == null
                    ? QualityStatus.UNKNOWN.name()
                    : definition.judge(metric.getValue()).name());
            reading.setParameterId(definition == null ? null : definition.id());
            reading.setRssi(toBd(message.rssi()));
            reading.setSnr(toBd(message.snr()));
            reading.setBatteryV(toBd(message.batteryV()));
            reading.setFCnt(message.fCnt());
            reading.setTransport(message.transport());
            reading.setRawPayload(message.rawPayload());
            readingRepository.save(reading);
        }

        recordMissingMandatory(device, message, configured, seen);
    }

    /**
     * Writes a null-valued reading for every mandatory parameter the packet did not carry.
     *
     * <p>A gap in a series says nothing. "The device stopped sending pressure" and "nobody ever
     * asked for pressure" are different facts and only one of them is a fault, and neither is
     * visible in a table that simply has no row. Recording the absence is what makes it queryable —
     * and it is the reason the module can treat a missing mandatory value as a finding without ever
     * having to refuse the packet that was missing it.
     */
    private void recordMissingMandatory(Device device, DeviceMessage message,
                                        Map<String, ParameterDefinition> configured,
                                        Set<String> seen) {
        for (ParameterDefinition definition : configured.values()) {
            if (!definition.mandatory() || seen.contains(definition.parameterName())
                    || !definition.dataType().isReading()) {
                continue;
            }
            DeviceReading reading = new DeviceReading();
            reading.setOrganizationId(device.getOrganizationId());
            reading.setDeviceId(device.getId());
            reading.setObservedAt(message.observedAt());
            reading.setReceivedAt(message.receivedAt());
            reading.setMetric(definition.parameterName());
            reading.setValue(null);
            reading.setUnit(definition.unit());
            reading.setQuality(QualityStatus.MISSING.name());
            reading.setParameterId(definition.id());
            reading.setTransport(message.transport());
            readingRepository.save(reading);
        }
    }

    /**
     * The device's parameters, reachable by whichever name a metric arrived under.
     *
     * <p>Indexed twice on purpose. {@code DeviceParameterApi} keys by source key, because its other
     * caller has a raw payload in front of it; by the time a message reaches this service,
     * {@code MetricVocabulary} has already rewritten the well-known vendor spellings into canonical
     * names. Looking up under only one of the two would silently miss every parameter whose
     * spellings differ — which is most of the interesting ones.
     */
    private Map<String, ParameterDefinition> configurationFor(Device device) {
        Map<String, ParameterDefinition> byKey = parameterApi.effectiveForDevice(
                device.getOrganizationId(), device.getId(), device.getDeviceType());
        if (byKey.isEmpty()) {
            return Map.of();
        }
        Map<String, ParameterDefinition> lookup = new LinkedHashMap<>(byKey);
        byKey.values().forEach(definition -> lookup.putIfAbsent(definition.parameterName(), definition));
        return lookup;
    }

    /**
     * The unit to record a reading in.
     *
     * <p>The configured parameter first, then {@link MetricCatalog}. The catalogue used to be the
     * only answer — it took this over from a {@code switch} in this very method — and it still is
     * for the eight metrics the platform ships with, so a tenant that configures nothing sees no
     * change. What configuration adds is the ninth: a unit for a parameter the platform has never
     * heard of, without a release.
     */
    private static String unitFor(String metric, ParameterDefinition definition) {
        if (definition != null && definition.unit() != null && !definition.unit().isBlank()) {
            return definition.unit();
        }
        return MetricCatalog.unitOf(metric);
    }

    private static String dedupKey(UUID deviceId, DeviceMessage message) {
        if (message.fCnt() != null) {
            return deviceId + ":f" + message.fCnt();
        }
        return deviceId + ":t" + message.observedAt().getEpochSecond() + ":h" + message.metrics().hashCode();
    }

    private static BigDecimal toBd(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private void audit(Device device, DeviceMessage message) {
        auditService.record(AuditEvent.builder()
                .organizationId(device.getOrganizationId())
                .eventType("DEVICE_UPLINK_RECEIVED")
                .category(AuditCategory.SYSTEM)
                .severity(AuditSeverity.INFO)
                .resourceType("Device")
                .resourceId(device.getId().toString())
                .success(true)
                .message("Uplink from " + message.deviceEui() + " via " + message.transport()
                        + " (" + message.metrics().size() + " metric(s))")
                .metadata(Map.of(
                        "transport", message.transport(),
                        "metricCount", message.metrics().size(),
                        "rssi", String.valueOf(message.rssi())))
                .build());
    }
}
