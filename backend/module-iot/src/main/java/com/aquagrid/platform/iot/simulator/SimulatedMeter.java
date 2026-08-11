package com.aquagrid.platform.iot.simulator;

import com.aquagrid.platform.iot.domain.model.CommunicationProfile;
import com.aquagrid.platform.iot.domain.model.Device;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The simulated state of one <b>registered</b> meter.
 *
 * <p>The registration is the point. This class used to invent its own device — a synthetic
 * {@code SIM%013d} EUI that existed in the simulator's map and nowhere else — which meant simulated
 * traffic could never be resolved, addressed, filtered or excluded the way real traffic is, and that
 * marking a device {@code source = SIMULATOR} recorded an intention nothing acted on. A meter here
 * stands behind a row in {@code iot.devices}: it emits on that device's network, under that device's
 * address, with that device's credentials, into that device's tenant.
 *
 * <p>It is a <b>virtual field device</b>, not a data generator. Everything it holds is something the
 * physical unit it stands in for also holds — a register that only climbs, a cell that only drains,
 * a frame counter, a reporting schedule, provisioning secrets. When the physical device arrives, it
 * takes over the same row and the same address, and nothing downstream of the receiver can tell the
 * difference or has to be told.
 *
 * <p>Faults are first-class because they are what the simulator exists to generate: a leak is a slow
 * persistent addition to baseline flow; a burst is a sudden large flow that ends; comms loss
 * suppresses uplinks for a window; battery decay drifts voltage down over time. Each is something
 * the alarm and NRW modules are built to detect, and detecting them requires producing them.
 *
 * <p><b>Mutating methods are synchronised.</b> Ticks run on the scheduler thread while an operator's
 * fault injection arrives on an HTTP worker; without the lock a burst could be applied halfway
 * through the tick that was reading it, and the register — the one number that must only ever move
 * forwards — is exactly what a lost update would corrupt.
 */
public final class SimulatedMeter {

    /**
     * Device attribute naming a meter's own reporting schedule, in seconds.
     *
     * <p>On {@code Device.attributes} rather than in simulator configuration, because it describes
     * the <em>device</em>, not the simulation: a battery-powered LoRaWAN meter reporting every six
     * hours and a mains NB-IoT logger reporting every five minutes have genuinely different duty
     * cycles, and the value stays true of the row after the physical unit takes it over. A fleet
     * that all reported on one global interval would give the alarm engine's silence detection a
     * uniformity no real estate has.
     */
    public static final String REPORTING_INTERVAL_ATTRIBUTE = "reportingIntervalSeconds";

    private final UUID deviceId;
    private final UUID organizationId;
    private final String deviceCode;
    /**
     * The device's registered type, carried so the payload composer can resolve the data
     * configuration this meter is expected to report under. A property of the device row, not of the
     * simulation — the physical unit that takes this row over has the same type.
     */
    private final String deviceType;
    private final String networkAddress;
    private final CommunicationProfile profile;
    private final ZoneId timezone;
    private final double baselineDailyLitres;
    private final Duration reportingInterval;
    private final Map<String, Object> provisioning;
    private final Random rng;

    private double cumulativeVolumeLitres;
    private double batteryVoltage = 3.6;
    private int frameCounter;
    private Instant lastEmittedAt;
    private Instant lastIntegratedAt;
    private Instant nextDueAt;
    private long uplinksEmitted;
    private long uplinksSuppressed;
    private boolean suspended;

    // Active fault state. A meter with none of these set is "healthy".
    private double leakRateLpm;      // added to every reading while active
    private Instant burstUntil;      // a burst adds high flow until this instant
    private double burstRateLpm;
    private Instant commsLostUntil;  // uplinks suppressed while now < commsLostUntil
    private boolean tampered;
    private boolean reverseFlow;

