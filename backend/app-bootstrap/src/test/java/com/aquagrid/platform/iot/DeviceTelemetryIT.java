package com.aquagrid.platform.iot;

import com.aquagrid.platform.AbstractIntegrationTest;
import com.aquagrid.platform.common.error.ResourceNotFoundException;
import com.aquagrid.platform.iot.api.DeviceMessage;
import com.aquagrid.platform.iot.application.service.DeviceManagementService;
import com.aquagrid.platform.iot.application.service.DeviceTelemetryService;
import com.aquagrid.platform.iot.domain.model.DeviceReading;
import com.aquagrid.platform.iot.domain.model.MetricCatalog;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceReadingRepository;
import com.aquagrid.platform.iot.web.dto.DeviceDto;
import com.aquagrid.platform.iot.web.dto.TelemetryDtos.DeviceTelemetryDto;
import com.aquagrid.platform.iot.web.dto.TelemetryDtos.MetricGroupDto;
import com.aquagrid.platform.iot.web.dto.TelemetryDtos.MetricReadingDto;
import com.aquagrid.platform.iot.web.dto.TelemetryDtos.MetricSeriesDto;
import com.aquagrid.platform.identity.infrastructure.persistence.OrganizationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verification of Module 7 — device telemetry — against real PostGIS.
 *
 * <p>What is under test is the reshaping, not the storage. The readings table holds a flat stream of
 * (metric, value, time) rows, and this module's whole job is to turn that into the answer an
 * operator asked for: the <em>latest</em> value of each metric, grouped by what the metric
 * describes, with enough context to judge whether it is current. Each of those three is a way the
 * answer could quietly be wrong — a stale value shown as current, a metric silently dropped because
 * the platform has no catalogue entry for it, a group ordered by storage rather than by meaning.
 */
