package com.aquagrid.platform.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * In-process token-bucket rate limiter.
 *
 * <p>A token bucket rather than a fixed window: a fixed window permits a burst of {@code 2 × limit}
 * across a window boundary, which is exactly the pattern a credential-stuffing script produces.
 * The bucket refills continuously, so the long-run rate is bounded regardless of timing.
 *
 * <p>Scope and honesty about it: this limiter is <b>per instance</b>. Behind N replicas the
 * effective limit is N × the configured value. That is an acceptable, deliberate trade for
 * Module 1 — it is defence in depth behind the persistent, cluster-wide account lockout, which is
 * the control that actually stops a distributed attack on one account. Module 31 replaces the
 * backing store with Redis to make the limit cluster-wide, with no change to this API.
 *
 * <p>Buckets expire after two windows of inactivity, so memory is bounded by the number of
 * <em>active</em> keys rather than by the number of keys ever seen.
 */
@Component
public class RateLimiter {

    private final Cache<String, Bucket> buckets;
    private final Clock clock;

    public RateLimiter(Clock clock) {
        this.clock = clock;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(200_000)
                .expireAfterAccess(Duration.ofHours(1))
                .build();
    }

    /**
     * Attempts to consume one token from {@code key}'s bucket.
     *
     * @param key      identity of the caller being limited (e.g. {@code login:ip:203.0.113.9})
     * @param capacity bucket size, i.e. the maximum burst
     * @param window   time to refill an empty bucket completely
     */
    public RateLimitDecision tryConsume(String key, int capacity, Duration window) {
        long nowMillis = clock.millis();
        Bucket bucket = buckets.get(key, unused -> new Bucket(capacity, nowMillis));
        return bucket.tryConsume(capacity, window.toMillis(), nowMillis);
    }

    /** Clears a key's bucket — called after a successful login so a legitimate user is not penalised. */
    public void reset(String key) {
        buckets.invalidate(key);
    }

    private static final class Bucket {

        private double tokens;
        private long lastRefillMillis;

        private Bucket(int capacity, long nowMillis) {
            this.tokens = capacity;
            this.lastRefillMillis = nowMillis;
        }

        private synchronized RateLimitDecision tryConsume(int capacity, long windowMillis,
                                                          long nowMillis) {
            double refillPerMilli = (double) capacity / windowMillis;
            long elapsed = Math.max(0, nowMillis - lastRefillMillis);
            tokens = Math.min(capacity, tokens + elapsed * refillPerMilli);
            lastRefillMillis = nowMillis;

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return RateLimitDecision.allowed((long) tokens);
            }
            long millisUntilToken = (long) Math.ceil((1.0 - tokens) / refillPerMilli);
            return RateLimitDecision.denied(Math.max(1, millisUntilToken / 1000));
        }
    }
}
