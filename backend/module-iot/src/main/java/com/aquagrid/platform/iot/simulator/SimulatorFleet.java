package com.aquagrid.platform.iot.simulator;

import com.aquagrid.platform.identity.api.IdentityApi;
import com.aquagrid.platform.identity.api.TenantSummary;
import com.aquagrid.platform.iot.api.DeviceMessage;
import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.domain.model.DeviceReading;
import com.aquagrid.platform.iot.domain.model.DeviceSource;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceReadingRepository;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The set of registered devices the simulator drives, and their state between ticks.
 *
 * <p><b>The fleet is the device registry, filtered.</b> Nothing is invented here: a meter appears
 * because somebody registered a device with {@code source = SIMULATOR}, and it disappears when they
 * change it or delete it. That inversion is the whole point of this class — the simulator used to
 * fabricate its own devices, which meant simulated telemetry was addressed to rows that did not
 * exist, could not be resolved by the receiver, could not be excluded from a water balance, and left
 * {@code DeviceSource.SIMULATOR} as an intention nothing honoured.
 *
 * <p>Reloading is incremental. Meter state — the register, the battery, an injected leak — is keyed
 * by device id and survives a refresh, because a reload happens every few minutes and one that reset
 * every meter would mean no fault ever lasted long enough to be detected, and the cumulative
 * register would saw-tooth instead of climbing.
 *
 * <p>A device with no {@code networkAddress} is <b>excluded and reported</b>, not silently skipped.
 * It is registered as simulated but unaddressable, so no packet the simulator emits for it could
 * ever be resolved back to it; a fleet that quietly dropped it would look identical to one where the
 * device was working, which is the failure this module was rebuilt to end.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "aquagrid.iot.transports", name = "simulator", havingValue = "true")
public class SimulatorFleet {

    /**
     * How far back to look for a device's last register reading when it joins the fleet.
     *
     * <p>Long enough to survive a weekend of downtime, bounded so the lookup stays an index seek. A
     * meter quiet for longer than this restarts from a synthetic opening balance, which is a visible
     * discontinuity rather than a silent one — and a simulated meter that has been silent for a
     * month has no meaningful register to preserve.
     */
    private static final Duration REGISTER_LOOKBACK = Duration.ofDays(30);

    private final DeviceRepository deviceRepository;
    private final DeviceReadingRepository deviceReadingRepository;
    private final IdentityApi identityApi;
    private final SimulatorProperties properties;

    private final Map<UUID, SimulatedMeter> meters = new ConcurrentHashMap<>();

    private volatile UUID organizationId;
    private volatile String organizationCode;
    private volatile ZoneId timezone = ZoneId.of("UTC");
    private volatile Instant lastLoadedAt;
    private volatile List<String> unaddressable = List.of();

    /**
     * Re-reads the fleet from the device registry.
     *
     * <p>Read-only and explicitly tenant-scoped. The simulator runs on a scheduler thread with no
     * authenticated principal, so there is no tenant to inherit — the organisation is resolved once,
     * through the published {@link IdentityApi}, and passed into every query. Reaching into
     * identity's persistence layer for it would breach the module boundary that makes {@code
     * module-iot} extractable.
     *
     * @return the number of meters now in the fleet
     */
    @Transactional(readOnly = true)
    public synchronized int reload() {
        resolveTenant();

        List<Device> registered = deviceRepository.findBySourceAndOrganizationId(
                DeviceSource.SIMULATOR.name(), organizationId);

        Set<UUID> present = new HashSet<>();
        List<String> missingAddress = new ArrayList<>();

        for (Device device : registered) {
            if (device.getNetworkAddress() == null || device.getNetworkAddress().isBlank()) {
                missingAddress.add(device.getDeviceCode());
                continue;
            }
            present.add(device.getId());
            meters.computeIfAbsent(device.getId(), id -> new SimulatedMeter(
                    device, timezone, properties.interval(),
                    openingVolume(id), openingFrameCounter(id)));
        }

        // Devices that stopped being simulator-source, or were deleted, stop being driven. Dropping
        // their state is correct: the row no longer says the platform may author its telemetry.
        //
        // This is the cutover. An administrator sets source = LIVE on the row and the virtual meter
        // stops here, at the next refresh, with no code change, no migration and no restart — the
        // physical device is already registered at the same address, so its first uplink resolves
        // through the row the simulator was driving a moment ago. Logged because it is the moment
        // an operator most wants confirmed.
        List<String> released = meters.entrySet().stream()
                .filter(entry -> !present.contains(entry.getKey()))
                .map(entry -> entry.getValue().deviceCode())
                .toList();
        meters.keySet().retainAll(present);
        if (!released.isEmpty()) {
            log.info("Released {} device(s) from the simulator — they are no longer source=SIMULATOR "
                    + "and are now expected to report for themselves: {}", released.size(), released);
        }

        this.unaddressable = List.copyOf(missingAddress);
        this.lastLoadedAt = Instant.now();

        if (!missingAddress.isEmpty()) {
            log.warn("{} simulator-source device(s) have no network address and cannot be driven: {}."
                            + " Re-register them with their transport's identity field set.",
                    missingAddress.size(), missingAddress);
        }
        log.info("Simulator fleet: {} meter(s) across tenant {} ({})",
                meters.size(), organizationCode, organizationId);
        return meters.size();
    }

