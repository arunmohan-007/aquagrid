package com.aquagrid.platform.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * HTTP-edge configuration.
 *
 * @param trustedProxies CIDR blocks of reverse proxies whose {@code X-Forwarded-For} may be
 *                       believed. <b>Empty by default</b>, which makes the platform secure when
 *                       deployed directly. Populating this with {@code 0.0.0.0/0} would restore
 *                       the header-spoofing vulnerability this design exists to prevent, so it is
 *                       set to the actual load balancer subnet and nothing wider.
 * @param corsOrigins    exact allowed origins. Wildcards are rejected: the API is used with
 *                       credentials, where a wildcard origin is both forbidden by the CORS spec
 *                       and dangerous.
 */
@Validated
@ConfigurationProperties(prefix = "aquagrid.web")
public record WebProperties(
        List<String> trustedProxies,
        List<String> corsOrigins,
        List<String> corsExposedHeaders
) {
    public WebProperties {
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
        corsOrigins = corsOrigins == null ? List.of() : List.copyOf(corsOrigins);
        corsExposedHeaders = corsExposedHeaders == null
                ? List.of("X-Request-Id")
                : List.copyOf(corsExposedHeaders);

        if (corsOrigins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalArgumentException(
                    "aquagrid.web.cors-origins must list exact origins; wildcards are not permitted "
                            + "because the API is used with credentials");
        }
    }
}
