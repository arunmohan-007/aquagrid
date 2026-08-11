package com.aquagrid.platform.iot.receiver.application.pipeline;

import com.aquagrid.platform.iot.domain.model.CommunicationProfile;
import com.aquagrid.platform.iot.receiver.application.resolver.CommunicationResolver;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.spi.ReceiverStage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Determines which communication profile governs the packet, and therefore which parser can read it.
 *
 * <p>Runs after device resolution. See {@link ReceiverStage.Stages#COMMUNICATION_RESOLUTION} for
 * why that inverts the nominal pipeline order: the profile belongs to the device, and deriving it
 * from the transport instead would mislabel every webhook-delivered LoRaWAN uplink in the estate.
 */
@Component
@RequiredArgsConstructor
public class CommunicationResolutionStage implements ReceiverStage {

    private final CommunicationResolver communicationResolver;

    @Override
    public String name() {
        return "COMMUNICATION_RESOLUTION";
    }

    @Override
    public Decision execute(ReceptionContext context) {
        CommunicationProfile profile = communicationResolver.resolve(context);
        context.resolvedProfile(profile);
        context.note("communicationProfile", profile.name());
        return Decision.CONTINUE;
    }

    @Override
    public int getOrder() {
        return Stages.COMMUNICATION_RESOLUTION;
    }
}
