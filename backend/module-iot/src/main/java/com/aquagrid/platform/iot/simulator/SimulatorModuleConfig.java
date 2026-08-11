package com.aquagrid.platform.iot.simulator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Simulator wiring.
 *
 * <p>Conditional on the same flag every other bean in the package is, so a deployment with the
 * simulator off does not even bind its configuration. That is not tidiness: {@link
 * SimulatorProperties} would otherwise be a live, settable block in a production context whose
 * values do nothing, and a property that appears to be honoured but is not is worse than one that
 * does not exist.
 */
@Configuration
@EnableConfigurationProperties(SimulatorProperties.class)
@ConditionalOnProperty(prefix = "aquagrid.iot.transports", name = "simulator", havingValue = "true")
public class SimulatorModuleConfig {
}
