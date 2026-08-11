package com.aquagrid.platform.iot.web.controller;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.iot.simulator.ConfiguredParameterComposer;
import com.aquagrid.platform.iot.simulator.DeviceSimulator;
import com.aquagrid.platform.iot.simulator.SimulatedFault;
import com.aquagrid.platform.iot.simulator.SimulatedMeter;
import com.aquagrid.platform.iot.web.dto.SimulatorDtos.FaultInjectionRequest;
import com.aquagrid.platform.iot.web.dto.SimulatorDtos.SimulatedDeviceDto;
import com.aquagrid.platform.iot.web.dto.SimulatorDtos.SimulatorStatusDto;
import com.aquagrid.platform.security.core.Permissions;
import com.aquagrid.platform.security.core.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Simulator control endpoint (Module 17).
 *
 * <p>Exists only when the simulator transport is enabled, so a production deployment has no
 * simulator surface to secure at all — not a route returning 403, no route. That is the same
 * conditional the beans behind it carry, and it is stronger than authorisation: an endpoint that is
 * absent cannot be reached by a misconfigured role.
 *
 * <p>Every route is gated on {@code iot:simulator:run}, including the read-only ones. The status
 * lists device codes, addresses and fault state for a whole fleet, which is operational detail about
 * the estate rather than a public dashboard, and the permission is held only by the platform
 * administrator by default.
 */
