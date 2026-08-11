package com.aquagrid.platform.iot.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * IoT module wiring.
 *
 * <p>Enables {@link IotProperties} so transport activation is typed and validated. Each adapter
 * reads its own {@code enabled} flag and contributes itself to the context only when active — an
 * adapter that is not enabled has no beans, no listeners, no open sockets.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(IotProperties.class)
public class IotModuleConfig {

    public IotModuleConfig(IotProperties properties) {
        var t = properties.transports();
        log.info("IoT module configured: http={} lorawan={} nbiot={} cellular={} mqtt={} simulator={}",
                t.http().enabled(), t.lorawan().enabled(), t.nbiot().enabled(),
                t.cellular().enabled(), t.mqtt().enabled(), t.simulator());
    }
}
