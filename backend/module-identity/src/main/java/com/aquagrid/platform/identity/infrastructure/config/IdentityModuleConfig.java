package com.aquagrid.platform.identity.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * Identity module wiring, plus the startup assertions that stop an unsafe configuration from
 * reaching production.
 *
 * <p>These checks fail the application at boot rather than logging a warning nobody reads. A
 * refresh cookie without {@code Secure} on a public deployment is a session-hijacking vulnerability
 * that produces no symptoms until it is exploited — exactly the class of defect that must be caught
 * by the process, not by an incident.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(IdentityProperties.class)
public class IdentityModuleConfig {

    public IdentityModuleConfig(IdentityProperties properties, Environment environment) {
        boolean development = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equals("local") || profile.equals("dev")
                        || profile.equals("test"));

        if (!development && !properties.refreshToken().secure()) {
            throw new IllegalStateException(
                    "Refusing to start: aquagrid.identity.refresh-token.secure is false outside a "
                            + "development profile. The refresh cookie would be transmitted over "
                            + "plain HTTP.");
        }
        if (!development && !"Strict".equalsIgnoreCase(properties.refreshToken().sameSite())
                && !"Lax".equalsIgnoreCase(properties.refreshToken().sameSite())) {
            throw new IllegalStateException(
                    "Refusing to start: aquagrid.identity.refresh-token.same-site must be Strict or "
                            + "Lax. SameSite=None removes the CSRF protection this design relies on.");
        }
        if (!properties.appBaseUrl().startsWith("https://") && !development) {
            throw new IllegalStateException(
                    "Refusing to start: aquagrid.identity.app-base-url must use HTTPS — password "
                            + "reset links are built from it.");
        }

        log.info("Identity module configured: lockout after {} attempts for {}, "
                        + "refresh TTL {}, max {} sessions/user, MFA issuer '{}'",
                properties.lockout().maxFailedAttempts(), properties.lockout().duration(),
                properties.refreshToken().ttl(), properties.refreshToken().maxSessionsPerUser(),
                properties.mfa().issuer());
    }
}
