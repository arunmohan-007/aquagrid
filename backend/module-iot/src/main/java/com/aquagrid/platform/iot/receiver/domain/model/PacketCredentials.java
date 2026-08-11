package com.aquagrid.platform.iot.receiver.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Whatever a packet presented to prove who sent it.
 *
 * <p>An open map rather than a fixed set of fields, and that is a deliberate trade. A typed record
 * would have to grow a column for every scheme the platform ever supports — an OPC-UA certificate
 * thumbprint, an Azure IoT Hub SAS token — and every such addition would be a change to a class
 * that every transport and every authenticator already depends on. Keyed values let an
 * authenticator ship with its own key constants and nothing else in the module change.
 *
 * <p>Values are secrets. This class deliberately has no {@code toString} that reveals them, because
 * a packet is logged on the rejection path and a default record {@code toString} would put device
 * tokens in the log file. {@link #describe()} is what logging uses: which credentials were present,
 * never what they were.
 */
public record PacketCredentials(Map<String, String> values) {

    /** Credential keys. Authenticators contribute their own; these cover the built-in schemes. */
    public static final class Keys {
        /** Shared secret identifying an integration or a gateway. */
        public static final String API_KEY = "apiKey";
        /** JWT presented by a gateway or an internal service. */
        public static final String BEARER_TOKEN = "bearerToken";
        /** Per-device secret issued at provisioning. */
        public static final String DEVICE_TOKEN = "deviceToken";
        /** Payload signature, hex or base64 per the algorithm. */
        public static final String SIGNATURE = "signature";
        /** Algorithm the signature was produced with, e.g. {@code HmacSHA256}. */
        public static final String SIGNATURE_ALGORITHM = "signatureAlgorithm";
        /** Single-use value binding a signature to one packet, defeating capture-and-resend. */
        public static final String NONCE = "nonce";
        /** Epoch-second timestamp the signature covers, bounding how long a capture stays useful. */
        public static final String TIMESTAMP = "timestamp";
        /** Broker or gateway username. */
        public static final String USERNAME = "username";
        /** LoRaWAN message integrity code, verified by the network server before it reaches us. */
        public static final String MIC = "mic";

        private Keys() {
        }
    }

    private static final PacketCredentials NONE = new PacketCredentials(Map.of());

    public PacketCredentials {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    /** No credentials presented. Not the same as "authentication not required". */
    public static PacketCredentials none() {
        return NONE;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String get(String key) {
        return values.get(key);
    }

    public boolean has(String key) {
        String value = values.get(key);
        return value != null && !value.isBlank();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** The credential <em>names</em> presented, for logs and audit. Never the values. */
    public String describe() {
        return values.isEmpty() ? "none" : String.join(",", values.keySet());
    }

    @Override
    public String toString() {
        return "PacketCredentials[" + describe() + "]";
    }

    /** Accumulates credentials as a transport discovers them across headers, topic and payload. */
    public static final class Builder {
        private final Map<String, String> values = new LinkedHashMap<>();

        /** Ignores null and blank values, so a transport can offer every header it might have. */
        public Builder with(String key, String value) {
            if (value != null && !value.isBlank()) {
                values.put(key, value.trim());
            }
            return this;
        }

        public PacketCredentials build() {
            return new PacketCredentials(values);
        }
    }
}
