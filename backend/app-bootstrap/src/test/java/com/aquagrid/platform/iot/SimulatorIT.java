package com.aquagrid.platform.iot;

import com.aquagrid.platform.AbstractIntegrationTest;
import com.aquagrid.platform.iot.api.DeviceMessage;
import com.aquagrid.platform.iot.application.service.DeviceManagementService;
import com.aquagrid.platform.iot.dataconfig.application.command.ParameterCommands;
import com.aquagrid.platform.iot.dataconfig.application.service.DeviceDataConfigService;
import com.aquagrid.platform.iot.dataconfig.domain.model.ParameterDataType;
import com.aquagrid.platform.iot.dataconfig.domain.model.ParameterScope;
import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.domain.model.DeviceReading;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceReadingRepository;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceRepository;
import com.aquagrid.platform.iot.simulator.DeviceSimulator;
import com.aquagrid.platform.iot.simulator.SimulatedFault;
import com.aquagrid.platform.iot.simulator.SimulatedMeter;
import com.aquagrid.platform.iot.web.dto.DeviceDto;
import com.aquagrid.platform.identity.infrastructure.persistence.OrganizationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verification of Module 17 — the fleet simulator — against real PostGIS.
 *
 * <p>What is under test is not "does the simulator produce numbers". It is the requirement the
 * module exists to satisfy: <b>a simulated device must be replaceable by a physical one with no
 * change to anything else</b>. That claim decomposes into things a test can actually pin down, and
 * each one below is a way the claim could quietly stop being true:
 *
 * <ul>
 *   <li>the fleet is the device registry, not a private list — so a device joins by being
 *       registered and leaves by being reclassified;</li>
 *   <li>traffic goes through the receiver, so it is authenticated, resolved and validated exactly
 *       as a real device's is, and lands in the same tables;</li>
 *   <li>nothing downstream can tell the difference — the reading records the device's own
 *       transport, not "SIMULATOR";</li>
 *   <li>the cutover is a change to one column, and it works while other devices keep simulating.</li>
 * </ul>
 *
 * <p>The simulator is enabled here rather than in {@code application-test.yml} so that every other
 * integration test keeps running without one, which is also the arrangement that proves the module
 * is genuinely optional.
 */
