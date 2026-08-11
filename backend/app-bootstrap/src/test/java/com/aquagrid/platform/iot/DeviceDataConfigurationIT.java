package com.aquagrid.platform.iot;

import com.aquagrid.platform.AbstractIntegrationTest;
import com.aquagrid.platform.iot.application.service.DeviceManagementService;
import com.aquagrid.platform.iot.dataconfig.api.DeviceParameterApi;
import com.aquagrid.platform.iot.dataconfig.application.command.ParameterCommands;
import com.aquagrid.platform.iot.dataconfig.application.service.DeviceDataConfigService;
import com.aquagrid.platform.iot.dataconfig.application.service.ParameterDiscoveryService;
import com.aquagrid.platform.iot.dataconfig.domain.model.DeviceDataParameter;
import com.aquagrid.platform.iot.dataconfig.domain.model.DiscoveredParameter;
import com.aquagrid.platform.iot.dataconfig.domain.model.DiscoveryStatus;
import com.aquagrid.platform.iot.dataconfig.domain.model.ParameterDataType;
import com.aquagrid.platform.iot.dataconfig.domain.model.ParameterScope;
import com.aquagrid.platform.iot.dataconfig.domain.model.QualityStatus;
import com.aquagrid.platform.iot.dataconfig.domain.model.RawTelemetry;
import com.aquagrid.platform.iot.dataconfig.infrastructure.persistence.RawTelemetryRepository;
import com.aquagrid.platform.iot.domain.model.DeviceReading;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceReadingRepository;
import com.aquagrid.platform.iot.receiver.api.ReceiverGateway;
import com.aquagrid.platform.iot.receiver.api.ReceptionOutcome;
import com.aquagrid.platform.iot.receiver.domain.model.IdentifierType;
import com.aquagrid.platform.iot.receiver.domain.model.InboundPacket;
import com.aquagrid.platform.iot.web.dto.DeviceDto;
import com.aquagrid.platform.identity.infrastructure.persistence.OrganizationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verification of Device Data Configuration against real PostGIS.
 *
 * <p>What is under test is one sentence, and every case below is a way it could quietly stop being
 * true:
 *
 * <blockquote><b>Configuration determines how data is used, not whether data is allowed in.</b>
 * </blockquote>
 *
 * <p>That sentence is easy to write into a design document and easy to lose in an implementation,
 * because every instinct a validation layer has is to reject. The most valuable tests here are
 * therefore the negative ones — the packet carrying a field nobody configured, the value outside its
 * declared range, the reading that fails its declared type — all of which must be <em>accepted and
 * stored</em>, and marked rather than dropped. If any of these ever starts refusing, the module has
 * become the thing it was built not to be, and it will be discovered when a fleet's firmware update
 * silently stops reporting.
 *
 * <p>Everything goes through {@code ReceiverGateway}, never the ingest port directly: the guarantees
 * being asserted are guarantees about the production reception path, and a test that bypassed it
 * would prove them about a path no device uses.
 */
