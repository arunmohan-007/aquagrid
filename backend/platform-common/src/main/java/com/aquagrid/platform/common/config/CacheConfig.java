package com.aquagrid.platform.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * Caching policy.
 *
 * <p>Caffeine (in-process L1) is used now. Every call site goes through the Spring
 * {@code CacheManager} abstraction and named caches, so introducing Redis as a shared L2 in
 * Module 31 is a configuration change, not a code change.
 *
 * <p>TTLs are short and explicit per cache. A permission cache that outlives a role revocation is
 * a security control that stopped working, so authorisation data is capped at 5 minutes and is
 * evicted eagerly whenever a role or permission is modified.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String USER_PERMISSIONS = "userPermissions";
    public static final String ORGANIZATION_BY_CODE = "organizationByCode";
    public static final String PASSWORD_POLICY = "passwordPolicy";

    @Bean
    @Primary
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .recordStats());
        manager.registerCustomCache(USER_PERMISSIONS, Caffeine.newBuilder()
                .maximumSize(20_000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .recordStats()
                .build());
        manager.registerCustomCache(ORGANIZATION_BY_CODE, Caffeine.newBuilder()
                .maximumSize(5_000)
                .expireAfterWrite(Duration.ofMinutes(15))
                .recordStats()
                .build());
        manager.registerCustomCache(PASSWORD_POLICY, Caffeine.newBuilder()
                .maximumSize(5_000)
                .expireAfterWrite(Duration.ofMinutes(30))
                .recordStats()
                .build());
        return manager;
    }
}