    /**
     * The register to resume a meter from.
     *
     * <p>A cumulative meter register only ever climbs, and consumption analytics read it as a
     * difference between two points. Restarting the application at zero would therefore not merely
     * lose history — it would post a large negative consumption for the interval spanning the
     * restart, which is precisely the artefact a simulator built to validate those analytics must
     * not manufacture.
     */
    private double openingVolume(UUID deviceId) {
        Instant now = Instant.now();
        Optional<DeviceReading> last = deviceReadingRepository
                .findSeries(deviceId, DeviceMessage.Metrics.VOLUME, now.minus(REGISTER_LOOKBACK), now)
                .stream()
                .findFirst();
        return last.map(DeviceReading::getValue)
                // No history: a deterministic opening balance derived from the device id, so a
                // meter reads the same on a fresh database as it did on the last one.
                .orElseGet(() -> (double) Math.floorMod(deviceId.hashCode(), 50_000));
    }

    /**
     * The frame counter to resume a meter from.
     *
     * <p>The counterpart of {@link #openingVolume}, and needed for a sharper reason. The receiver's
     * replay protection keys on the frame counter and remembers every one it has seen for the
     * length of its window — so a meter that restarted at zero would spend that entire window
     * re-sending counters already claimed, having every packet refused as a duplicate. The fleet
     * would look alive in the logs and be silent in the readings table.
     *
     * <p>Looked up over the same window the register uses rather than the replay window: they are
     * configured independently, and resuming from a counter <em>higher</em> than strictly necessary
     * costs nothing, while resuming from one too low costs a day of telemetry.
     */
    private int openingFrameCounter(UUID deviceId) {
        Integer last = deviceReadingRepository.findLastFrameCounter(
                deviceId, Instant.now().minus(REGISTER_LOOKBACK));
        return last == null ? 0 : last;
    }

    private void resolveTenant() {
        if (organizationId != null) {
            return;
        }
        TenantSummary tenant = identityApi.findTenantByCode(properties.organizationCode())
                .orElseThrow(() -> new IllegalStateException(
                        "Simulator is enabled but no tenant matches aquagrid.iot.simulator"
                                + ".organization-code=" + properties.organizationCode()
                                + ". Seed the tenant, name a different one, or disable the simulator."));
        this.organizationId = tenant.id();
        this.organizationCode = tenant.code();
        this.timezone = ZoneId.of(tenant.timezone());
    }

    /**
     * Whether the fleet is stale enough to re-read.
     *
     * <p>Polled rather than event-driven, deliberately. An event on device registration would
     * couple the registry to the simulator — a listener the device service must not fail on, in a
     * module that is only ever loaded in development. A device registered mid-run starts emitting
     * within one refresh window, which is the right latency for a tool nobody is watching to the
     * second.
     */
    boolean isStale() {
        return lastLoadedAt == null
                || lastLoadedAt.plus(properties.fleetRefresh()).isBefore(Instant.now());
    }

    Collection<SimulatedMeter> meters() {
        return meters.values();
    }

    Optional<SimulatedMeter> meter(UUID deviceId) {
        return Optional.ofNullable(meters.get(deviceId));
    }

    int size() {
        return meters.size();
    }

    /** Device codes registered as simulated that carry no address, and so cannot be driven. */
    List<String> unaddressable() {
        return unaddressable;
    }

    Instant lastLoadedAt() {
        return lastLoadedAt;
    }

    UUID organizationId() {
        return organizationId;
    }

    String organizationCode() {
        return organizationCode;
    }

    ZoneId timezone() {
        return timezone;
    }
}
