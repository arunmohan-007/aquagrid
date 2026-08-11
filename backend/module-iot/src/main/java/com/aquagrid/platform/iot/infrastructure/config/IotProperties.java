package com.aquagrid.platform.iot.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Transport activation and codec configuration.
 *
 * <p>Adapters are switched on per-transport via these properties, so a municipality running only
 * NB-IoT never loads an MQTT client and never starts a broker connection. This is the concrete
 * realisation of the communication-independence principle at the deployment level.
 */
@Validated
@ConfigurationProperties(prefix = "aquagrid.iot")
public record IotProperties(
        Transports transports
) {
    public record Transports(
            Transport http,
            Transport lorawan,
            Transport nbiot,
            Transport cellular,
            Transport mqtt,
            boolean simulator
    ) {
        public Transports {
            http = http == null ? Transport.disabled() : http;
            lorawan = lorawan == null ? Transport.disabled() : lorawan;
            nbiot = nbiot == null ? Transport.disabled() : nbiot;
            cellular = cellular == null ? Transport.disabled() : cellular;
            mqtt = mqtt == null ? Transport.disabled() : mqtt;
        }
    }

    public record Transport(boolean enabled, String endpoint, Duration pollInterval) {
        public static Transport disabled() {
            return new Transport(false, null, null);
        }
    }
}
