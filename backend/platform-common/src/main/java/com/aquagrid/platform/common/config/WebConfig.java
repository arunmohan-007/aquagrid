package com.aquagrid.platform.common.config;

import com.aquagrid.platform.common.crypto.CryptoProperties;
import com.aquagrid.platform.common.web.ClientIpResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;

/**
 * Kernel web wiring.
 *
 * <p>{@link Clock} is exposed as a bean rather than calling {@code Instant.now()} inline, so that
 * time-dependent logic — token expiry, lockout windows, TOTP steps — is deterministically testable.
 */
@Configuration
@EnableConfigurationProperties({WebProperties.class, CryptoProperties.class})
@EnableSpringDataWebSupport(pageSerializationMode =
        EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class WebConfig implements WebMvcConfigurer {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    public ClientIpResolver clientIpResolver(WebProperties properties) {
        return new ClientIpResolver(properties.trustedProxies());
    }
}
