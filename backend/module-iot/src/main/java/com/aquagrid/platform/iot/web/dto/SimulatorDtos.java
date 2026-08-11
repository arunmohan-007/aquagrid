package com.aquagrid.platform.iot.web.dto;

import com.aquagrid.platform.iot.simulator.SimulatedFault;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The simulator's API contract (Module 17).
 *
 * <p>Grouped in one file because they are one contract read as a unit — a status and the fleet it
 * describes — and because each is a handful of lines whose meaning comes from the others. Splitting
 * them into five files would spread one screen of information across five.
 */
public final class SimulatorDtos {

    private SimulatorDtos() {
    }

    /**
     * What the simulator is doing.
     *
     * @param state             {@code RUNNING} or {@code PAUSED}
     * @param organizationCode  the tenant whose simulator-source devices are being driven
     * @param fleetSize         meters currently driven
     * @param unaddressable     device codes registered as simulated but carrying no network
     *                          address. They cannot be driven and are listed rather than dropped —
     *                          silence here is the exact failure this module was rebuilt to end
     * @param intervalSeconds   simulated reporting interval
     * @param lastTickAt        when the fleet last reported
     * @param lastTickMillis    how long that pass took, end to end through the receiver
     * @param emitted           uplinks handed to the receiver
     * @param accepted          ingested as telemetry
     * @param duplicates        recognised as already ingested
     * @param rejected          refused by the receiver. <b>Non-zero means the fleet is
     *                          misconfigured</b> — nothing spoofs the simulator, so a refusal is
     *                          always a statement about a device row, not about traffic
     * @param suppressed        ticks a meter stayed silent for under a comms-loss fault. Simulated
     *                          outage, not failure
     * @param metersLeaking     meters with an active leak
     * @param metersTampered    meters flagged tampered
     * @param metersSilent      meters currently inside a comms-loss window
     */
    @Schema(name = "SimulatorStatus", description = "Fleet simulator state and totals")
    public record SimulatorStatusDto(
            String state,
            String organizationCode,
            int fleetSize,
            List<String> unaddressable,
            long intervalSeconds,
            Instant lastTickAt,
            long lastTickMillis,
            long emitted,
            long accepted,
            long duplicates,
            long rejected,
            long suppressed,
            long metersLeaking,
            long metersTampered,
            long metersSilent,
            /*
             * Whether the fleet is currently emitting fields no configuration describes, and which
             * ones. Reported rather than merely settable, because an operator who sees the discovery
             * queue filling up needs to be able to tell in one place whether that is a device doing
             * something new or a developer having left this switched on.
             */
            boolean extraTestParameters,
            List<String> extraTestParameterKeys
    ) {
    }

    /**
     * One simulated meter.
     *
     * @param deviceId            the registered device it drives — the same id the device registry
     *                            and the receiver console use, so an operator can follow one meter
     *                            across all three screens
     * @param deviceCode          operator-facing device code
     * @param networkAddress      the address its packets claim
     * @param transport           the network it emulates
     * @param baselineDailyLitres its household demand profile, litres per day
     * @param reportingIntervalSeconds its own duty cycle, from the device's
     *                            {@code reportingIntervalSeconds} attribute where it declares one.
     *                            A property of the device, so it stays true after the physical unit
     *                            takes the row over
     * @param suspended           silenced for cutover: still in the fleet, deliberately not
     *                            reporting, because a physical device is now answering at this
     *                            address. The durable control is the device's {@code source}
     * @param activeFaults        conditions currently in force. Empty means healthy
     * @param lastEmittedAt       when it last produced an uplink
     * @param uplinksEmitted      uplinks produced
     * @param uplinksSuppressed   ticks it stayed silent for
     * @param lastStatus          what the receiver did with its most recent uplink
     * @param lastErrorCode       the platform error code when that was a rejection
     * @param lastErrorDetail     the operator-facing reason for it
     */
    @Schema(name = "SimulatedDevice", description = "A registered device the simulator is driving")
    public record SimulatedDeviceDto(
            UUID deviceId,
            String deviceCode,
            String networkAddress,
            String transport,
            double baselineDailyLitres,
            long reportingIntervalSeconds,
            boolean suspended,
            List<SimulatedFault> activeFaults,
            Instant lastEmittedAt,
            long uplinksEmitted,
            long uplinksSuppressed,
            String lastStatus,
            String lastErrorCode,
            String lastErrorDetail
    ) {
    }

    /**
     * A request to put one meter into a fault state.
     *
     * <p>The fault is named; its magnitude is not. An operator validating an alarm should be
     * reproducing the condition the platform will really see, and a body that could specify
     * 900 L/min would be testing the threshold against a flow no distribution main produces.
     */
    @Schema(name = "FaultInjectionRequest", description = "Fault to apply to a simulated meter")
    public record FaultInjectionRequest(
            @NotNull(message = "fault is required")
            SimulatedFault fault
    ) {
    }
}
