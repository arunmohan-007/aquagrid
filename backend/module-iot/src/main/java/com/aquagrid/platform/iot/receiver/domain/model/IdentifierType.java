package com.aquagrid.platform.iot.receiver.domain.model;

/**
 * The kinds of identifier a packet can carry to say which device sent it.
 *
 * <p>Different technologies address a device by different things, and the receiver must not care
 * which. A transport declares whatever identifiers it can see — a LoRaWAN uplink knows a DevEUI and
 * a JoinEUI, an MQTT publish knows a client id and a topic, an HTTP POST may only have an API key —
 * and the {@code DeviceResolver} tries its strategies in priority order until one matches.
 *
 * <p>This is a vocabulary, not a dispatch table: no code switches on it. Strategies advertise which
 * types they can consume, and the resolver indexes them by that declaration. A technology whose
 * identifier is not listed here uses {@link #CUSTOM}, whose value is namespaced by the transport
 * that supplied it — so a new technology needs no change to this enum to be resolvable.
 */
public enum IdentifierType {

    /** LoRaWAN device EUI-64. The DevEUI is the LoRaWAN device's permanent identity. */
    DEV_EUI,

    /** LoRaWAN join EUI (formerly AppEUI). Identifies the join server, not the device. */
    JOIN_EUI,

    /** LoRaWAN application EUI, where a network server still reports the pre-1.1 name. */
    APPLICATION_EUI,

    /** Cellular equipment identity — the address for NB-IoT, LTE-M and 4G devices. */
    IMEI,

    /** Manufacturer serial number, printed on the device and recorded at registration. */
    SERIAL_NUMBER,

    /** Ethernet/Wi-Fi hardware address, for wired and gateway-attached devices. */
    MAC_ADDRESS,

    /** The client identifier a device presents when it connects to the MQTT broker. */
    MQTT_CLIENT_ID,

    /**
     * The topic a message was published on. Resolves a device where one broker connection carries a
     * whole site — the usual SCADA bridge arrangement, where the client id names the bridge and only
     * the topic names the meter.
     */
    MQTT_TOPIC,

    /** A shared secret issued to an integration, identifying the sender but rarely the device. */
    API_KEY,

    /** A bearer token presented by a gateway or an integration. */
    BEARER_TOKEN,

    /** A per-device token issued at provisioning, which both authenticates and identifies. */
    DEVICE_TOKEN,

    /**
     * Whatever the device's own technology addresses it by, already normalised — the value stored
     * in {@code Device.networkAddress}. The fast path: one indexed lookup, no interpretation.
     */
    NETWORK_ADDRESS,

    /** The device's own operator-facing code, when a payload carries it directly. */
    DEVICE_CODE,

    /**
     * Anything else, supplied by a transport the platform does not model natively. The value is
     * namespaced by its owner ({@code modbus:unit:17}) and matched against the device's
     * {@code provisioning} block, so a new technology is resolvable without touching this enum.
     */
    CUSTOM
}