class DeviceTelemetryIT extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private DeviceTelemetryService telemetryService;
    @Autowired
    private DeviceManagementService deviceService;
    @Autowired
    private DeviceReadingRepository readingRepository;
    @Autowired
    private OrganizationRepository organizationRepository;

    private UUID organizationId() {
        return organizationRepository.findByCodeIgnoreCase("SYSTEM").orElseThrow().getId();
    }

    @Test
    @DisplayName("returns the latest value of each metric, grouped by what it describes")
    void groupsLatestReadingsByCategory() {
        DeviceDto device = register();
        Instant now = Instant.now();

        // Two readings of the same metric: only the newer may survive into the summary.
        write(device.id(), DeviceMessage.Metrics.VOLUME, 1000.0, now.minus(Duration.ofMinutes(10)));
        write(device.id(), DeviceMessage.Metrics.VOLUME, 1200.0, now.minus(Duration.ofMinutes(1)));
        write(device.id(), DeviceMessage.Metrics.FLOW_RATE, 2.5, now.minus(Duration.ofMinutes(1)));
        write(device.id(), DeviceMessage.Metrics.BATTERY_VOLTAGE, 3.4, now.minus(Duration.ofMinutes(1)));
        write(device.id(), DeviceMessage.Metrics.TAMPER, 1.0, now.minus(Duration.ofMinutes(1)));

        DeviceTelemetryDto telemetry = telemetryService.telemetryFor(organizationId(), device.id());

        assertThat(reading(telemetry, DeviceMessage.Metrics.VOLUME))
                .get()
                .satisfies(volume -> {
                    assertThat(volume.value()).isEqualTo(1200.0);
                    // A cumulative register, so the client knows its ramp is not the consumption.
                    assertThat(volume.kind()).isEqualTo(MetricCatalog.Kind.COUNTER);
                    assertThat(volume.unit()).isEqualTo("L");
                    assertThat(volume.label()).isEqualTo("Meter reading");
                });

        // Consumption first: the group order is the catalogue's, which is the order the question is
        // asked in, not whatever order the rows happened to be stored in.
        assertThat(telemetry.groups()).first()
                .extracting(MetricGroupDto::category)
                .isEqualTo(MetricCatalog.Category.CONSUMPTION);

        assertThat(categories(telemetry)).containsExactly(
                MetricCatalog.Category.CONSUMPTION,
                MetricCatalog.Category.DEVICE_HEALTH,
                MetricCatalog.Category.CONDITION);

        // A flag is a condition, not a measurement — the client renders it as set or clear.
        assertThat(reading(telemetry, DeviceMessage.Metrics.TAMPER))
                .get().extracting(MetricReadingDto::kind).isEqualTo(MetricCatalog.Kind.FLAG);
    }

    @Test
    @DisplayName("every reading carries its age, because a value without one cannot be judged")
    void readingsCarryTheirAge() {
        DeviceDto device = register();
        write(device.id(), DeviceMessage.Metrics.PRESSURE, 3.2,
                Instant.now().minus(Duration.ofHours(2)));

        DeviceTelemetryDto telemetry = telemetryService.telemetryFor(organizationId(), device.id());

        // 3.2 bar from two hours ago and 3.2 bar from last March are the same number and completely
        // different facts. Presenting the first without the second is what makes a reading unusable.
        assertThat(reading(telemetry, DeviceMessage.Metrics.PRESSURE))
                .get().extracting(MetricReadingDto::ageSeconds)
                .satisfies(age -> assertThat(age).isBetween(7_100L, 7_300L));
    }

    @Test
    @DisplayName("a metric the platform has no catalogue entry for is shown, not dropped")
    void keepsUncataloguedMetrics() {
        DeviceDto device = register();
        write(device.id(), "chlorine_residual", 0.42, Instant.now().minus(Duration.ofMinutes(1)));

        DeviceTelemetryDto telemetry = telemetryService.telemetryFor(organizationId(), device.id());

        // The device measured it and the platform stored it. Hiding it here because there is no
        // entry for it would lose data that demonstrably exists, on the only screen that shows it.
        assertThat(reading(telemetry, "chlorine_residual"))
                .get()
                .satisfies(reading -> {
                    assertThat(reading.value()).isEqualTo(0.42);
                    assertThat(reading.category()).isEqualTo(MetricCatalog.Category.OTHER);
                    assertThat(reading.label()).isEqualTo("chlorine_residual");
                });
    }

    @Test
    @DisplayName("a reading older than the current-state window is not presented as current")
    void excludesStaleReadings() {
        DeviceDto device = register();
        write(device.id(), DeviceMessage.Metrics.VOLUME, 500.0,
                Instant.now().minus(Duration.ofDays(45)));

        DeviceTelemetryDto telemetry = telemetryService.telemetryFor(organizationId(), device.id());

        // Something has to be the outer edge. A value from six weeks ago shown beside live ones,
        // in a panel titled with the device's current state, is history presented as present tense.
        assertThat(reading(telemetry, DeviceMessage.Metrics.VOLUME)).isEmpty();
    }

    @Test
    @DisplayName("a series is returned oldest first, which is the order a chart is drawn in")
    void seriesIsChronological() {
        DeviceDto device = register();
        Instant now = Instant.now();
        write(device.id(), DeviceMessage.Metrics.FLOW_RATE, 1.0, now.minus(Duration.ofMinutes(30)));
        write(device.id(), DeviceMessage.Metrics.FLOW_RATE, 2.0, now.minus(Duration.ofMinutes(20)));
        write(device.id(), DeviceMessage.Metrics.FLOW_RATE, 3.0, now.minus(Duration.ofMinutes(10)));

        MetricSeriesDto series = telemetryService.seriesFor(organizationId(), device.id(),
                DeviceMessage.Metrics.FLOW_RATE, now.minus(Duration.ofHours(1)), now);

        // The repository returns newest first because the packet feed reads that way. Reversing here
        // rather than in the client keeps both callers from each having an opinion about ordering.
        assertThat(series.points()).extracting("value").containsExactly(1.0, 2.0, 3.0);
        assertThat(series.truncated()).isFalse();
        assertThat(series.unit()).isEqualTo("L/min");
    }

    @Test
    @DisplayName("another tenant's device is absent rather than forbidden")
    void refusesCrossTenantReads() {
        DeviceDto device = register();

        // Indistinguishable from a device that does not exist. Anything finer turns this endpoint
        // into a way to test which device ids are real.
        assertThatThrownBy(() -> telemetryService.telemetryFor(UUID.randomUUID(), device.id()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("the ingest path stamps units from the same catalogue the API serves")
    void unitsComeFromOneDeclaration() {
        // The unit lived in a switch inside the ingest service and the label in a map in the
        // browser, so adding a metric meant edits in two languages that nothing checked agreed.
        assertThat(MetricCatalog.unitOf(DeviceMessage.Metrics.VOLUME)).isEqualTo("L");
        assertThat(MetricCatalog.unitOf(DeviceMessage.Metrics.FLOW_RATE)).isEqualTo("L/min");
        assertThat(MetricCatalog.of(DeviceMessage.Metrics.VOLUME).kind())
                .isEqualTo(MetricCatalog.Kind.COUNTER);
        assertThat(MetricCatalog.all()).extracting("metric")
                .contains(DeviceMessage.Metrics.VOLUME, DeviceMessage.Metrics.PRESSURE, "rssi", "snr");
    }

    // ---- Fixtures ----------------------------------------------------------------------------

    private static Optional<MetricReadingDto> reading(DeviceTelemetryDto telemetry, String metric) {
        return telemetry.groups().stream()
                .flatMap(group -> group.readings().stream())
                .filter(reading -> reading.metric().equals(metric))
                .findFirst();
    }

    private static List<MetricCatalog.Category> categories(DeviceTelemetryDto telemetry) {
        return telemetry.groups().stream().map(MetricGroupDto::category).toList();
    }

    private void write(UUID deviceId, String metric, double value, Instant observedAt) {
        DeviceReading reading = new DeviceReading();
        reading.setOrganizationId(organizationId());
        reading.setDeviceId(deviceId);
        reading.setMetric(metric);
        reading.setValue(value);
        reading.setUnit(MetricCatalog.unitOf(metric));
        reading.setObservedAt(observedAt);
        reading.setReceivedAt(observedAt);
        reading.setTransport("NB_IOT");
        readingRepository.saveAndFlush(reading);
    }

    private DeviceDto register() {
        int seq = SEQ.incrementAndGet();
        return deviceService.register(organizationId(), null, new DeviceDto.RegistrationRequest(
                "TLM-" + seq,
                "Ward 9 meter",
                "WATER_METER",
                "AST-9001",
                null,
                null,
                null,
                "NB_IOT",
                "Kamstrup",
                "flowIQ 2200",
                null,
                LocalDate.of(2026, 3, 14),
                "PROVISIONED",
                new double[]{76.9366, 8.5241},
                Map.of("imei", String.format("35693803571%04d", seq)),
                "1.4.2"));
    }
}
