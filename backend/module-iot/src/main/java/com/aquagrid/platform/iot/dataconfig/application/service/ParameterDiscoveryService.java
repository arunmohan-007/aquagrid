package com.aquagrid.platform.iot.dataconfig.application.service;

import com.aquagrid.platform.common.audit.AuditCategory;
import com.aquagrid.platform.common.audit.AuditEvent;
import com.aquagrid.platform.common.audit.AuditEventTypes;
import com.aquagrid.platform.common.audit.AuditService;
import com.aquagrid.platform.common.audit.AuditSeverity;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.iot.dataconfig.domain.model.DiscoveredParameter;
import com.aquagrid.platform.iot.dataconfig.domain.model.DiscoveryStatus;
import com.aquagrid.platform.iot.dataconfig.domain.model.ParameterDataType;
import com.aquagrid.platform.iot.dataconfig.domain.model.RawTelemetry;
import com.aquagrid.platform.iot.dataconfig.infrastructure.persistence.DiscoveredParameterRepository;
import com.aquagrid.platform.iot.dataconfig.infrastructure.persistence.RawTelemetryRepository;
import com.aquagrid.platform.iot.domain.model.MetricCatalog;
import com.aquagrid.platform.iot.receiver.application.parser.MetricVocabulary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Notices parameters nobody has described, and offers them for configuration.
 *
 * <p>Storing an unknown field means nothing is lost. It does not mean anyone finds out — an
 * unconfigured parameter is invisible on every dashboard, absent from every report and outside every
 * alarm rule, indistinguishable from the operator's chair from a field the device never sent. This
 * service is the difference between preserving data and surfacing it, and it is why "accept
 * everything" is a feature rather than a hoarding policy.
 *
 * <h2>Why the write path is what it is</h2>
 *
 * <p>{@link #record} runs inside packet reception, so it is written to be cheap and to be harmless
 * when it fails:
 *
 * <ul>
 *   <li>One {@code INSERT .. ON CONFLICT DO UPDATE} per unknown field, not a read followed by a
 *       write. Two receivers racing on the same first sighting would otherwise produce a constraint
 *       violation rather than a count of two.</li>
 *   <li>{@code REQUIRES_NEW}, so the discovery note is independent of the reading it was noticed
 *       alongside — and, more importantly, so a failure here cannot roll back a reading that has
 *       already been accepted.</li>
 *   <li>Exceptions are swallowed and logged. Discovery is a convenience for an administrator;
 *       telemetry is the product. Failing an uplink because a note could not be filed would invert
 *       that, and would invite the gateway to resend a packet that was ingested perfectly well.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParameterDiscoveryService {

    /**
     * Payload keys that are envelope, not measurement.
     *
     * <p>Every JSON uplink carries some of these — the device's own identifier, its clock, a
     * sequence number — and none of them is a parameter anyone would configure. Listing them here
     * rather than filtering on the screen keeps the queue short enough to be read: a discovery list
     * whose first ten rows are {@code deviceId}, {@code timestamp} and {@code seq} is a list nobody
     * scrolls past.
     *
     * <p>Matched after the same normalisation {@code MetricVocabulary} uses — lower-cased with
     * separators stripped — so {@code devEui}, {@code dev_eui} and {@code DEVEUI} are one entry.
     */
    private static final Set<String> ENVELOPE_KEYS = Set.of(
            "deviceid", "deviceeui", "deveui", "imei", "serialnumber", "serial", "clientid",
            "unitid", "devaddr", "devicename", "deviceinfo", "applicationid", "applicationname",
            "timestamp", "time", "ts", "observedat", "measuredat", "readingtime", "receivedat",
            "fcnt", "framecounter", "seq", "sequence", "fport", "dr", "adr", "confirmed",
            "rxinfo", "txinfo", "gatewayid", "data", "object", "payload", "raw",
            "latitude", "longitude", "lat", "lon", "lng", "alt", "altitude");

    private final DiscoveredParameterRepository repository;
    private final RawTelemetryRepository rawTelemetryRepository;
    private final AuditService auditService;

    // ---- Write path ----------------------------------------------------------------------------

    /**
     * Records that a device sent fields the catalogue does not describe.
     *
     * @param configuredKeys the payload keys the device's configuration already claims, so a
     *                       configured parameter is never reported as a discovery
     * @param observed       every scalar field the payload carried, by its verbatim key
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID organizationId, UUID deviceId, String deviceCode, String deviceType,
                       Set<String> configuredKeys, Map<String, Object> observed, Instant seenAt) {
        if (organizationId == null || deviceId == null || observed.isEmpty()) {
            return;
        }
        try {
            for (Map.Entry<String, Object> field : observed.entrySet()) {
                String key = field.getKey();
                if (key == null || key.isBlank() || configuredKeys.contains(key)
                        || isEnvelope(key) || isPlatformMetric(key)) {
                    continue;
                }
                Object value = field.getValue();
                repository.recordSighting(organizationId, deviceId, deviceCode, deviceType, key,
                        sample(value), ParameterDataType.detect(value).name(),
                        seenAt == null ? Instant.now() : seenAt);
            }
        } catch (RuntimeException e) {
            // Loud but not fatal — see the class comment. The reading is already committed by its
            // own transaction and must not be reported as a failure because a note could not be filed.
            log.error("Could not record discovered parameters for device {} — the readings themselves "
                    + "are unaffected", deviceId, e);
        }
    }

    private static boolean isEnvelope(String key) {
        return ENVELOPE_KEYS.contains(
                key.toLowerCase().replace("_", "").replace("-", "").replace(" ", ""));
    }

    /**
     * Whether the platform already ships an opinion about this field.
     *
     * <p>{@code MetricCatalog} <em>is</em> configuration — it declares a label, unit, kind and
     * category for the metrics the platform understands, and a reading that matches one is already
     * stored with its unit and displayed in its right group. Listing it as undescribed would ask an
     * administrator to configure something that is demonstrably already working.
     *
     * <p>Matched after {@code MetricVocabulary} canonicalisation, so the vendor spellings resolve
     * too: {@code flowRate}, {@code totalVolume} and {@code battery} are the platform's
     * {@code flow_rate}, {@code volume} and {@code battery_voltage}, not four unknowns each.
     * Without this the queue opens on the eight metrics every water meter sends — which is the
     * "queue nobody reads" failure this class exists to avoid, arrived at from the other direction.
     *
     * <p>A tenant can still configure one: doing so overrides the catalogue's unit and adds a range.
     * What they are spared is being nagged to.
     */
    private static boolean isPlatformMetric(String key) {
        return MetricCatalog.isCatalogued(MetricVocabulary.canonicalise(key));
    }

    /** The value as the screen shows it, bounded so a nested object cannot fill the column. */
    private static String sample(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.length() <= 255 ? text : text.substring(0, 252) + "...";
    }

    // ---- Read path -----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<DiscoveredParameter> search(UUID organizationId, UUID deviceId, String deviceType,
                                            DiscoveryStatus status, String search, Pageable pageable) {
        return repository.search(organizationId, deviceId, blankToNull(deviceType),
                status == null ? null : status.name(), blankToNull(search), pageable);
    }

    @Transactional(readOnly = true)
    public DiscoveredParameter require(UUID discoveryId, UUID organizationId) {
        return repository.findByIdAndOrganizationId(discoveryId, organizationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No discovered parameter " + discoveryId + " in this organisation."));
    }

    /** How many parameters are still waiting for a decision. Drives the menu badge. */
    @Transactional(readOnly = true)
    public long pendingCount(UUID organizationId) {
        return repository.countByOrganizationIdAndStatus(organizationId, DiscoveryStatus.PENDING);
    }

    /**
     * The most recent payloads that actually carried this parameter.
     *
     * <p>The "View Raw Data" action, and the reason the discovery row keeps only one sample. An
     * administrator deciding what {@code motor_temperature} is wants to see a spread of values, not
     * a single reading that happened to be the last one recorded — and the values are already there
     * in {@code iot.device_raw_telemetry}, indexed for exactly this query.
     */
    @Transactional(readOnly = true)
    public List<RawTelemetry> samplePayloads(UUID organizationId, UUID discoveryId, int limit) {
        DiscoveredParameter discovery = require(discoveryId, organizationId);
        return rawTelemetryRepository.findRecentCarrying(organizationId, discovery.getDeviceId(),
                discovery.getParameterName(), Math.clamp(limit, 1, 50));
    }

    // ---- Ignore --------------------------------------------------------------------------------

    /**
     * Dismisses a discovery from the queue.
     *
     * <p><b>Deletes nothing.</b> The raw payloads stay, the occurrence counter keeps climbing on
     * every further sighting, and the parameter can be configured years later with its whole history
     * intact. What changes is that a vendor's {@code fw_build} field, which nobody will ever chart,
     * stops competing for attention with the {@code motor_temperature} someone should look at.
     *
     * <p>Audited, because an ignored parameter is a decision that a stream of device data will go
     * unexamined — and the person who later asks "why did nobody notice the motor was overheating"
     * deserves an answer better than silence.
     */
    @Transactional
    public DiscoveredParameter ignore(UUID discoveryId, UUID organizationId, UUID actorId,
                                      String actorName, String reason) {
        DiscoveredParameter discovery = require(discoveryId, organizationId);
        if (discovery.getStatus() == DiscoveryStatus.CONFIGURED) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'" + discovery.getParameterName() + "' has already been configured. Deactivate "
                            + "the parameter instead — ignoring it here would not stop it being "
                            + "validated and charted.");
        }
        discovery.setStatus(DiscoveryStatus.IGNORED);
        discovery.setResolvedBy(actorId);
        discovery.setResolvedAt(Instant.now());
        DiscoveredParameter saved = repository.save(discovery);

        auditService.record(AuditEvent.builder()
                .organizationId(organizationId)
                .actorUserId(actorId)
                .actorUsername(actorName)
                .eventType(AuditEventTypes.DEVICE_PARAMETER_DISCOVERY_IGNORED)
                .category(AuditCategory.CONFIGURATION)
                .severity(AuditSeverity.INFO)
                .resourceType("iot.device_discovered_parameter")
                .resourceId(saved.getId().toString())
                .success(true)
                .message("Ignored discovered parameter '" + saved.getParameterName() + "' from device "
                        + saved.getDeviceCode() + "; it is still received and stored, and no longer listed")
                /*
                 * Every value a string, and the count deliberately so.
                 *
                 * The audit metadata column is JSONB, and Hibernate dirty-checks a JSON attribute by
                 * serialising it and comparing against a snapshot it built the same way. A `long`
                 * survives that round trip as an `Integer`, which does not equal the `Long` still in
                 * memory — so the freshly inserted row is seen as modified and an UPDATE is flushed
                 * against a table the database enforces as append-only. The write then fails, on a
                 * background thread, logging an error and losing the audit row for an action whose
                 * whole point is that it be recorded.
                 */
                .metadata(Map.of(
                        "parameterName", saved.getParameterName(),
                        "deviceId", String.valueOf(saved.getDeviceId()),
                        "occurrences", String.valueOf(saved.getOccurrences()),
                        "reason", reason == null ? "" : reason))
                .build());
        return saved;
    }

    /** Puts an ignored discovery back on the queue. The mistake this undoes is a common one. */
    @Transactional
    public DiscoveredParameter restore(UUID discoveryId, UUID organizationId) {
        DiscoveredParameter discovery = require(discoveryId, organizationId);
        if (discovery.getStatus() == DiscoveryStatus.IGNORED) {
            discovery.setStatus(DiscoveryStatus.PENDING);
            discovery.setResolvedBy(null);
            discovery.setResolvedAt(null);
            return repository.save(discovery);
        }
        return discovery;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
