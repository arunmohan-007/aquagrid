package com.aquagrid.platform.iot.receiver.infrastructure.config;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.iot.receiver.api.ReceiverGateway;
import com.aquagrid.platform.iot.receiver.infrastructure.transport.HttpTransportReceiver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Receiver wiring.
 *
 * <p>The HTTP-family transports are declared here as separately-conditional beans of one class,
 * rather than as four annotated classes. That keeps the property-per-transport activation the
 * platform already uses — a municipality running only NB-IoT loads no LoRaWAN route — without four
 * files whose only difference is a string. A transport that is not enabled contributes no bean, so
 * it has no entry in the registry, no route on the ingress controller and no status to report.
 *
 * <p>{@code ReceiverPublicEndpoints} is what actually exposes those routes; a bean here without the
 * matching public endpoint would be a listener nothing can reach.
 */
@Slf4j
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ReceiverProperties.class)
public class ReceiverModuleConfig {

    /** Base path every ingress route hangs off. One constant, so security and routing cannot drift. */
    public static final String INGRESS_BASE = ApiPaths.API_V1 + "/receiver";

    public ReceiverModuleConfig(ReceiverProperties properties) {
        // Logged at boot because these are the settings whose consequences are invisible at runtime
        // until something is wrong: an open ingestion endpoint accepts everything quietly, and an
        // over-long replay window looks exactly like a working one.
        ReceiverProperties.Security security = properties.security();
        log.info("Receiver configured: authentication={}, ipAllowList={}, replayWindow={}, "
                        + "signatureRequired={}",
                security.requireAuthentication() ? "required" : "OPTIONAL",
                security.ipAllowList().isEmpty() ? "none" : security.ipAllowList(),
                security.replayWindow(), security.signatureRequired());

        if (!security.requireAuthentication() && security.ipAllowList().isEmpty()) {
            log.warn("Receiver is accepting UNAUTHENTICATED packets from ANY source. "
                    + "Set aquagrid.iot.receiver.security.require-authentication=true, or configure "
                    + "an ip-allow-list, before this reaches production.");
        }
    }

    @Bean
    @ConditionalOnProperty(prefix = "aquagrid.iot.transports.http", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public HttpTransportReceiver httpTransportReceiver(ReceiverGateway gateway) {
        return new HttpTransportReceiver("HTTP", "HTTP (direct device push)",
                INGRESS_BASE + "/http", gateway);
    }

    @Bean
    @ConditionalOnProperty(prefix = "aquagrid.iot.transports.lorawan", name = "enabled",
            havingValue = "true")
    public HttpTransportReceiver lorawanTransportReceiver(ReceiverGateway gateway) {
        return new HttpTransportReceiver("LORAWAN", "LoRaWAN (network-server integration)",
                INGRESS_BASE + "/lorawan", gateway);
    }

    @Bean
    @ConditionalOnProperty(prefix = "aquagrid.iot.transports.nbiot", name = "enabled",
            havingValue = "true")
    public HttpTransportReceiver nbIotTransportReceiver(ReceiverGateway gateway) {
        return new HttpTransportReceiver("NB_IOT", "NB-IoT / LTE-M (carrier webhook)",
                INGRESS_BASE + "/nbiot", gateway);
    }

    @Bean
    @ConditionalOnProperty(prefix = "aquagrid.iot.transports.cellular", name = "enabled",
            havingValue = "true")
    public HttpTransportReceiver cellularTransportReceiver(ReceiverGateway gateway) {
        return new HttpTransportReceiver("CELLULAR", "Cellular 4G (modem REST push)",
                INGRESS_BASE + "/cellular", gateway);
    }

    /**
     * MQTT's receiver.
     *
     * <p>Registered as an HTTP-family receiver because that is what it honestly is today: the
     * broker subscriber lands with the Paho dependency, and until then the bridge route is how MQTT
     * traffic reaches the platform. The decode path is already the real one — a packet delivered
     * here goes through the same pipeline a broker callback will — so activating the subscriber
     * later changes how bytes arrive and nothing about what happens to them.
     */
    @Bean
    @ConditionalOnProperty(prefix = "aquagrid.iot.transports.mqtt", name = "enabled",
            havingValue = "true")
    public HttpTransportReceiver mqttTransportReceiver(ReceiverGateway gateway) {
        return new HttpTransportReceiver("MQTT", "MQTT (bridge relay)",
                INGRESS_BASE + "/mqtt", gateway);
    }
}
