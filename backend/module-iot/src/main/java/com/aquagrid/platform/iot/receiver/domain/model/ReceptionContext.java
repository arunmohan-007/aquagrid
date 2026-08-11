package com.aquagrid.platform.iot.receiver.domain.model;

import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.iot.domain.model.CommunicationProfile;
import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.receiver.api.TelemetryEvent;
import lombok.Getter;

import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The mutable state of one packet's journey through the pipeline.
 *
 * <p>Each stage reads what earlier stages established and contributes its own result. Passing this
 * one object down the chain, rather than threading a widening tuple through eight signatures, is
 * what lets a stage be inserted or reordered without touching its neighbours — which is the whole
 * point of building the pipeline as a chain of responsibility.
 *
 * <p><b>Confined to one thread.</b> A context belongs to the reception that created it and is never
 * shared: the receiver is thread-safe because contexts are not, not because they are synchronised.
 * Concurrency lives one level up, where each packet gets its own context on its own virtual thread.
 *
 * <p>Once {@link #reject} is called the chain halts. Later stages are not run, so no stage needs to
 * defend against a half-populated context — if a stage runs at all, everything it declares a
 * dependency on is present.
 */
@Getter
public final class ReceptionContext {

    private final InboundPacket packet;
    private final long startedAtNanos = System.nanoTime();

    /** Per-stage elapsed time, for the processing-time histogram and slow-stage diagnosis. */
    private final Map<String, Long> stageNanos = new LinkedHashMap<>();

    /**
     * Identifiers discovered after the packet was built — an authenticator that recognises a device
     * token, or a parser that finds a serial number in the body. Kept apart from the packet's own
     * claims so the log can distinguish what arrived from what the receiver worked out.
     */
    private final Map<IdentifierType, String> derivedIdentifiers = new EnumMap<>(IdentifierType.class);

    /** Free-form notes stages leave for the packet log and the telemetry event's metadata. */
    private final Map<String, Object> notes = new LinkedHashMap<>();

    private String authenticatedPrincipal;
    private String authenticationScheme;
    private UUID tenantId;
    private Device device;
    private CommunicationProfile profile;
    private ParsedTelemetry telemetry;
    private TelemetryEvent event;
    private Rejection rejection;
    private boolean duplicate;

    public ReceptionContext(InboundPacket packet) {
        this.packet = packet;
    }

    // ---- Stage results ---------------------------------------------------------------------

    public void authenticatedAs(String principal, String scheme) {
        this.authenticatedPrincipal = principal;
        this.authenticationScheme = scheme;
    }

    public void resolvedTenant(UUID tenantId) {
        this.tenantId = tenantId;
    }

    /** Binds the device and, with it, the tenant. The tenant always comes from the row, never the wire. */
    public void resolvedDevice(Device device) {
        this.device = device;
        this.tenantId = device.getOrganizationId();
    }

    public void resolvedProfile(CommunicationProfile profile) {
        this.profile = profile;
    }

    public void parsed(ParsedTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    public void built(TelemetryEvent event) {
        this.event = event;
    }

    /** Marks the packet as already ingested. Terminal, but a success as far as the wire is concerned. */
    public void markDuplicate() {
        this.duplicate = true;
    }

    /**
     * Refuses the packet. The first rejection wins: a stage that fails may still be asked to clean
     * up, and a second, vaguer reason overwriting the first is how the real cause gets lost.
     */
    public void reject(ErrorCode code, String detail) {
        if (this.rejection == null) {
            this.rejection = Rejection.of(code, detail);
        }
    }

    public void reject(Rejection rejection) {
        if (this.rejection == null) {
            this.rejection = rejection;
        }
    }

    public boolean isRejected() {
        return rejection != null;
    }

    /** True once no further stage should run — refused, or already known. */
    public boolean isTerminal() {
        return rejection != null || duplicate;
    }

    // ---- Accessors used by stages -----------------------------------------------------------

    /**
     * The device, for callers that must cope with it being absent.
     *
     * <p>Named distinctly from Lombok's {@code getDevice()} rather than replacing it. Stages that
     * run after {@code DeviceResolutionStage} are guaranteed a device — the chain halted otherwise —
     * and forcing them to unwrap an Optional would suggest a doubt the pipeline has already
     * removed. The bookkeeping that runs for <em>every</em> packet, including the ones refused
     * before resolution, genuinely does need the Optional.
     */
    public Optional<Device> deviceIfResolved() {
        return Optional.ofNullable(device);
    }

    public UUID packetId() {
        return packet.packetId();
    }

    public String transport() {
        return packet.transport();
    }

    public String correlationId() {
        return packet.correlationId();
    }

    /**
     * An identifier claim, from the packet or derived downstream.
     *
     * <p>Derived values win. When a device token both authenticates a packet and names its device,
     * that binding was proved; a value that merely arrived in the body was asserted.
     */
    public String identifier(IdentifierType type) {
        String derived = derivedIdentifiers.get(type);
        return derived != null ? derived : packet.identifier(type);
    }

    public void deriveIdentifier(IdentifierType type, String value) {
        if (value != null && !value.isBlank()) {
            derivedIdentifiers.put(type, value.trim());
        }
    }

    public void note(String key, Object value) {
        if (value != null) {
            notes.put(key, value);
        }
    }

    public void recordStage(String stage, long nanos) {
        stageNanos.merge(stage, nanos, Long::sum);
    }

    public Duration elapsed() {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }

    public long elapsedMillis() {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
