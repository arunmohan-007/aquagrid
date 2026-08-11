package com.aquagrid.platform.iot.application.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Short-lived in-process dedup of recently-ingested messages.
 *
 * <p>LoRaWAN and NB-IoT both retransmit until acked, and a network hiccup can cause the platform
 * to receive the same uplink twice. The dedup key (deviceId + fCnt, or deviceId + timestamp +
 * metric-hash for transports without a frame counter) is remembered for a short window so the second
 * arrival is recognised as a duplicate and acked without double-counting.
 *
 * <p>This is a v1 in-process cache. At scale (many API replicas) a shared cache (Redis) replaces it;
 * the call sites do not change because everything goes through this class.
 */
@Component
public class DedupCache {

    private final Cache<String, Boolean> seen;

    public DedupCache() {
        this.seen = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();
    }

    public boolean wasRecentlySeen(String key) {
        return seen.getIfPresent(key) != null;
    }

    public void remember(String key) {
        seen.put(key, Boolean.TRUE);
    }
}