    /**
     * Binds a meter to its device row.
     *
     * @param fallbackInterval    the fleet-wide reporting interval, used where the device declares
     *                            none of its own
     * @param openingVolumeLitres the register to resume from — the device's last recorded cumulative
     *                            volume, or a deterministic opening balance where it has none. See
     *                            {@link SimulatorFleet} for why resuming matters
     * @param openingFrameCounter the frame counter to resume from, for the same reason a physical
     *                            meter keeps its own in non-volatile memory: the receiver's replay
     *                            protection has already claimed every counter this device has used,
     *                            and restarting from zero would have every packet refused as a
     *                            duplicate until the replay window expired
     */
    SimulatedMeter(Device device, ZoneId timezone, Duration fallbackInterval,
                   double openingVolumeLitres, int openingFrameCounter) {
        this.deviceId = device.getId();
        this.organizationId = device.getOrganizationId();
        this.deviceCode = device.getDeviceCode();
        this.deviceType = device.getDeviceType();
        this.networkAddress = device.getNetworkAddress();
        this.profile = CommunicationProfile.from(device.getTransport());
        this.timezone = timezone;
        this.cumulativeVolumeLitres = openingVolumeLitres;
        this.frameCounter = openingFrameCounter;
        this.reportingInterval = readInterval(device, fallbackInterval);

        // The provisioning block as registered — ciphertext and all. Held by reference rather than
        // decrypted here: the credential presenter decrypts per uplink exactly as the receiver does
        // on the verifying side, so no plaintext device secret is ever resident in the fleet.
        this.provisioning = Map.copyOf(device.getProvisioning());

        // Seeded from the device id, not the clock. A given meter therefore has the same demand
        // profile and the same noise sequence on every run, which is what makes a simulated
        // reproduction of a reported bug actually reproduce it.
        long seed = device.getId().getMostSignificantBits() ^ device.getId().getLeastSignificantBits();
        this.rng = new Random(seed);
        // 300–1000 L/day, the residential spread. Derived from the seed so households differ from
        // each other but a household does not differ from itself between runs.
        this.baselineDailyLitres = 300 + Math.floorMod(seed, 700);
    }

