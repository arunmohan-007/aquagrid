package com.aquagrid.platform.config;

import com.aquagrid.platform.iot.infrastructure.config.IotProperties;
import com.aquagrid.platform.iot.receiver.infrastructure.config.ReceiverProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * Production-readiness boot assertions for the concerns Modules 4–6 and 18 introduced.
 *
 * <p>The identity module already asserts its own critical settings (JWT key, Secure cookie, HTTPS
 * base URL). This class covers the rest: the simulator must never run in a non-development profile,
 * the receiver must not accept anonymous traffic without an IP allow-list, and the legacy Phase-5
 * HTTP ingest controller must stay off once the receiver owns the public surface.
 *
 * <p>Like the identity assertions, these fail the boot rather than logging a warning nobody reads.
 * A simulator left enabled in production would generate fictional telemetry indistinguishable from
 * real data — a quiet, persistent data-integrity defect. An open ingest endpoint is quieter still.
 */
@Slf4j
@Configuration
public class ProductionReadinessVerifier {

    public ProductionReadinessVerifier(IotProperties iotProperties,
                                       ReceiverProperties receiverProperties,
                                       Environment environment) {
        boolean development = Arrays.stream(environment.getActiveProfiles()).anyMatch(
                profile -> profile.equals("local") || profile.equals("dev") || profile.equals("test"));

        if (!development && iotProperties.transports().simulator()) {
            throw new IllegalStateException(
                    "Refusing to start: aquagrid.iot.transports.simulator is true outside a "
                            + "development profile. The simulator would generate fictional telemetry "
                            + "indistinguishable from real device data. Set IOT_SIMULATOR_ENABLED=false.");
        }

        boolean legacyHttpIngest = environment.getProperty(
                "aquagrid.iot.legacy-http-ingest.enabled", Boolean.class, false);
        if (!development && legacyHttpIngest) {
            throw new IllegalStateException(
                    "Refusing to start: aquagrid.iot.legacy-http-ingest.enabled is true outside a "
                            + "development profile. POST /api/v1/ingest/http bypasses receiver "
                            + "authentication, rate limits and replay protection. Keep it false and "
                            + "use POST /api/v1/receiver/http.");
        }

        ReceiverProperties.Security security = receiverProperties.security();
        if (!development && !security.requireAuthentication() && security.ipAllowList().isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start: aquagrid.iot.receiver.security.require-authentication is "
                            + "false and no ip-allow-list is configured. That combination accepts "
                            + "anonymous telemetry from the public internet. Set "
                            + "RECEIVER_REQUIRE_AUTH=true, or configure RECEIVER_IP_ALLOW_LIST for a "
                            + "carrier APN / private VPN deployment.");
        }

        if (!development) {
            // In production, every ingest transport should be a deliberate, reviewed choice. We do
            // not refuse to start if none are enabled (a tenant running Module 1 only has no devices),
            // but we log exactly which transports are live so an unintended one is visible.
            var t = iotProperties.transports();
            long gatewayCredentials = security.gateways().stream()
                    .filter(g -> g.apiKeySha256() != null && !g.apiKeySha256().isBlank())
                    .count();
            log.info("Production ingest transports: http={} lorawan={} nbiot={} mqtt={} "
                            + "receiverAuth={} gatewayCredentials={} ipAllowList={}",
                    t.http().enabled(), t.lorawan().enabled(), t.nbiot().enabled(), t.mqtt().enabled(),
                    security.requireAuthentication() ? "required" : "network-trust",
                    gatewayCredentials,
                    security.ipAllowList().isEmpty() ? "none" : security.ipAllowList());

            if (t.http().enabled() && security.requireAuthentication() && gatewayCredentials == 0) {
                // Not a boot failure: per-device tokens and HMAC still authenticate. Operators who
                // expect a shared gateway key need the log line that says none is configured.
                log.warn("HTTP receiver is enabled with authentication required, but no gateway "
                        + "API-key hashes are configured. Devices must present a device token or "
                        + "HMAC signature, or set RECEIVER_GATEWAY_API_KEY_SHA256.");
            }
        }
    }
}
