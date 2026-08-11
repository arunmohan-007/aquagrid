package com.aquagrid.platform.config;

import com.aquagrid.platform.iot.infrastructure.config.IotProperties;
import com.aquagrid.platform.iot.receiver.infrastructure.config.ReceiverProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boot-time production assertions for the HTTP receiver surface.
 *
 * <p>These are the configurations that would otherwise start cleanly and then accept anonymous
 * telemetry or fictional simulator traffic. Each failure mode is a {@code IllegalStateException}
 * rather than a warning, matching the rest of the platform's production readiness checks.
 */
class ProductionReadinessVerifierTest {

    @Test
    @DisplayName("production refuses an open receiver with no IP allow-list")
    void refusesOpenReceiverInProduction() {
        assertThatThrownBy(() -> new ProductionReadinessVerifier(
                transports(false),
                receiver(false, List.of(), List.of()),
                productionEnv()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("require-authentication");
    }

    @Test
    @DisplayName("production refuses the legacy /ingest/http controller")
    void refusesLegacyHttpIngestInProduction() {
        MockEnvironment env = productionEnv();
        env.setProperty("aquagrid.iot.legacy-http-ingest.enabled", "true");

        assertThatThrownBy(() -> new ProductionReadinessVerifier(
                transports(false),
                receiver(true, List.of(), List.of()),
                env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("legacy-http-ingest");
    }

    @Test
    @DisplayName("production refuses the simulator")
    void refusesSimulatorInProduction() {
        assertThatThrownBy(() -> new ProductionReadinessVerifier(
                transports(true),
                receiver(true, List.of(), List.of()),
                productionEnv()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulator");
    }

    @Test
    @DisplayName("production starts when authentication is required")
    void acceptsHardenedProductionConfig() {
        assertThatCode(() -> new ProductionReadinessVerifier(
                transports(false),
                receiver(true, List.of(
                        new ReceiverProperties.GatewayCredential(
                                "chirpstack", "aabb", List.of("LORAWAN"))),
                        List.of()),
                productionEnv()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("network-trust is allowed only with an IP allow-list")
    void acceptsNetworkTrustWithAllowList() {
        assertThatCode(() -> new ProductionReadinessVerifier(
                transports(false),
                receiver(false, List.of(), List.of("10.0.0.0/8")),
                productionEnv()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("development profiles may disable authentication")
    void allowsOpenReceiverInDevelopment() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");

        assertThatCode(() -> new ProductionReadinessVerifier(
                transports(true),
                receiver(false, List.of(), List.of()),
                env))
                .doesNotThrowAnyException();
    }

    private static MockEnvironment productionEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("docker");
        return env;
    }

    private static IotProperties transports(boolean simulator) {
        return new IotProperties(new IotProperties.Transports(
                new IotProperties.Transport(true, null, null),
                IotProperties.Transport.disabled(),
                IotProperties.Transport.disabled(),
                IotProperties.Transport.disabled(),
                IotProperties.Transport.disabled(),
                simulator));
    }

    private static ReceiverProperties receiver(boolean requireAuth,
                                               List<ReceiverProperties.GatewayCredential> gateways,
                                               List<String> ipAllowList) {
        return new ReceiverProperties(
                true,
                new ReceiverProperties.Security(
                        requireAuth, ipAllowList, Duration.ofHours(24),
                        Duration.ofDays(7), Duration.ofMinutes(5), List.of(), gateways),
                null, null, null, null, null);
    }
}
