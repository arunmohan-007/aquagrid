package com.aquagrid.platform.iot.domain.model;

/**
 * How a device's telemetry reaches the platform — the application/ingress protocol.
 *
 * <p>Deliberately not a {@link CommunicationProfile}. A communication profile names the
 * <em>network</em> the device sits on (LoRaWAN, NB-IoT, Ethernet, …) and declares what must be
 * provisioned to address it there. This names the bearer the packet actually travels on when it
 * arrives here. They vary independently: ChirpStack delivers LoRaWAN uplinks over HTTP, and a
 * cellular modem may push JSON over MQTT just as easily as over HTTP.
 *
 * <p>HTTP used to live on {@code CommunicationProfile} beside the radios, which collapsed those two
 * questions into one dropdown and made it impossible to say "this LoRaWAN meter arrives over HTTP"
 * without lying about the network. It is here now, next to MQTT, which is the other ingress the
 * platform terminates today.
 */
public enum DeviceProtocol {

    /** REST / webhook push — Postman, carrier callbacks, ChirpStack, direct device HTTP. */
    HTTP,

    /** Publish/subscribe over an MQTT broker. */
    MQTT;

    /** Resolves a protocol string from the API or the database; null when it names no protocol. */
    public static DeviceProtocol from(String value) {
        if (value == null) {
            return null;
        }
        for (DeviceProtocol protocol : values()) {
            if (protocol.name().equalsIgnoreCase(value.trim())) {
                return protocol;
            }
        }
        return null;
    }
}
