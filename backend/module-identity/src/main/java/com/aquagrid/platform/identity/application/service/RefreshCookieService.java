package com.aquagrid.platform.identity.application.service;

import com.aquagrid.platform.identity.infrastructure.config.IdentityProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Builds and reads the refresh-token cookie.
 *
 * <p>Every attribute is a deliberate control:
 * <ul>
 *   <li><b>{@code HttpOnly}</b> — JavaScript cannot read the cookie, so an XSS payload cannot
 *       exfiltrate the long-lived credential. This is the reason the refresh token is a cookie at
 *       all while the access token is not.</li>
 *   <li><b>{@code Secure}</b> — never sent over plain HTTP. Configurable purely so a local
 *       development server works; a startup check refuses to run a non-development profile without
 *       it.</li>
 *   <li><b>{@code SameSite=Strict}</b> — the browser will not attach the cookie to any cross-site
 *       request. This is what makes disabling CSRF protection on a stateless API safe.</li>
 *   <li><b>{@code Path=/api/v1/auth}</b> — the cookie is transmitted only to the handful of
 *       endpoints that need it, instead of riding along on every telemetry poll and tile request.
 *       Least privilege applied to a credential's blast radius.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RefreshCookieService {

    private final IdentityProperties properties;

    public ResponseCookie build(String token) {
        return baseBuilder(token)
                .maxAge(properties.refreshToken().ttl())
                .build();
    }

    /** An expired, empty cookie with identical attributes — the only reliable way to clear one. */
    public ResponseCookie clear() {
        return baseBuilder("").maxAge(Duration.ZERO).build();
    }

    public Optional<String> read(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> properties.refreshToken().cookieName().equals(cookie.getName()))
                .map(jakarta.servlet.http.Cookie::getValue)
                .filter(StringUtils::hasText)
                .findFirst();
    }

    private ResponseCookie.ResponseCookieBuilder baseBuilder(String value) {
        IdentityProperties.RefreshToken config = properties.refreshToken();
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(config.cookieName(), value)
                .httpOnly(true)
                .secure(config.secure())
                .path(config.path())
                .sameSite(config.sameSite());
        if (StringUtils.hasText(config.domain())) {
            builder.domain(config.domain());
        }
        return builder;
    }
}
