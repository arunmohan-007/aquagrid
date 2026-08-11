package com.aquagrid.platform.iot.receiver.application.resolver;

import com.aquagrid.platform.iot.domain.model.CommunicationProfile;
import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Determines which communication profile a packet is governed by.
 *
 * <p>The profile comes from the <b>device row</b>, not from the transport that delivered the
 * packet, and the distinction is not pedantic. The two genuinely differ in production: ChirpStack
 * delivers LoRaWAN uplinks over an HTTP webhook, so a packet arriving on the HTTP transport may
 * belong to a device whose profile is {@code LORAWAN}. Taking the transport as the profile would
 * point a JSON parser at a base64 LoRaWAN frame and reject every uplink from a working fleet.
 *
 * <p>Where they disagree it is recorded rather than corrected. A device registered as NB-IoT whose
 * traffic arrives over MQTT is either a re-provisioning nobody updated the registry for, or someone
 * else's packet — and both are worth seeing. Refusing on the mismatch would break the first case,
 * which is common and harmless; ignoring it silently would hide the second.
 */
@Slf4j
@Service
public class CommunicationResolver {

    /**
     * @return the device's profile, or the transport's own where the device declares none the
     *         platform recognises. Never null: a packet with no profile has no parser
     */
    public CommunicationProfile resolve(ReceptionContext context) {
        Device device = context.getDevice();
        if (device != null) {
            CommunicationProfile declared = CommunicationProfile.from(device.getTransport());
            if (declared != null) {
                if (!declared.name().equalsIgnoreCase(context.transport())) {
                    // Expected for webhook-delivered LoRaWAN and for broker-fronted fleets. Noted
                    // on the packet log so a genuine mis-registration is visible in the data rather
                    // than only in a log file that has already rotated.
                    context.note("transportProfileMismatch",
                            context.transport() + "->" + declared.name());
                }
                return declared;
            }
            log.debug("Device {} declares unknown transport '{}' — falling back to the delivering transport",
                    device.getId(), device.getTransport());
        }
        CommunicationProfile fromTransport = CommunicationProfile.from(context.transport());
        return fromTransport == null ? CommunicationProfile.ETHERNET : fromTransport;
    }
}
