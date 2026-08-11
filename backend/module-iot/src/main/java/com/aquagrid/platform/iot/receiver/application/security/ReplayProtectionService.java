package com.aquagrid.platform.iot.receiver.application.security;

import com.aquagrid.platform.iot.application.service.DedupCache;
import com.aquagrid.platform.iot.receiver.domain.model.InboundPacket;
import com.aquagrid.platform.iot.receiver.domain.model.PacketCredentials;
import com.aquagrid.platform.iot.receiver.domain.model.ParsedTelemetry;
import com.aquagrid.platform.iot.receiver.infrastructure.config.ReceiverProperties;
import com.aquagrid.platform.iot.receiver.infrastructure.persistence.ReplayCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Decides whether a packet has been seen before.
 *
 * <p>Two questions wear the same clothes here and are worth separating, because the platform needs
 * both and they fail differently:
 *
 * <ul>
 *   <li><b>Deduplication</b> is a correctness concern. LoRaWAN and NB-IoT retransmit until
 *       acknowledged, so the same uplink legitimately arrives twice. Missing one costs a
 *       double-counted reading on a billing meter.</li>
 *   <li><b>Replay protection</b> is a security control. An attacker who captures one signed packet
 *       can re-assert it at will — on a consumption meter, indefinitely, which is a billing fraud
 *       primitive. Missing one costs the property that a signature means anything at all.</li>
 * </ul>
 *
 * <p>They are answered by one lookup but backed by two stores, and that is the design. The
 * in-process {@link DedupCache} absorbs the common case at no I/O cost. It is not sufficient on its
 * own for the security half: it is per-instance, so a captured packet replayed at the other replica
 * is accepted, and it is in memory, so a rolling restart re-opens the window. So a cache miss falls
 * through to the durable, cluster-wide table, and correctness never depends on a cache.
 *
 * <p>The durable check is an insert, not a read. {@code claim} lets the unique index adjudicate:
 * exactly one caller can win, so there is no window between "have I seen this?" and "remember it"
 * for a fast double-delivery to slip through.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplayProtectionService {

    private final DedupCache dedupCache;
    private final ReplayCacheRepository replayCacheRepository;
    private final ReceiverProperties properties;

    /**
     * Claims this packet's identity for the device.
     *
     * <p>Runs in its own transaction. The claim must survive the outcome of the reception that made
     * it: if a later stage fails and rolls back, the packet identity has still been observed, and
     * releasing it would hand an attacker a retry. {@code REQUIRES_NEW} is what makes the claim
     * independent of the caller's transaction.
     *
     * @return true when the packet is new and may proceed; false when it is a repeat
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(UUID deviceId, InboundPacket packet, ParsedTelemetry telemetry) {
        String key = replayKey(packet, telemetry);
        String cacheKey = deviceId + ":" + key;

        if (dedupCache.wasRecentlySeen(cacheKey)) {
            return false;
        }

        Instant now = Instant.now();
        int inserted = replayCacheRepository.claim(deviceId, key, packet.transport(),
                now, now.plus(properties.security().replayWindow()));

        // Remember either way. A durable hit means some other replica saw it; caching that fact
        // stops this instance paying for the same round trip on the next retransmission.
        dedupCache.remember(cacheKey);
        return inserted > 0;
    }

    /**
     * What makes this packet <em>this</em> packet, in descending order of strength.
     *
     * <p>The ordering matters because a weak key is not merely less precise, it is wrong in a
     * specific direction: a content hash treats two genuinely distinct readings that happen to be
     * identical — a meter reporting zero flow twice in a quiet hour, which is the normal case
     * overnight — as a duplicate, and silently discards the second. So a nonce or a frame counter
     * is used whenever the transport offers one, and the hash is the last resort, narrowed by the
     * device's own timestamp so that identical readings at different times stay distinct.
     */
    private String replayKey(InboundPacket packet, ParsedTelemetry telemetry) {
        // 1. An explicit nonce. Purpose-built, single-use, and part of what a signature covers.
        String nonce = packet.credentials().get(PacketCredentials.Keys.NONCE);
        if (nonce != null && !nonce.isBlank()) {
            return "n:" + nonce;
        }
        // 2. A frame counter. Monotonic per device, which is exactly what is wanted.
        if (telemetry != null && telemetry.frameCounter() != null) {
            return "f:" + telemetry.frameCounter();
        }
        // 3. Device timestamp plus a payload digest. The timestamp is what keeps two identical
        //    quiet-hour readings apart; the digest is what catches a genuine retransmission.
        String observedAt = telemetry != null && telemetry.observedAt() != null
                ? String.valueOf(telemetry.observedAt().getEpochSecond())
                : String.valueOf(packet.receivedAt().getEpochSecond());
        return "h:" + observedAt + ":" + sha256(packet.payload());
    }

    /**
     * Truncated to 16 bytes, which is ample here: the key is already scoped to one device inside
     * one replay window, so the population a collision would have to occur within is tiny, and the
     * column is an index key whose width is paid for on every insert.
     *
     * <p>Deliberately <em>not</em> exposed for credential hashing — a truncated digest does not
     * match what {@code sha256sum} produces, and a configured API key hashed this way silently
     * fails to authenticate. {@link Sha256#hex} is the one to use there.
     */
    private static String sha256(byte[] payload) {
        return Sha256.truncatedHex(payload);
    }
}
