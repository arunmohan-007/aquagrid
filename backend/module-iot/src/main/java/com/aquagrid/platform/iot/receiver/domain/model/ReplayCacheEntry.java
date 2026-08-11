package com.aquagrid.platform.iot.receiver.domain.model;

import com.aquagrid.platform.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A packet identity the receiver has already seen, held long enough to recognise its return.
 *
 * <p>The durable counterpart to the in-process {@code DedupCache}, and it exists because that cache
 * has two gaps that matter to a security control rather than to a performance optimisation. It is
 * per-instance, so behind two replicas a captured packet replayed at the other one is accepted; and
 * it is in memory, so a rolling restart re-opens the window on every packet still within it. Losing
 * a dedup hit costs a double-counted reading. Losing a <em>replay</em> check costs the property
 * that an attacker who records one signed packet cannot re-assert it at will — on a billing meter,
 * indefinitely.
 *
 * <p>The two are used together, not instead of each other: the in-process cache absorbs the common
 * case at no I/O cost, and this table is consulted on a miss. That keeps the hot path fast without
 * making correctness depend on a cache.
 *
 * <p>Rows expire. {@code expiresAt} is enforced by a scheduled sweep rather than a TTL index
 * because PostgreSQL has none, and the sweep is what bounds the table: a fleet of 100k meters at
 * one uplink a minute would otherwise write 144M rows a day.
 */
@Getter
@Setter
@Entity
@Table(name = "receiver_replay_cache", schema = "iot")
public class ReplayCacheEntry extends BaseEntity {

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    /**
     * What makes this packet the packet: a nonce, a frame counter, or a hash of the payload for
     * transports offering neither. Scoped to the device, never global — two meters legitimately
     * emit frame counter 1, and a global namespace would reject the second one.
     */
    @Column(name = "replay_key", nullable = false, length = 200)
    private String replayKey;

    @Column(name = "transport", length = 20)
    private String transport;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