@Tag(name = "Simulator", description = "Fleet simulator status and control")
@RestController
@RequestMapping(value = ApiPaths.API_V1 + "/simulator", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@ConditionalOnProperty(prefix = "aquagrid.iot.transports", name = "simulator", havingValue = "true")
public class SimulatorController {

    private final DeviceSimulator simulator;
    private final ConfiguredParameterComposer parameterComposer;

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('" + Permissions.SIMULATOR_RUN + "')")
    @Operation(summary = "Simulator state and totals",
            description = """
                    Fleet size, run state, and what the receiver did with everything sent so far.

                    `rejected` is the number to read first. Nothing spoofs the simulator, so a
                    refusal is never stray traffic — it is always a statement about a device row,
                    and it is the same refusal the physical device would get at that address.""")
    public SimulatorStatusDto status() {
        requireSimulatedTenant();
        Instant now = Instant.now();
        List<SimulatedMeter> meters = simulator.meters();
        DeviceSimulator.Counters counters = simulator.counters();

        return new SimulatorStatusDto(
                simulator.state().name(),
                simulator.organizationCode(),
                simulator.fleetSize(),
                simulator.unaddressable(),
                simulator.interval().toSeconds(),
                simulator.lastTickAt(),
                simulator.lastTickMillis(),
                counters.emitted(),
                counters.accepted(),
                counters.duplicates(),
                counters.rejected(),
                counters.suppressed(),
                meters.stream().filter(SimulatedMeter::isLeaking).count(),
                meters.stream().filter(SimulatedMeter::isTampered).count(),
                meters.stream().filter(meter -> meter.isSilent(now)).count(),
                parameterComposer.isExtraTestParameters(),
                ConfiguredParameterComposer.probeKeys());
    }

    @PostMapping("/extra-test-parameters")
    @PreAuthorize("hasAuthority('" + Permissions.SIMULATOR_RUN + "')")
    @Operation(summary = "Emit fields no configuration describes",
            description = """
                    Adds unconfigured parameters to every simulated JSON uplink, so the promise that
                    unknown data is accepted, stored whole and offered for configuration can be
                    exercised end to end rather than asserted.

                    The fields are ordinary payload keys — nothing marks them as a test — so what
                    arrives is what a firmware update genuinely adding a sensor would produce. They
                    appear on the Discovered Parameters screen within one reporting interval.

                    Off by default. A fleet permanently emitting undescribed fields would keep the
                    discovery queue permanently non-empty and train operators to ignore it.""")
    public SimulatorStatusDto extraTestParameters(@RequestParam boolean enabled) {
        requireSimulatedTenant();
        parameterComposer.setExtraTestParameters(enabled);
        return status();
    }

    @GetMapping("/devices")
    @PreAuthorize("hasAuthority('" + Permissions.SIMULATOR_RUN + "')")
    @Operation(summary = "The registered devices being driven",
            description = """
                    One entry per device with `source = SIMULATOR` that carries a network address.

                    Faulty meters sort first — a list whose point is to show what is wrong should
                    not put it on page three.""")
    public List<SimulatedDeviceDto> devices() {
        requireSimulatedTenant();
        Instant now = Instant.now();
        return simulator.meters().stream()
                .map(meter -> toDto(meter, now))
                .sorted(Comparator
                        .comparingInt((SimulatedDeviceDto dto) -> dto.activeFaults().isEmpty() ? 1 : 0)
                        .thenComparing(SimulatedDeviceDto::deviceCode,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @PostMapping("/start")
    @PreAuthorize("hasAuthority('" + Permissions.SIMULATOR_RUN + "')")
    @Operation(summary = "Start or resume emission",
            description = "Reloads the fleet first, so a device registered a moment ago joins now.")
    public SimulatorStatusDto start() {
        requireSimulatedTenant();
        simulator.start();
        return status();
    }

    @PostMapping("/pause")
    @PreAuthorize("hasAuthority('" + Permissions.SIMULATOR_RUN + "')")
    @Operation(summary = "Stop emission",
            description = """
                    The fleet and every meter's state — register, battery, injected faults — are
                    kept, so resuming continues the run rather than restarting it.""")
    public SimulatorStatusDto pause() {
        requireSimulatedTenant();
        simulator.pause();
        return status();
    }

    @PostMapping("/reload")
    @PreAuthorize("hasAuthority('" + Permissions.SIMULATOR_RUN + "')")
    @Operation(summary = "Re-read the fleet from the device registry",
            description = """
                    Picks up devices registered, retired or switched between SIMULATOR and LIVE
                    since the last read. Happens on its own every few minutes; this is for when
                    waiting is the wrong answer.""")
    public SimulatorStatusDto reload() {
        requireSimulatedTenant();
        simulator.reloadFleet();
        return status();
    }

    @PostMapping("/step")
    @PreAuthorize("hasAuthority('" + Permissions.SIMULATOR_RUN + "')")
    @Operation(summary = "Run N reporting intervals immediately",
            description = """
                    Compresses the simulated clock while every packet still travels the full
                    production path. The endpoint a validation run uses: waiting a real minute per
                    simulated minute makes any assertion about a trend — a leak crossing an MNF
                    threshold, a cell reaching its replacement voltage — a test measured in hours.

                    Runs synchronously and is bounded; the response reports how many ticks ran.""")
    public SimulatorStatusDto step(@RequestParam(defaultValue = "1") int ticks) {
        requireSimulatedTenant();
        simulator.step(ticks);
        return status();
    }

    @PostMapping("/devices/{deviceId}/faults")
    @PreAuthorize("hasAuthority('" + Permissions.SIMULATOR_RUN + "')")
    @Operation(summary = "Put one meter into a fault state",
            description = """
                    The fault is named; its magnitude is the engine's. An operator validating an
                    alarm should be reproducing the condition the platform will really see, and a
                    body that could specify 900 L/min would be testing the threshold against a flow
                    no distribution main produces.

                    `HEALTHY` clears every active fault, which is what makes a scenario repeatable.""")
    public SimulatedDeviceDto injectFault(@PathVariable UUID deviceId,
                                          @Valid @RequestBody FaultInjectionRequest request) {
        requireSimulatedTenant();
        simulator.inject(deviceId, request.fault()).orElseThrow(SimulatorController::notInFleet);
        return device(deviceId);
    }

    @PostMapping("/devices/{deviceId}/suspend")
    @PreAuthorize("hasAuthority('" + Permissions.SIMULATOR_RUN + "')")
    @Operation(summary = "Silence or resume one meter, for cutover to a physical device",
            description = """
                    Takes effect at once, and leaves the rest of the fleet running — which is what
                    makes a gradual replacement possible, with real and simulated meters reporting
                    side by side for as long as the rollout takes.

                    This is the immediate control, not the durable one. Setting the device's source
                    to LIVE is what removes it from the fleet permanently and survives a restart; a
                    suspension that outlived the process would be a second, invisible answer to
                    which devices are simulated.""")
    public SimulatedDeviceDto suspend(@PathVariable UUID deviceId,
                                      @RequestParam(defaultValue = "true") boolean suspended) {
        requireSimulatedTenant();
        simulator.suspend(deviceId, suspended).orElseThrow(SimulatorController::notInFleet);
        return device(deviceId);
    }

    private SimulatedDeviceDto device(UUID deviceId) {
        Instant now = Instant.now();
        return simulator.meters().stream()
                .filter(meter -> meter.deviceId().equals(deviceId))
                .findFirst()
                .map(meter -> toDto(meter, now))
                .orElseThrow(SimulatorController::notInFleet);
    }

    private SimulatedDeviceDto toDto(SimulatedMeter meter, Instant now) {
        DeviceSimulator.DeviceOutcome outcome = simulator.lastOutcome(meter.deviceId()).orElse(null);
        return new SimulatedDeviceDto(
                meter.deviceId(),
                meter.deviceCode(),
                meter.networkAddress(),
                meter.profile() == null ? null : meter.profile().name(),
                meter.baselineDailyLitres(),
                meter.reportingInterval().toSeconds(),
                meter.isSuspended(),
                meter.activeFaults(now),
                meter.lastEmittedAt(),
                meter.uplinksEmitted(),
                meter.uplinksSuppressed(),
                outcome == null ? null : outcome.status(),
                outcome == null ? null : outcome.errorCode(),
                outcome == null ? null : outcome.detail());
    }

    /**
     * Refuses a caller from a different tenant than the one being simulated.
     *
     * <p>The platform's tenancy invariant applies here as everywhere, and this endpoint is an
     * unusual shape for it: the simulator targets one configured organisation rather than the
     * caller's, so without this check an administrator of tenant B would read tenant A's device
     * codes, addresses and fault state out of the status view. The tenant is taken from the
     * authenticated principal and compared, never accepted as a parameter.
     */
    private void requireSimulatedTenant() {
        UUID caller = SecurityUtils.currentOrganizationId()
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_RESOLVED));
        UUID simulated = simulator.organizationId();
        if (simulated != null && !simulated.equals(caller)) {
            throw new BusinessException(ErrorCode.OPERATION_NOT_PERMITTED,
                    "The simulator is running against a different organisation.");
        }
    }

    private static BusinessException notInFleet() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                "That device is not being simulated. It must be registered with source=SIMULATOR "
                        + "and carry the identity field its transport requires.");
    }
}