@TestPropertySource(properties = {
        // The reception path is what is under test, not the credential check. A gateway key would
        // add a second reason for every assertion below to fail and tell nothing about this module.
        "aquagrid.iot.receiver.security.require-authentication=false"
})
class DeviceDataConfigurationIT extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private DeviceManagementService deviceService;
    @Autowired
    private DeviceDataConfigService configService;
    @Autowired
    private ParameterDiscoveryService discoveryService;
    @Autowired
    private DeviceParameterApi parameterApi;
    @Autowired
    private ReceiverGateway receiver;
    @Autowired
    private DeviceReadingRepository readingRepository;
    @Autowired
    private RawTelemetryRepository rawTelemetryRepository;
    @Autowired
    private OrganizationRepository organizationRepository;

    // ---- The central rule ----------------------------------------------------------------------

    @Test
    @DisplayName("a payload carrying fields nobody configured is accepted, whole")
    void acceptsAndPreservesUnconfiguredFields() {
        DeviceDto device = register();
        configure(device, "voltage", ParameterDataType.DOUBLE, "V", null, null);

        // The example from the brief: configured voltage and current, plus three fields the
        // catalogue has never heard of.
        ReceptionOutcome outcome = send(device, """
                {"deviceId":"%s","voltage":415,"current":8.2,"pumpStatus":true,
                 "temperature":42,"powerFactor":0.91,"frequency":50}
                """.formatted(device.networkAddress()));

        assertThat(outcome).isInstanceOf(ReceptionOutcome.Accepted.class);

        // The whole payload, key for key. Not the configured subset, and not a canonicalised
        // rewrite of it — this row is the answer to "what did the device actually send", and a row
        // that had been tidied could not answer it.
        RawTelemetry stored = rawPayloadFor(device);
        assertThat(stored.getPayload()).containsKeys(
                "voltage", "current", "pumpStatus", "temperature", "powerFactor", "frequency");
        assertThat(stored.getPayload()).containsEntry("powerFactor", 0.91);
        assertThat(stored.getProcessingStatus()).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("unconfigured fields are surfaced for configuration, not merely retained")
    void surfacesUnconfiguredFieldsForConfiguration() {
        DeviceDto device = register();

        send(device, """
                {"deviceId":"%s","motor_temperature":48.5,"fw_build":"2.14.1"}
                """.formatted(device.networkAddress()));

        List<DiscoveredParameter> discovered = discovered(device);

        // Storing an unknown field means nothing is lost. It does not mean anyone finds out: an
        // unconfigured parameter is on no dashboard, in no report and outside every alarm rule, so
        // without this queue it is indistinguishable from a field the device never sent.
        assertThat(discovered).extracting(DiscoveredParameter::getParameterName)
                .contains("motor_temperature", "fw_build");

        DiscoveredParameter motorTemp = discovered.stream()
                .filter(row -> row.getParameterName().equals("motor_temperature"))
                .findFirst().orElseThrow();
        assertThat(motorTemp.getSampleValue()).isEqualTo("48.5");
        assertThat(motorTemp.getDetectedDataType()).isEqualTo(ParameterDataType.DOUBLE);
        assertThat(motorTemp.getStatus()).isEqualTo(DiscoveryStatus.PENDING);

        // A string field is discovered too. The parsers only ever produced numbers, so a discovery
        // built on parsed metrics would miss exactly the fields a controller sends and a meter
        // does not.
        assertThat(discovered).filteredOn(row -> row.getParameterName().equals("fw_build"))
                .singleElement()
                .satisfies(row -> assertThat(row.getDetectedDataType())
                        .isEqualTo(ParameterDataType.TEXT));
    }

    @Test
    @DisplayName("envelope fields are not offered as parameters")
    void doesNotDiscoverEnvelopeFields() {
        DeviceDto device = register();

        send(device, """
                {"deviceId":"%s","timestamp":"%s","seq":7,"level":3.4}
                """.formatted(device.networkAddress(), Instant.now()));

        // Every uplink carries these. A queue whose first rows are deviceId, timestamp and seq is a
        // queue nobody scrolls past, and the parameters worth configuring are on page two.
        assertThat(discovered(device)).extracting(DiscoveredParameter::getParameterName)
                .contains("level")
                .doesNotContain("deviceId", "timestamp", "seq");
    }

    @Test
    @DisplayName("metrics the platform already understands are not offered as discoveries")
    void doesNotDiscoverPlatformMetrics() {
        DeviceDto device = register();

        // Vendor spellings of metrics MetricCatalog already declares, alongside one it does not.
        send(device, """
                {"deviceId":"%s","flowRate":3.1,"totalVolume":1200.0,"battery":3.6,"rssi":-71,
                 "motor_temperature":48.5}
                """.formatted(device.networkAddress()));

        List<String> names = discovered(device).stream()
                .map(DiscoveredParameter::getParameterName).toList();

        /*
         * MetricCatalog is configuration — it declares a label, unit, kind and category, and these
         * readings are already stored with their units and grouped correctly. Listing them would
         * ask an administrator to configure something demonstrably already working, and would open
         * the queue on the eight metrics every water meter sends. The one field nobody has an
         * opinion about is the one that should be asked about.
         */
        assertThat(names).containsExactly("motor_temperature");
    }

    @Test
    @DisplayName("a value outside its configured range is stored and flagged, never discarded")
    void keepsOutOfRangeValues() {
        DeviceDto device = register();
        configure(device, "pressure", ParameterDataType.DOUBLE, "bar", 0.0, 10.0);

        send(device, """
                {"deviceId":"%s","pressure":47.0}
                """.formatted(device.networkAddress()));

        DeviceReading reading = readingFor(device, "pressure");

        // The single most important assertion in this class. A pressure of 47 bar on a 10 bar main
        // is the most important reading of the day; dropping it to satisfy a range somebody typed
        // last March would destroy the evidence of the event the range exists to detect.
        assertThat(reading.getValue()).isEqualTo(47.0);
        assertThat(reading.getQuality()).isEqualTo(QualityStatus.OUT_OF_RANGE.name());
        assertThat(reading.getUnit()).isEqualTo("bar");
    }

    @Test
    @DisplayName("a configured parameter stamps its own unit and a VALID verdict")
    void stampsConfiguredUnitAndQuality() {
        DeviceDto device = register();
        configure(device, "water_level", ParameterDataType.DOUBLE, "m", 0.0, 20.0);

        send(device, """
                {"deviceId":"%s","water_level":8.4}
                """.formatted(device.networkAddress()));

        DeviceReading reading = readingFor(device, "water_level");
        assertThat(reading.getValue()).isEqualTo(8.4);
        assertThat(reading.getUnit()).isEqualTo("m");
        assertThat(reading.getQuality()).isEqualTo(QualityStatus.VALID.name());
        // Which definition judged it, so widening the range later does not make this verdict
        // unreadable — the id plus the history table is what reconstructs the rule of the day.
        assertThat(reading.getParameterId()).isNotNull();
    }

    @Test
    @DisplayName("an unconfigured reading is stored with UNKNOWN, which is not a defect")
    void storesUnconfiguredReadingsAsUnknown() {
        DeviceDto device = register();

        send(device, """
                {"deviceId":"%s","chlorine_residual":0.42}
                """.formatted(device.networkAddress()));

        DeviceReading reading = readingFor(device, "chlorine_residual");
        assertThat(reading.getValue()).isEqualTo(0.42);
        // UNKNOWN is the honest answer before an administrator has said anything about the
        // parameter, not a failure to evaluate one.
        assertThat(reading.getQuality()).isEqualTo(QualityStatus.UNKNOWN.name());
    }

    @Test
    @DisplayName("a mandatory parameter that did not arrive is recorded, not merely absent")
    void recordsMissingMandatoryParameters() {
        DeviceDto device = register();
        DeviceDataParameter level =
                configure(device, "water_level", ParameterDataType.DOUBLE, "m", 0.0, 20.0);
        configService.update(level.getId(), organizationId(), null, "test",
                update(builder -> builder.mandatory(true)));

        // A packet carrying something else entirely. The mandatory level is simply not in it, which
        // is the case under test — not a malformed packet, just an incomplete one.
        send(device, """
                {"deviceId":"%s","battery":3.6}
                """.formatted(device.networkAddress()));

        DeviceReading missing = readingFor(device, "water_level");

        // "The device stopped sending its level" and "nobody ever asked for a level" are different
        // facts, and a gap in a series says neither. Recording the absence is what makes it
        // queryable — and it is why a missing mandatory value never has to become a refused packet.
        assertThat(missing.getValue()).isNull();
        assertThat(missing.getQuality()).isEqualTo(QualityStatus.MISSING.name());
    }

    // ---- Templates and overrides ---------------------------------------------------------------

    @Test
    @DisplayName("a device inherits its type's template and may override one entry")
    void deviceOverridesTypeTemplate() {
        /*
         * A device type no other test registers.
         *
         * Device-type templates are tenant-wide by design, and every integration test shares the
         * SYSTEM tenant and a reused container. Seeding a WATER_METER template here would therefore
         * configure the fleet SimulatorIT drives — which is legitimate product behaviour and an
         * illegitimate way for one test to reach another.
         */
        String deviceType = "PUMP_CONTROLLER";
        DeviceDto device = register(deviceType);

        configService.create(organizationId(), null, "test", create(builder -> builder
                .scope(ParameterScope.DEVICE_TYPE).deviceType(deviceType)
                .parameterName("water_level").dataType(ParameterDataType.DOUBLE).unit("m")));
        configService.create(organizationId(), null, "test", create(builder -> builder
                .scope(ParameterScope.DEVICE_TYPE).deviceType(deviceType)
                .parameterName("battery").dataType(ParameterDataType.DOUBLE).unit("%")));

        // This one meter is from a different vendor and reports level in centimetres.
        configService.create(organizationId(), null, "test", create(builder -> builder
                .scope(ParameterScope.DEVICE).deviceId(device.id())
                .parameterName("water_level").dataType(ParameterDataType.DOUBLE).unit("cm")));

        var effective = parameterApi.effectiveForDevice(organizationId(), device.id(), deviceType);

        // Replaced entire, not merged field by field: a partial merge would leave an operator
        // unable to say what this device runs under by reading either row.
        assertThat(effective).hasSize(2);
        assertThat(effective.get("water_level").unit()).isEqualTo("cm");
        assertThat(effective.get("water_level").scope()).isEqualTo(ParameterScope.DEVICE);
        assertThat(effective.get("battery").unit()).isEqualTo("%");
    }

    @Test
    @DisplayName("a vendor spelling is matched in the payload and stored under the configured name")
    void matchesVendorSpellingAndStoresCanonicalName() {
        DeviceDto device = register();
        configService.create(organizationId(), null, "test", create(builder -> builder
                .scope(ParameterScope.DEVICE).deviceId(device.id())
                .parameterName("total_volume").payloadKey("cumulativeReading")
                .dataType(ParameterDataType.DOUBLE).unit("m3")));

        send(device, """
                {"deviceId":"%s","cumulativeReading":1834.5}
                """.formatted(device.networkAddress()));

        // Matched on the vendor's key, stored under the configured name — otherwise a chart built
        // on the configured name would find nothing, which is the whole point of allowing the two
        // to differ.
        DeviceReading reading = readingFor(device, "total_volume");
        assertThat(reading.getValue()).isEqualTo(1834.5);
        assertThat(reading.getUnit()).isEqualTo("m3");

        // And it is not reported as undescribed under its wire name.
        assertThat(discovered(device)).extracting(DiscoveredParameter::getParameterName)
                .doesNotContain("cumulativeReading");
    }

    // ---- Discovery hand-off --------------------------------------------------------------------

    @Test
    @DisplayName("configuring a discovered parameter closes its place in the queue")
    void configuringClosesTheDiscovery() {
        DeviceDto device = register();
        send(device, """
                {"deviceId":"%s","motor_temperature":48.5}
                """.formatted(device.networkAddress()));

        DiscoveredParameter pending = discovered(device).stream()
                .filter(row -> row.getParameterName().equals("motor_temperature"))
                .findFirst().orElseThrow();

        DeviceDataParameter created = configService.create(organizationId(), null, "test",
                create(builder -> builder
                        .scope(ParameterScope.DEVICE).deviceId(device.id())
                        .parameterName("motor_temperature").dataType(ParameterDataType.DOUBLE)
                        .unit("°C").discoveredParameterId(pending.getId())));

        DiscoveredParameter closed = discoveryService.require(pending.getId(), organizationId());

        // A queue that keeps asking about a parameter already defined is the fastest way to make a
        // queue nobody reads.
        assertThat(closed.getStatus()).isEqualTo(DiscoveryStatus.CONFIGURED);
        assertThat(closed.getParameterId()).isEqualTo(created.getId());
    }

    @Test
    @DisplayName("ignoring a discovery hides it and deletes nothing")
    void ignoreHidesAndKeepsEverything() {
        DeviceDto device = register();
        send(device, """
                {"deviceId":"%s","fw_build":"2.14.1"}
                """.formatted(device.networkAddress()));

        DiscoveredParameter pending = discovered(device).stream()
                .filter(row -> row.getParameterName().equals("fw_build"))
                .findFirst().orElseThrow();

        discoveryService.ignore(pending.getId(), organizationId(), null, "test", "never charted");

        // Off the queue...
        assertThat(discovered(device)).extracting(DiscoveredParameter::getParameterName)
                .doesNotContain("fw_build");
        // ...and the payload that carried it is exactly where it was. A parameter ignored last year
        // can be configured this year with its whole history intact, which is the only reason
        // "Ignore" is safe to offer at all.
        assertThat(rawPayloadFor(device).getPayload()).containsEntry("fw_build", "2.14.1");
    }

    // ---- Retention across the awkward paths ----------------------------------------------------

    @Test
    @DisplayName("a packet from a device nobody registered still has its payload kept")
    void keepsPayloadsOfRejectedPackets() {
        // No device row at all — the packet cannot be attributed to a tenant, and is refused.
        String address = "UNREGISTERED" + SEQ.incrementAndGet();
        UUID packetId = UUID.randomUUID();
        ReceptionOutcome outcome = receiver.receive(packet(packetId, address, """
                {"deviceId":"%s","flow_rate":3.1}
                """.formatted(address)));

        assertThat(outcome).isInstanceOf(ReceptionOutcome.Rejected.class);

        // Retention is an observer, not a pipeline stage, precisely so this row exists: a payload
        // refused because its device is not registered is the payload somebody most needs to read
        // during commissioning, and a stage placed anywhere in the chain would miss it.
        RawTelemetry stored = rawTelemetryRepository.findById(packetId).orElseThrow();
        assertThat(stored.getProcessingStatus()).isEqualTo("REJECTED");
        assertThat(stored.getProcessingError()).isNotBlank();
        assertThat(stored.getPayload()).containsEntry("flow_rate", 3.1);
        assertThat(stored.getOrganizationId()).isNull();
    }

    @Test
    @DisplayName("retiring a parameter stops it being interpreted, not being received")
    void deactivationRetainsData() {
        DeviceDto device = register();
        DeviceDataParameter level =
                configure(device, "water_level", ParameterDataType.DOUBLE, "m", 0.0, 20.0);
        send(device, """
                {"deviceId":"%s","water_level":8.4}
                """.formatted(device.networkAddress()));

        configService.deactivate(level.getId(), organizationId(), null, "test", "sensor removed");
        send(device, """
                {"deviceId":"%s","water_level":8.6}
                """.formatted(device.networkAddress()));

        List<DeviceReading> readings = readings(device, "water_level");

        // Both readings are there. The reading written while the parameter was live keeps the
        // verdict it was given; the one after it is UNKNOWN, because nothing describes the field
        // any more — which is a statement about attention, not about retention.
        assertThat(readings).hasSize(2);
        assertThat(readings).extracting(DeviceReading::getValue)
                .containsExactlyInAnyOrder(8.4, 8.6);
        assertThat(readings).extracting(DeviceReading::getQuality)
                .containsExactlyInAnyOrder(QualityStatus.VALID.name(), QualityStatus.UNKNOWN.name());
    }

    // ---- Fixtures ------------------------------------------------------------------------------

    private UUID organizationId() {
        return organizationRepository.findByCodeIgnoreCase("SYSTEM").orElseThrow().getId();
    }

    private DeviceDto register() {
        return register("WATER_METER");
    }

    private DeviceDto register(String deviceType) {
        String code = "DDC-" + SEQ.incrementAndGet();
        return deviceService.register(organizationId(), null, new DeviceDto.RegistrationRequest(
                code, "Data configuration fixture", deviceType, null, null, "LIVE", "HTTP",
                "NB_IOT",
                "Kamstrup", "flowIQ 2200", null, LocalDate.of(2026, 3, 14), "ACTIVE", null,
                Map.of("imei", String.format("35693803565%04d", SEQ.get())), "1.4.2"));
    }

    /** A device-scoped parameter, which is the shortest path to a configured reading. */
    private DeviceDataParameter configure(DeviceDto device, String name, ParameterDataType type,
                                          String unit, Double min, Double max) {
        return configService.create(organizationId(), null, "test", create(builder -> builder
                .scope(ParameterScope.DEVICE).deviceId(device.id())
                .parameterName(name).dataType(type).unit(unit).minValue(min).maxValue(max)));
    }

    private ReceptionOutcome send(DeviceDto device, String json) {
        return receiver.receive(packet(UUID.randomUUID(), device.networkAddress(), json));
    }

    private static InboundPacket packet(UUID packetId, String address, String json) {
        Map<IdentifierType, String> identifiers = new EnumMap<>(IdentifierType.class);
        identifiers.put(IdentifierType.NETWORK_ADDRESS, address);
        return InboundPacket.builder()
                .packetId(packetId)
                .transport("HTTP")
                .receivedAt(Instant.now())
                .payload(json.getBytes(StandardCharsets.UTF_8))
                .contentType("application/json")
                .sourceIp("127.0.0.1")
                .correlationId(UUID.randomUUID().toString())
                .identifiers(identifiers)
                .build();
    }

    private RawTelemetry rawPayloadFor(DeviceDto device) {
        return rawTelemetryRepository.search(organizationId(), device.id(), null, null, null,
                        PageRequest.of(0, 1))
                .getContent().getFirst();
    }

    private DeviceReading readingFor(DeviceDto device, String metric) {
        List<DeviceReading> found = readings(device, metric);
        assertThat(found).as("readings of '%s' for %s", metric, device.deviceCode()).isNotEmpty();
        return found.getFirst();
    }

    /** Newest first, over a window wide enough that device-clock skew cannot exclude a reading. */
    private List<DeviceReading> readings(DeviceDto device, String metric) {
        Instant now = Instant.now();
        return readingRepository.findSeries(device.id(), metric,
                now.minus(java.time.Duration.ofDays(1)), now.plus(java.time.Duration.ofDays(1)));
    }

    private List<DiscoveredParameter> discovered(DeviceDto device) {
        return discoveryService.search(organizationId(), device.id(), null, DiscoveryStatus.PENDING,
                null, PageRequest.of(0, 50)).getContent();
    }

    // ---- Command builders ----------------------------------------------------------------------
    //
    // The commands are records with twenty-odd components, most of them defaulted. Building them
    // positionally at fifteen call sites is a defect waiting for two adjacent booleans to be
    // transposed — which the compiler cannot catch and no assertion here would notice.

    private static ParameterCommands.Create create(java.util.function.UnaryOperator<CreateBuilder> f) {
        return f.apply(new CreateBuilder()).build();
    }

    private static ParameterCommands.Update update(java.util.function.UnaryOperator<UpdateBuilder> f) {
        return f.apply(new UpdateBuilder()).build();
    }

    private static final class CreateBuilder {
        private ParameterScope scope = ParameterScope.DEVICE;
        private String deviceType;
        private UUID deviceId;
        private String parameterName;
        private ParameterDataType dataType = ParameterDataType.DOUBLE;
        private String unit;
        private String payloadKey;
        private Double minValue;
        private Double maxValue;
        private UUID discoveredParameterId;

        CreateBuilder scope(ParameterScope value) { this.scope = value; return this; }
        CreateBuilder deviceType(String value) { this.deviceType = value; return this; }
        CreateBuilder deviceId(UUID value) { this.deviceId = value; return this; }
        CreateBuilder parameterName(String value) { this.parameterName = value; return this; }
        CreateBuilder dataType(ParameterDataType value) { this.dataType = value; return this; }
        CreateBuilder unit(String value) { this.unit = value; return this; }
        CreateBuilder payloadKey(String value) { this.payloadKey = value; return this; }
        CreateBuilder minValue(Double value) { this.minValue = value; return this; }
        CreateBuilder maxValue(Double value) { this.maxValue = value; return this; }
        CreateBuilder discoveredParameterId(UUID value) { this.discoveredParameterId = value; return this; }

        ParameterCommands.Create build() {
            return new ParameterCommands.Create(scope, deviceType, deviceId, parameterName, null,
                    null, dataType, unit, "OTHER", payloadKey, false, true, false, true,
                    minValue, maxValue, null, null, null, true, null, null, discoveredParameterId);
        }
    }

    private static final class UpdateBuilder {
        private Boolean mandatory;

        UpdateBuilder mandatory(Boolean value) { this.mandatory = value; return this; }

        ParameterCommands.Update build() {
            return new ParameterCommands.Update(null, null, null, null, null, null, mandatory,
                    null, null, null, null, null, null, null, null, null, null, false);
        }
    }
}