    /**
     * Reads the device's declared reporting interval, falling back to the fleet's.
     *
     * <p>Clamped to at least a second. An attribute of zero would make the meter due on every pass
     * of the scheduler and turn one misconfigured row into a write loop bounded only by the
     * receiver's per-device rate limit.
     */
    private static Duration readInterval(Device device, Duration fallback) {
        Object declared = device.getAttributes().get(REPORTING_INTERVAL_ATTRIBUTE);
        if (declared == null) {
            return fallback;
        }
        try {
            long seconds = declared instanceof Number number
                    ? number.longValue()
                    : Long.parseLong(String.valueOf(declared).trim());
            return seconds <= 0 ? fallback : Duration.ofSeconds(Math.max(1, seconds));
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /**
     * Whether this meter is due to report.
     *
     * <p>Each meter keeps its own schedule, so one pass of the simulator's clock produces uplinks
     * from the meters whose interval has elapsed and silence from the rest — which is what a real
     * mixed fleet looks like on the wire, and what a silence-detection rule has to cope with.
     */
    synchronized boolean isDue(Instant now) {
        return nextDueAt == null || !now.isBefore(nextDueAt);
    }

    /**
     * Advances the meter to {@code now} and returns the flow rate to report, or {@code null} if it
     * is inside a comms-loss window and emits nothing.
     *
     * <p>The register is integrated over the <em>elapsed</em> time, not over the nominal interval,
     * and that is deliberate: a meter that was silent for forty minutes has still been passing water
     * for forty minutes. Its next successful uplink must show the whole gap, because that is what
     * the physical register would show, and an analytic reading consumption as a difference between
     * two uplinks would otherwise lose every litre delivered during an outage.
     */
    synchronized Double emit(Instant now, DiurnalDemandModel model) {
        Duration elapsed = lastIntegratedAt == null
                ? reportingInterval
                : Duration.between(lastIntegratedAt, now);
        if (elapsed.isNegative() || elapsed.isZero()) {
            elapsed = reportingInterval;
        }

        // The schedule advances whether or not anyone hears the meter. A device does not stop being
        // due because its gateway is down.
        nextDueAt = now.plus(reportingInterval);
        lastIntegratedAt = now;

        double baseline = model.baselineFlow(baselineDailyLitres, now, timezone);
        double flow = model.jitter(baseline, rng);
        if (leakRateLpm > 0) flow += leakRateLpm;
        if (burstUntil != null && now.isBefore(burstUntil)) flow += burstRateLpm;
        if (reverseFlow) flow = -flow;

        // Integrate flow over the interval into the cumulative register, the way a real meter does.
        // Reverse flow still turns the register backwards: that is a fault the platform must be able
        // to see in the data, not one the simulator should tidy away.
        double minutes = elapsed.toMillis() / 60_000.0;
        cumulativeVolumeLitres = Math.max(0, cumulativeVolumeLitres + flow * minutes);

        if (commsLostUntil != null && now.isBefore(commsLostUntil)) {
            uplinksSuppressed++;
            return null;
        }

        lastEmittedAt = now;
        uplinksEmitted++;
        return flow;
    }

    /** The cumulative volume to report, rounded the way a meter encoder would. */
    public synchronized double cumulativeVolume() {
        return Math.round(cumulativeVolumeLitres * 10.0) / 10.0; // decilitre resolution
    }

    /**
     * Battery decays with every transmission.
     *
     * <p>~0.0005 V per uplink: a meter reporting hourly reaches 3.0 V — the replacement threshold —
     * in roughly two years, which is the number a deployment plans battery logistics against.
     */
    synchronized double drainBattery() {
        batteryVoltage = Math.max(2.8, batteryVoltage - 0.0005);
        return batteryVoltage;
    }

    // ---- Fault injection ------------------------------------------------------------------------
    // Called both by the scenario engine (spontaneous) and by the control endpoint (deliberate). The
    // same methods for both, so an injected fault is indistinguishable downstream from one the
    // engine rolled — a test that could tell them apart would not be testing the production path.

    synchronized void startLeak(double rateLpm) {
        this.leakRateLpm = Math.max(this.leakRateLpm, rateLpm);
    }

    synchronized void triggerBurst(Instant now, Duration duration, double rateLpm) {
        this.burstUntil = now.plus(duration);
        this.burstRateLpm = rateLpm;
    }

    synchronized void loseComms(Instant now, Duration duration) {
        this.commsLostUntil = now.plus(duration);
    }

    synchronized void setTampered(boolean tampered) {
        this.tampered = tampered;
    }

    synchronized void setReverseFlow(boolean reverseFlow) {
        this.reverseFlow = reverseFlow;
    }

    /** Drops the cell to the replacement threshold, so a battery alarm can be exercised at once. */
    synchronized void depleteBattery() {
        this.batteryVoltage = 2.95;
    }

    /** Clears every active fault, returning the meter to its demand curve. */
    synchronized void recover() {
        this.leakRateLpm = 0;
        this.burstUntil = null;
        this.burstRateLpm = 0;
        this.commsLostUntil = null;
        this.tampered = false;
        this.reverseFlow = false;
    }

    // ---- Cutover --------------------------------------------------------------------------------

    /**
     * Stops or resumes simulation of this one meter, without touching the rest of the fleet.
     *
     * <p>The immediate half of a cutover. When a physical device is fitted at an address the
     * simulator is driving, both would be reporting for the same meter until the registry is
     * updated — so an engineer standing at the meter needs to silence the virtual one now, not at
     * the next fleet refresh.
     *
     * <p><b>Runtime only, and deliberately.</b> The durable control is the device's
     * {@code source} column: setting it to {@code LIVE} removes the meter from the fleet at the next
     * reload and keeps it out across restarts. A suspension that survived a restart would be a
     * second, invisible source of truth about which devices are simulated, disagreeing with the one
     * the rest of the platform reads.
     */
    public synchronized void setSuspended(boolean suspended) {
        this.suspended = suspended;
    }

    public synchronized boolean isSuspended() {
        return suspended;
    }

    // ---- State ----------------------------------------------------------------------------------

    synchronized int nextFrameCounter() {
        return ++frameCounter;
    }

    public synchronized double batteryVoltage() {
        return batteryVoltage;
    }

    public synchronized boolean isLeaking() {
        return leakRateLpm > 0;
    }

    public synchronized boolean isBursting(Instant now) {
        return burstUntil != null && now.isBefore(burstUntil);
    }

    public synchronized boolean isSilent(Instant now) {
        return commsLostUntil != null && now.isBefore(commsLostUntil);
    }

    public synchronized boolean isTampered() {
        return tampered;
    }

    public synchronized boolean isReverseFlow() {
        return reverseFlow;
    }

    /** The faults currently active, for the status view. Empty means healthy. */
    public synchronized List<SimulatedFault> activeFaults(Instant now) {
        List<SimulatedFault> active = new ArrayList<>();
        if (isLeaking()) active.add(SimulatedFault.LEAK);
        if (isBursting(now)) active.add(SimulatedFault.BURST);
        if (isSilent(now)) active.add(SimulatedFault.COMMS_LOSS);
        if (tampered) active.add(SimulatedFault.TAMPER);
        if (reverseFlow) active.add(SimulatedFault.REVERSE_FLOW);
        if (batteryVoltage <= 3.0) active.add(SimulatedFault.BATTERY_CRITICAL);
        return active;
    }

    public synchronized Instant lastEmittedAt() {
        return lastEmittedAt;
    }

    public synchronized long uplinksEmitted() {
        return uplinksEmitted;
    }

    public synchronized long uplinksSuppressed() {
        return uplinksSuppressed;
    }

    public UUID deviceId() {
        return deviceId;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public String deviceCode() {
        return deviceCode;
    }

    /** The device's registered type, used to resolve its configured data parameters. */
    public String deviceType() {
        return deviceType;
    }

    /** The address its network reaches it on — what the emitted packet claims as its identifier. */
    public String networkAddress() {
        return networkAddress;
    }

    public CommunicationProfile profile() {
        return profile;
    }

    public double baselineDailyLitres() {
        return baselineDailyLitres;
    }

    public Duration reportingInterval() {
        return reportingInterval;
    }

    /** The device's registered provisioning block, secrets still encrypted. */
    Map<String, Object> provisioning() {
        return provisioning;
    }
}