@TestPropertySource(properties = {
        "aquagrid.iot.transports.simulator=true",
        // Loaded but silent: this test drives the clock itself with step(), so a background
        // scheduler emitting concurrently would make every count it asserts on a race.
        "aquagrid.iot.simulator.auto-start=false",
        "aquagrid.iot.simulator.interval=1m",
        // No spontaneous faults. The assertions are about injected conditions, and a leak the
        // engine rolled on its own would make this test fail once every few hundred runs.
        "aquagrid.iot.simulator.faults.leak=0",
        "aquagrid.iot.simulator.faults.burst=0",
        "aquagrid.iot.simulator.faults.comms-loss=0",
        "aquagrid.iot.simulator.faults.tamper=0",
        /*
         * A real gateway credential, exercised for real. The simulator presents the plaintext, the
         * receiver checks it against the hash — the same ApiKeyAuthenticator, the same comparison
         * the physical network server will face after cutover. Authentication is deliberately left
         * required: turning it off here would make every assertion below pass in a configuration
         * no deployment runs.
         */
        "aquagrid.iot.receiver.security.require-authentication=true",
        "aquagrid.iot.receiver.security.gateways[0].principal=test-gateway",
        "aquagrid.iot.receiver.security.gateways[0].api-key-sha256="
                + "f315d667d343b4b802fa25fe570db37cbc33dcd1a8f5ab41d0983352992f9991",
        "aquagrid.iot.simulator.gateway-api-key=aquagrid-local-dev-key"
})
class SimulatorIT extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private DeviceSimulator simulator;
    @Autowired
    private DeviceManagementService deviceService;
    @Autowired
    private DeviceDataConfigService dataConfigService;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private DeviceReadingRepository readingRepository;
    @Autowired
    private OrganizationRepository organizationRepository;

    private UUID organizationId() {
        return organizationRepository.findByCodeIgnoreCase("SYSTEM").orElseThrow().getId();
    }

    private String code(String prefix) {
        return prefix + "-" + SEQ.incrementAndGet();
    }

    @Test
    @DisplayName("drives a registered simulator-source device and writes telemetry through the receiver")
    void drivesRegisteredDevice() {
        DeviceDto registered = register(code("SIM-NB"), "NB_IOT",
                Map.of("imei", nextImei()), "SIMULATOR");
        simulator.reloadFleet();

        DeviceSimulator.Counters before = simulator.counters();
        simulator.step(1);
        DeviceSimulator.Counters after = simulator.counters();

        // Accepted, not merely emitted. The simulator's own view of how much it sent proves
        // nothing; the number that matters is how much the platform took.
        assertThat(after.accepted()).isGreaterThan(before.accepted());
        assertThat(after.rejected()).isEqualTo(before.rejected());

        List<DeviceReading> volume = readings(registered.id(), DeviceMessage.Metrics.VOLUME);
        assertThat(volume).isNotEmpty();

        // The reading is stamped with the network the device is registered on. This is the
        // assertion that the rest of the platform cannot tell simulated telemetry from real:
        // if this ever reads "SIMULATOR", every downstream consumer has gained a special case.
        assertThat(volume.getFirst().getTransport()).isEqualTo("NB_IOT");

        // The device row's radio state was refreshed by the ingest path, exactly as a real uplink
        // refreshes it — so the device-health dashboard needs no simulator-specific query.
        Device stored = deviceRepository.findById(registered.id()).orElseThrow();
        assertThat(stored.getLastSeenAt()).isNotNull();
        assertThat(stored.getBatteryV()).isNotNull();
    }

    @Test
    @DisplayName("a LoRaWAN device emits a ChirpStack envelope its own parser decodes")
    void emitsTransportShapedPayloads() {
        DeviceDto registered = register(code("SIM-LORA"), "LORAWAN",
                Map.of("devEui", nextDevEui()), "SIMULATOR");
        simulator.reloadFleet();

        simulator.step(1);

        // Volume can only be present if the envelope was recognised, the base64 frame extracted and
        // the little-endian register decoded — three stages of the real ingestion path that a
        // simulator emitting a tidy internal object would never have touched.
        assertThat(readings(registered.id(), DeviceMessage.Metrics.VOLUME)).isNotEmpty();
        assertThat(readings(registered.id(), DeviceMessage.Metrics.BATTERY_VOLTAGE)).isNotEmpty();
    }

    @Test
    @DisplayName("an injected tamper reaches the readings table as the alarm engine will read it")
    void injectedFaultsSurfaceInTelemetry() {
        DeviceDto registered = register(code("SIM-TAMPER"), "NB_IOT",
                Map.of("imei", nextImei()), "SIMULATOR");
        simulator.reloadFleet();

        assertThat(simulator.inject(registered.id(), SimulatedFault.TAMPER)).isPresent();
        simulator.step(1);

        assertThat(readings(registered.id(), DeviceMessage.Metrics.TAMPER))
                .isNotEmpty()
                .allSatisfy(reading -> assertThat(reading.getValue()).isEqualTo(1.0));
    }

    @Test
    @DisplayName("a comms-loss fault produces silence, not a zero reading")
    void commsLossSuppressesUplinks() {
        DeviceDto registered = register(code("SIM-SILENT"), "NB_IOT",
                Map.of("imei", nextImei()), "SIMULATOR");
        simulator.reloadFleet();
        simulator.inject(registered.id(), SimulatedFault.COMMS_LOSS);

        DeviceSimulator.Counters before = simulator.counters();
        simulator.step(1);

        // Silence and zero flow are different faults. A platform that cannot tell them apart cannot
        // distinguish a dead gateway from an empty house, so the simulator must be able to produce
        // the first without producing a reading.
        assertThat(simulator.counters().suppressed()).isGreaterThan(before.suppressed());
        assertThat(readings(registered.id(), DeviceMessage.Metrics.VOLUME)).isEmpty();
    }

    @Test
    @DisplayName("the cumulative register only ever climbs across successive uplinks")
    void registerIsMonotonic() {
        DeviceDto registered = register(code("SIM-REG"), "NB_IOT",
                Map.of("imei", nextImei()), "SIMULATOR");
        simulator.reloadFleet();

        simulator.step(5);

        // findSeries returns newest first, so a correct run descends. A register that went backwards
        // would post negative consumption for the interval — the one arithmetic error a simulator
        // built to validate consumption analytics must never make.
        List<Double> series = readings(registered.id(), DeviceMessage.Metrics.VOLUME).stream()
                .map(DeviceReading::getValue)
                .toList();
        assertThat(series).hasSizeGreaterThan(1).isSortedAccordingTo((a, b) -> Double.compare(b, a));
    }

    @Test
    @DisplayName("each device reports on its own declared duty cycle")
    void honoursPerDeviceReportingInterval() {
        DeviceDto slow = register(code("SIM-SLOW"), "NB_IOT",
                Map.of("imei", nextImei()), "SIMULATOR");
        // A six-hour LoRaWAN-style duty cycle expressed on the device, where it stays true after a
        // physical unit takes the row over.
        setAttribute(slow.id(), SimulatedMeter.REPORTING_INTERVAL_ATTRIBUTE, 21_600);
        DeviceDto fast = register(code("SIM-FAST"), "NB_IOT",
                Map.of("imei", nextImei()), "SIMULATOR");
        simulator.reloadFleet();

        // Three one-minute steps: both report on the first, only the fast one thereafter.
        simulator.step(3);

        assertThat(readings(fast.id(), DeviceMessage.Metrics.VOLUME)).hasSize(3);
        assertThat(readings(slow.id(), DeviceMessage.Metrics.VOLUME)).hasSize(1);
    }

    @Test
    @DisplayName("setting source to LIVE releases the device, and the rest keep simulating")
    void cutoverReleasesOneDeviceWithoutStoppingTheFleet() {
        String imei = nextImei();
        DeviceDto cutting = register(code("SIM-CUT"), "NB_IOT", Map.of("imei", imei), "SIMULATOR");
        DeviceDto staying = register(code("SIM-STAY"), "NB_IOT",
                Map.of("imei", nextImei()), "SIMULATOR");
        simulator.reloadFleet();
        assertThat(fleetIds()).contains(cutting.id(), staying.id());

        // The whole cutover: one column, through the ordinary device API. No migration, no restart,
        // no simulator-specific call — and the row, its id, its address and its history all stay.
        deviceService.update(cutting.id(), organizationId(), null,
                request(null, "NB_IOT", Map.of("imei", imei), "LIVE"));
        simulator.reloadFleet();

        assertThat(fleetIds()).doesNotContain(cutting.id());
        // Gradual replacement is the requirement: a fleet is converted a few meters at a time, so
        // releasing one must not disturb its neighbours.
        assertThat(fleetIds()).contains(staying.id());

        int readingsAtCutover = readings(cutting.id(), DeviceMessage.Metrics.VOLUME).size();
        simulator.step(1);
        assertThat(readings(cutting.id(), DeviceMessage.Metrics.VOLUME)).hasSize(readingsAtCutover);
        assertThat(readings(staying.id(), DeviceMessage.Metrics.VOLUME)).isNotEmpty();
    }

    @Test
    @DisplayName("suspending a meter silences it immediately without touching the registry")
    void suspensionSilencesOneMeter() {
        DeviceDto registered = register(code("SIM-SUSP"), "NB_IOT",
                Map.of("imei", nextImei()), "SIMULATOR");
        simulator.reloadFleet();

        assertThat(simulator.suspend(registered.id(), true)).contains(true);
        simulator.step(2);

        // An engineer standing at a newly fitted meter needs the virtual one quiet now, not at the
        // next fleet refresh — while the device row still says SIMULATOR, because they have not
        // finished commissioning yet.
        assertThat(readings(registered.id(), DeviceMessage.Metrics.VOLUME)).isEmpty();
        assertThat(fleetIds()).contains(registered.id());

        simulator.suspend(registered.id(), false);
        simulator.step(1);
        assertThat(readings(registered.id(), DeviceMessage.Metrics.VOLUME)).isNotEmpty();
    }

    /*
     * A meter rebuilt from the registry must resume its frame counter, not restart it.
     *
     * The receiver's replay protection keys on the frame counter and remembers every one it has
     * seen for the length of its window. A simulated meter whose counter reset to zero therefore
     * spent that entire window re-sending counters already claimed — every packet refused as a
     * duplicate, no readings written, and a log that said the fleet was running. This is what that
     * looked like in practice: eight meters "emitting" and a readings table that had stopped
     * twenty minutes earlier.
     */
    @Test
    @DisplayName("a meter rebuilt from the registry resumes its frame counter rather than replaying")
    void frameCounterSurvivesAFleetRebuild() {
        String imei = nextImei();
        DeviceDto registered = register(code("SIM-FCNT"), "NB_IOT", Map.of("imei", imei), "SIMULATOR");
        simulator.reloadFleet();
        simulator.step(3);

        int readingsBefore = readings(registered.id(), DeviceMessage.Metrics.VOLUME).size();
        assertThat(readingsBefore).isEqualTo(3);

        // Force the meter to be discarded and reconstructed, which is what a restart does to it:
        // out of the fleet on one reload, back in on the next, as a brand-new SimulatedMeter.
        deviceService.update(registered.id(), organizationId(), null,
                request(null, "NB_IOT", Map.of("imei", imei), "LIVE"));
        simulator.reloadFleet();
        deviceService.update(registered.id(), organizationId(), null,
                request(null, "NB_IOT", Map.of("imei", imei), "SIMULATOR"));
        simulator.reloadFleet();

        DeviceSimulator.Counters before = simulator.counters();
        simulator.step(2);
        DeviceSimulator.Counters after = simulator.counters();

        // The packets must be accepted, not collapsed into duplicates by replay protection.
        assertThat(after.duplicates()).isEqualTo(before.duplicates());
        assertThat(after.accepted()).isEqualTo(before.accepted() + 2);
        assertThat(readings(registered.id(), DeviceMessage.Metrics.VOLUME))
                .hasSize(readingsBefore + 2);
    }

    @Test
    @DisplayName("a device registered without an address is reported, not silently skipped")
    void unaddressableDevicesAreReported() {
        DeviceDto stranded = register(code("SIM-NOADDR"), "ETHERNET", Map.of(), "SIMULATOR");
        simulator.reloadFleet();

        // It cannot be driven — no address means no packet it emitted could ever resolve back to it.
        // Dropping it quietly would look exactly like a device that was working, which is the
        // failure mode this module was rebuilt to end.
        assertThat(fleetIds()).doesNotContain(stranded.id());
        assertThat(simulator.unaddressable()).contains(stranded.deviceCode());
    }

    @Test
    @DisplayName("a configured parameter is emitted, and never on top of the meter model's own")
    void dataConfigurationExtendsTheModelWithoutDisplacingIt() {
        DeviceDto registered = register(code("SIM-CFG"), "NB_IOT",
                Map.of("imei", nextImei()), "SIMULATOR");

        /*
         * Two parameters on this one device. `pump_status` is something the water-meter model knows
         * nothing about, so the composer should supply it — that is the feature: a device type's
         * data configuration *is* the simulator's model for everything the meter model does not
         * cover, rather than a second Java class per device type.
         *
         * `battery` is the trap. It is the same physical quantity the model already emits, spelled
         * the way a tenant might catalogue it rather than the way the platform canonicalises it. A
         * composer matching only on canonical names writes a percentage over the model's 3.6 V cell,
         * MetricSanityValidator refuses the packet as a decode fault, and the entire fleet goes
         * silent — with nothing on screen connecting it to a parameter somebody configured. That
         * happened; this is the test that would have caught it.
         */
        configureDeviceParameter(registered.id(), "pump_status", "%", null, null);
        configureDeviceParameter(registered.id(), "battery", "%", 0.0, 100.0);
        simulator.reloadFleet();

        simulator.step(1);

        assertThat(readings(registered.id(), "pump_status")).isNotEmpty();

        // The cell voltage the model produced, not a percentage the configuration implied.
        assertThat(readings(registered.id(), DeviceMessage.Metrics.BATTERY_VOLTAGE))
                .isNotEmpty()
                .allSatisfy(reading -> assertThat(reading.getValue()).isBetween(2.8, 4.0));

        // And the packet was accepted rather than refused as implausible.
        assertThat(readings(registered.id(), DeviceMessage.Metrics.VOLUME)).isNotEmpty();
    }

    // ---- Fixtures ----------------------------------------------------------------------------

    /** A device-scoped data parameter, which is the shortest path to a configured emission. */
    private void configureDeviceParameter(UUID deviceId, String name, String unit,
                                          Double min, Double max) {
        dataConfigService.create(organizationId(), null, "test",
                new ParameterCommands.Create(
                        ParameterScope.DEVICE, null, deviceId, name, null, null,
                        ParameterDataType.DOUBLE, unit, "OTHER", null,
                        false, true, false, true, min, max, null, null, null, true, null, null, null));
    }

    private List<UUID> fleetIds() {
        return simulator.meters().stream().map(SimulatedMeter::deviceId).toList();
    }

    private List<DeviceReading> readings(UUID deviceId, String metric) {
        return readingRepository.findSeries(deviceId, metric,
                Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(1)));
    }

    private void setAttribute(UUID deviceId, String key, Object value) {
        Device device = deviceRepository.findById(deviceId).orElseThrow();
        device.getAttributes().put(key, value);
        deviceRepository.saveAndFlush(device);
    }

    private DeviceDto register(String deviceCode, String transport,
                               Map<String, String> communication, String source) {
        return deviceService.register(organizationId(), null,
                request(deviceCode, transport, communication, source));
    }

    /** IMEIs and DevEUIs must be unique per tenant, and these tests share one. */
    private static String nextImei() {
        return String.format("35693803564%04d", SEQ.incrementAndGet());
    }

    private static String nextDevEui() {
        return String.format("A81758FFFE03%04X", SEQ.incrementAndGet());
    }

    private DeviceDto.RegistrationRequest request(String deviceCode, String transport,
                                                  Map<String, String> communication, String source) {
        return new DeviceDto.RegistrationRequest(
                deviceCode,
                "Simulated ward meter",
                "WATER_METER",
                "AST-0091",
                null,
                source,
                null,
                transport,
                "Kamstrup",
                "flowIQ 2200",
                null,
                LocalDate.of(2026, 3, 14),
                "PROVISIONED",
                new double[]{76.9366, 8.5241},
                communication,
                "1.4.2");
    }
}
