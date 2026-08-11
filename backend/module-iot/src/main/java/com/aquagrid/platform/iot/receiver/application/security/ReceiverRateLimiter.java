package com.aquagrid.platform.iot.receiver.application.security;

import com.aquagrid.platform.iot.receiver.infrastructure.config.ReceiverProperties;
import com.aquagrid.platform.security.ratelimit.RateLimitDecision;
import com.aquagrid.platform.security.ratelimit.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Bounds how fast one device, or one source, can feed the receiver.
 *
 * <p>Delegates to the platform's token-bucket {@link RateLimiter} rather than implementing another
 * one. The reasoning it embodies applies unchanged here: a fixed window admits a burst of twice the
 * limit across a boundary, which is the exact shape of a replay flood.
 *
 * <p>Two limits, because they stop different things. The per-device limit catches a meter that has
 * gone haywire or is being impersonated — a device on a fifteen-minute schedule sending thousands
 * of packets a minute is faulty or forged, and either way the platform should not ingest it without
 * bound. The per-source limit catches a gateway or a script hammering the endpoint, and is set far
 * higher because one legitimate gateway fronts thousands of meters.
 *
 * <p><b>Per instance, and honestly so.</b> Behind N replicas the effective limit is N times the
 * configured value — the same trade the platform already accepts for login limiting, and acceptable
 * for the same reason: this is a shock absorber, not the control that decides who may write. That
 * control is authentication and device resolution. Module 31's Redis backing makes it cluster-wide
 * with no change to this class.
 */
@Service
@RequiredArgsConstructor
public class ReceiverRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final RateLimiter rateLimiter;
    private final ReceiverProperties properties;

    /**
     * Checked <em>after</em> the device is resolved, so the limit follows the device rather than
     * whatever address it happens to arrive from — a meter that roams between two gateways is one
     * device, and limiting it per source would give it double its allowance.
     */
    public RateLimitDecision checkDevice(UUID deviceId) {
        return rateLimiter.tryConsume("receiver:device:" + deviceId,
                properties.limits().packetsPerMinutePerDevice(), WINDOW);
    }

    /**
     * Checked before anything touches the database, so a flood costs a map lookup rather than a
     * connection from the pool. That ordering is the point of having a source limit at all.
     */
    public RateLimitDecision checkSource(String sourceIp) {
        String key = sourceIp == null || sourceIp.isBlank() ? "unknown" : sourceIp;
        return rateLimiter.tryConsume("receiver:src:" + key,
                properties.limits().packetsPerMinutePerSource(), WINDOW);
    }
}
