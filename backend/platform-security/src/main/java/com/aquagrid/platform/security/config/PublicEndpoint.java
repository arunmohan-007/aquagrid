package com.aquagrid.platform.security.config;

import org.springframework.http.HttpMethod;

/**
 * A single endpoint that may be reached without authentication.
 *
 * @param method  restrict to one HTTP method, or {@code null} for all methods
 * @param pattern Ant-style path pattern
 */
public record PublicEndpoint(HttpMethod method, String pattern) {

    public static PublicEndpoint any(String pattern) {
        return new PublicEndpoint(null, pattern);
    }

    public static PublicEndpoint post(String pattern) {
        return new PublicEndpoint(HttpMethod.POST, pattern);
    }

    public static PublicEndpoint get(String pattern) {
        return new PublicEndpoint(HttpMethod.GET, pattern);
    }
}
