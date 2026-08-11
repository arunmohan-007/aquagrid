package com.aquagrid.platform.iot.domain.model;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What each communication technology needs to know about a device, and nothing else.
 *
 * <p>The device registry is communication-independent: {@link Device} carries identity, hardware
 * and lifecycle, and none of it changes when a utility switches a district from NB-IoT to LoRaWAN.
 * Everything that <em>is</em> radio-specific is declared here, once, and drives three things that
 * would otherwise drift apart:
 *
 * <ol>
 *   <li>which fields the registration form shows,</li>
 *   <li>which fields the server validates and stores,</li>
 *   <li>which field becomes the device's {@code networkAddress} — the single indexed column every
 *       uplink is resolved through.</li>
 * </ol>
 *
 * <p>Adding a technology is one enum constant. There is no {@code switch} on transport anywhere in
 * the registration path, which is what stops NB-IoT rules from silently applying to LoRaWAN.
 *
 * <p>Every constant here names a <em>network</em>. Whether the traffic on it is real or synthetic is
 * a separate axis — see {@link DeviceSource}. How the packet arrives at the platform (HTTP push vs
 * MQTT publish) is another — see {@link DeviceProtocol}. "Simulator" was once a constant of this
 * enum, which made it impossible to say that a simulated meter emulates NB-IoT, and left such
 * devices with no identity field and therefore no address for an uplink to resolve through. HTTP
 * was another: it named the ingress bearer rather than a network, and has moved to
 * {@link DeviceProtocol}.
 */
public enum CommunicationProfile {

    /**
     * LoRaWAN. DevEUI addresses the device; JoinEUI and AppKey are its OTAA join credentials.
     * AppKey is the AES-128 root key — it is a secret, and is the reason {@link #secretFields}
     * exists at all.
     */
    LORAWAN("devEui",
            List.of(field("devEui", "DevEUI", true, Format.HEX_16),
                    field("joinEui", "JoinEUI", false, Format.HEX_16),
                    field("appKey", "AppKey", false, Format.HEX_32, true))),

    /** NB-IoT. Addressed by IMEI; the SIM and its operator are needed to chase connectivity faults. */
    NB_IOT("imei",
            List.of(field("imei", "IMEI", true, Format.IMEI),
                    field("sim", "SIM (ICCID)", false, Format.ICCID),
                    field("operator", "Operator", false, Format.TEXT))),

    /** 4G cellular. Same identifiers as NB-IoT — same modem family, different radio bearer. */
    CELLULAR("imei",
            List.of(field("imei", "IMEI", true, Format.IMEI),
                    field("sim", "SIM (ICCID)", false, Format.ICCID),
                    field("operator", "Operator", false, Format.TEXT))),

    /** Wired IP. The device is reached by address, not by a radio identifier. */
    ETHERNET(null, List.of()),

    /**
     * MQTT broker addressing. The broker-level client identifier addresses the device; the topic it
     * publishes on is recorded so the receiver can resolve a device from the topic alone when a
     * shared client connection fans out many meters (the usual SCADA bridge arrangement).
     *
     * <p>Distinct from {@link DeviceProtocol#MQTT}: that answers "does the packet arrive over an
     * MQTT connection?", this answers "is the device addressed as an MQTT client on a broker?". A
     * LoRaWAN meter whose network server forwards uplinks over MQTT has protocol MQTT and profile
     * LORAWAN — not this one.
     */
    MQTT("clientId",
            List.of(field("clientId", "MQTT Client ID", true, Format.IDENTIFIER),
                    field("topic", "Publish Topic", false, Format.TEXT),
                    field("username", "Broker Username", false, Format.TEXT),
                    field("password", "Broker Password", false, Format.TEXT, true))),

    /**
     * Raw TCP. Loggers and RTUs that hold a long-lived socket and frame their own binary packets.
     * {@code unitId} is the identifier carried in the frame header.
     */
    TCP("unitId",
            List.of(field("unitId", "Unit ID", true, Format.IDENTIFIER),
                    field("macAddress", "MAC Address", false, Format.MAC))),

    /**
     * Raw UDP. Same framing as TCP but connectionless — chosen where battery budget will not pay
     * for a handshake. Datagrams are unauthenticated by the transport, so an HMAC secret is the
     * norm rather than the exception here.
     */
    UDP("unitId",
            List.of(field("unitId", "Unit ID", true, Format.IDENTIFIER),
                    field("hmacKey", "HMAC Key", false, Format.HEX_32, true))),

    /** WebSocket. Browser-side and edge-gateway pushes over an upgraded HTTP connection. */
    WEBSOCKET("clientId",
            List.of(field("clientId", "Client ID", true, Format.IDENTIFIER),
                    credentialField("deviceToken", "Device Token", Format.TEXT)));

    /** Which of this profile's fields becomes {@code Device.networkAddress}; null if none does. */
    private final String identityField;

    private final List<Field> fields;

    CommunicationProfile(String identityField, List<Field> fields) {
        this.identityField = identityField;
        this.fields = fields;
    }

    /**
     * One communication-specific input.
     *
     * @param key          the JSON key, stable across the API and the {@code provisioning} column
     * @param label        what the operator reads on the form
     * @param required     whether registration fails without it
     * @param format       the shape it must have
     * @param secret       true when the value must be encrypted at rest and never read back
     * @param lookupHashed true when the receiver must find a device <em>by</em> this value, so a
     *                     deterministic digest of it is stored alongside the ciphertext — see
     *                     {@link #hashField()}
     */
    public record Field(String key, String label, boolean required, Format format, boolean secret,
                        boolean lookupHashed) {

        /**
         * The provisioning key holding this field's lookup digest.
         *
         * <p>A secret is encrypted with AES-GCM under a random IV, which is correct for
         * confidentiality and useless as a lookup key: the same token encrypts to a different value
         * every time. So a credential the receiver must search by is stored twice — the ciphertext
         * to keep it, and this digest to find it. The digest is not itself a credential, so read
         * access to the device table does not let anyone authenticate with what they find.
         *
         * <p>Must agree with {@code DeviceTokenAuthenticator.TOKEN_HASH_FIELD} and with the
         * expression index V1403 creates over it; a mismatch makes the scheme silently unmatchable
         * rather than broken, which is how it came to be unreachable in the first place.
         */
        public String hashField() {
            return key + "Hash";
        }
    }

    /** The shapes a communication field can take. Each is a hard rejection, not a warning. */
    public enum Format {
        /** 16 hexadecimal characters — an 8-byte EUI-64. */
        HEX_16(Pattern.compile("^[0-9A-Fa-f]{16}$"), "16 hexadecimal characters"),
        /** 32 hexadecimal characters — a 16-byte AES-128 key. */
        HEX_32(Pattern.compile("^[0-9A-Fa-f]{32}$"), "32 hexadecimal characters"),
        /** 15 digits. The check digit is not verified: test and staging fleets rarely carry one. */
        IMEI(Pattern.compile("^\\d{15}$"), "15 digits"),
        /** 18–22 digits, covering the ICCID lengths in use across Indian operators. */
        ICCID(Pattern.compile("^\\d{18,22}$"), "18 to 22 digits"),
        /**
         * A free-form network identifier: letters, digits and {@code : _ . -}, at most 32 characters.
         *
         * <p>The 32 is not arbitrary. Any field bearing this format may become
         * {@code Device.networkAddress}, which is {@code VARCHAR(32)}; a longer bound would let
         * registration accept a client id that the insert then truncates or rejects.
         */
        IDENTIFIER(Pattern.compile("^[A-Za-z0-9:_.\\-]{1,32}$"),
                "at most 32 letters, digits, colons, underscores, dots or hyphens"),
        /** A MAC address in colon or hyphen notation. */
        MAC(Pattern.compile("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$"),
                "six hex octets separated by colons or hyphens"),
        /** Free text, bounded so a paste accident cannot fill the column. */
        TEXT(Pattern.compile("^.{1,60}$"), "at most 60 characters");

        private final Pattern pattern;
        private final String expectation;

        Format(Pattern pattern, String expectation) {
            this.pattern = pattern;
            this.expectation = expectation;
        }

        public boolean matches(String value) {
            return pattern.matcher(value).matches();
        }

        /** Human-readable rule, used verbatim in the 400 response so the operator can self-correct. */
        public String expectation() {
            return expectation;
        }
    }

    private static Field field(String key, String label, boolean required, Format format) {
        return new Field(key, label, required, format, false, false);
    }

    private static Field field(String key, String label, boolean required, Format format, boolean secret) {
        return new Field(key, label, required, format, secret, false);
    }

    /**
     * A secret the receiver authenticates <em>and identifies</em> a device by.
     *
     * <p>Distinct from an ordinary secret because of what is done with it, not how well it is
     * guarded. An AppKey is checked against a device already resolved; a device token is how the
     * device is resolved, so it needs a searchable digest as well as ciphertext. Optional by
     * construction — a token is issued at provisioning, not demanded at registration.
     */
    private static Field credentialField(String key, String label, Format format) {
        return new Field(key, label, false, format, true, true);
    }

    public List<Field> fields() {
        return fields;
    }

    public String identityField() {
        return identityField;
    }

    /**
     * The value that should become the device's {@code networkAddress}, or null when this profile
     * has no network-layer identifier.
     *
     * <p>Normalised to upper case: DevEUIs arrive from vendor portals in both cases, and the
     * tenant-unique index must not admit {@code a81b...} alongside {@code A81B...} as two devices.
     */
    public String networkAddressFrom(Map<String, String> communication) {
        if (identityField == null) {
            return null;
        }
        String value = communication.get(identityField);
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    /** Resolves a transport string from the API or the database; null when it names no profile. */
    public static CommunicationProfile from(String transport) {
        if (transport == null) {
            return null;
        }
        for (CommunicationProfile profile : values()) {
            if (profile.name().equalsIgnoreCase(transport.trim())) {
                return profile;
            }
        }
        return null;
    }
}
