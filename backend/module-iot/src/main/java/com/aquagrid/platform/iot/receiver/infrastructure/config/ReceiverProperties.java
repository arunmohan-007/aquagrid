package com.aquagrid.platform.iot.receiver.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Receiver configuration.
 *
 * <p>Kept apart from {@code IotProperties} rather than nested inside it: that record governs which
 * transports a deployment loads, and this governs how the receiver behaves once one of them
 * delivers something. Merging them would mean a change to ingestion policy touching the same class
 * a change to transport activation touches, and those are reviewed by different people.
 *
 * <p><b>Every default here is the safe one.</b> Authentication is required, payload retention is on
 * only for the packets that need it, and the socket transports are off. A deployment that wants
 * less has to say so.
 */
@Validated
@ConfigurationProperties(prefix = "aquagrid.iot.receiver")
public record ReceiverProperties(
        boolean enabled,
        Security security,
        Limits limits,
        Retention retention,
        Sockets sockets,
        Duration statisticsFlushInterval,
        Duration sessionIdleTimeout
) {

    public ReceiverProperties {
        security = security == null ? Security.defaults() : security;
        limits = limits == null ? Limits.defaults() : limits;
        retention = retention == null ? Retention.defaults() : retention;
        sockets = sockets == null ? Sockets.defaults() : sockets;
        statisticsFlushInterval = statisticsFlushInterval == null
                ? Duration.ofSeconds(30) : statisticsFlushInterval;
        sessionIdleTimeout = sessionIdleTimeout == null
                ? Duration.ofMinutes(15) : sessionIdleTimeout;
    }

    /**
     * @param requireAuthentication when false, packets bearing no credential are accepted from
     *                              allow-listed sources. Intended for deployments where the
     *                              gateway sits on a carrier APN or a private VPN and the network
     *                              is the control. Off means <em>anyone</em> who can reach the
     *                              endpoint can file readings, which is why it defaults to true
     * @param ipAllowList           CIDRs permitted to deliver packets. Empty means no IP
     *                              restriction — deliberately not "deny all", because an empty
     *                              list that silently blocked every gateway would be a far worse
     *                              failure mode than one that does not narrow an already
     *                              authenticated path
     * @param replayWindow          how long a packet identity is remembered. Must exceed the
     *                              longest plausible store-and-forward delay, or an NB-IoT device
     *                              waking from PSM has its backlog treated as fresh on redelivery
     * @param maxClockSkewPast      how far behind the receiver's clock a device timestamp may be
     * @param maxClockSkewFuture    how far ahead. Much tighter than the past bound: devices drift
     *                              backwards and buffer, they do not legitimately predict
     * @param signatureRequired     transports on which an HMAC signature is mandatory. UDP belongs
     *                              here in any deployment reachable from the internet — a datagram
     *                              is trivially spoofable and carries no other proof of origin
     */
    public record Security(
            boolean requireAuthentication,
            List<String> ipAllowList,
            Duration replayWindow,
            Duration maxClockSkewPast,
            Duration maxClockSkewFuture,
            List<String> signatureRequired,
            List<GatewayCredential> gateways
    ) {
        public Security {
            ipAllowList = ipAllowList == null ? List.of() : List.copyOf(ipAllowList);
            replayWindow = replayWindow == null ? Duration.ofHours(24) : replayWindow;
            maxClockSkewPast = maxClockSkewPast == null ? Duration.ofDays(7) : maxClockSkewPast;
            maxClockSkewFuture = maxClockSkewFuture == null ? Duration.ofMinutes(5) : maxClockSkewFuture;
            signatureRequired = signatureRequired == null ? List.of() : List.copyOf(signatureRequired);
            gateways = gateways == null ? List.of() : List.copyOf(gateways);
        }

        static Security defaults() {
            return new Security(true, List.of(), null, null, null, List.of(), List.of());
        }

        public boolean requiresSignature(String transport) {
            return signatureRequired.stream().anyMatch(t -> t.equalsIgnoreCase(transport));
        }
    }

    /**
     * A gateway or integration permitted to deliver packets, identified by API key.
     *
     * <p>Configuration rather than a database table, and the distinction is about what kind of
     * credential this is. These are infrastructure credentials — a handful of them, one per
     * ChirpStack instance or carrier webhook, changing at the rate the estate changes, and needed
     * before the first packet can be accepted. A table would put a bootstrap dependency and a
     * migration in the path of standing up a gateway, to manage perhaps five rows. Per-<em>device</em>
     * credentials are a different problem with a different scale and live on the device row.
     *
     * <p>Only the SHA-256 of the key is configured. The deployment holds the hash; the plaintext
     * exists at the gateway and nowhere in this system, so a leaked configuration file, a
     * checked-in values.yaml or a support bundle does not hand over working credentials.
     *
     * @param principal   who this is, recorded on every packet log row it authenticates
     * @param apiKeySha256 lowercase hex SHA-256 of the API key
     * @param transports  which transports this credential is valid on. Empty means all — narrow it:
     *                    a LoRaWAN network server's key has no business authenticating UDP
     */
    public record GatewayCredential(
            String principal,
            String apiKeySha256,
            List<String> transports
    ) {
        public GatewayCredential {
            transports = transports == null ? List.of() : List.copyOf(transports);
            apiKeySha256 = apiKeySha256 == null ? "" : apiKeySha256.trim().toLowerCase();
        }

        public boolean permits(String transport) {
            return transports.isEmpty()
                    || transports.stream().anyMatch(t -> t.equalsIgnoreCase(transport));
        }
    }

    /**
     * @param maxPacketBytes             hard ceiling, checked before anything parses the payload
     * @param packetsPerMinutePerDevice  a meter reporting far above its schedule is either faulty
     *                                   or forged; either way the platform should not ingest it
     *                                   without bound
     * @param packetsPerMinutePerSource  per source IP, which is what bounds a gateway gone rogue.
     *                                   Generous, because one gateway legitimately fronts thousands
     *                                   of meters
     * @param maxMetricsPerPacket        bounds the fan-out of one packet into reading rows
     */
    public record Limits(
            int maxPacketBytes,
            int packetsPerMinutePerDevice,
            int packetsPerMinutePerSource,
            int maxMetricsPerPacket
    ) {
        public Limits {
            maxPacketBytes = maxPacketBytes <= 0 ? 65_536 : maxPacketBytes;
            packetsPerMinutePerDevice = packetsPerMinutePerDevice <= 0 ? 120 : packetsPerMinutePerDevice;
            packetsPerMinutePerSource = packetsPerMinutePerSource <= 0 ? 30_000 : packetsPerMinutePerSource;
            maxMetricsPerPacket = maxMetricsPerPacket <= 0 ? 64 : maxMetricsPerPacket;
        }

        static Limits defaults() {
            return new Limits(0, 0, 0, 0);
        }
    }

    /**
     * How long each kind of row is kept.
     *
     * <p>{@code storeAcceptedPayloads} defaults false and {@code storeRejectedPayloads} true, which
     * is the asymmetry that matters: an accepted payload's information is already in the readings
     * table, while a rejected one's is nowhere else at all — discarding it destroys the only
     * evidence of why the packet was refused.
     */
    public record Retention(
            boolean storeAcceptedPayloads,
            boolean storeRejectedPayloads,
            Duration packetLog,
            Duration payload,
            Duration receiverLog,
            Duration connectionHistory,
            Duration deadLetter,
            Duration statistics
    ) {
        public Retention {
            packetLog = packetLog == null ? Duration.ofDays(90) : packetLog;
            payload = payload == null ? Duration.ofDays(14) : payload;
            receiverLog = receiverLog == null ? Duration.ofDays(365) : receiverLog;
            connectionHistory = connectionHistory == null ? Duration.ofDays(90) : connectionHistory;
            deadLetter = deadLetter == null ? Duration.ofDays(30) : deadLetter;
            statistics = statistics == null ? Duration.ofDays(400) : statistics;
        }

        static Retention defaults() {
            return new Retention(false, true, null, null, null, null, null, null);
        }
    }

    /**
     * The transports the receiver binds a socket for itself, rather than being handed bytes by the
     * servlet container or a broker client. Off by default — a listening port is attack surface,
     * and a deployment with no TCP loggers should not have one open.
     */
    public record Sockets(
            SocketListener tcp,
            SocketListener udp,
            WebSocketListener websocket
    ) {
        public Sockets {
            tcp = tcp == null ? SocketListener.disabled(5555) : tcp;
            udp = udp == null ? SocketListener.disabled(5556) : udp;
            websocket = websocket == null ? WebSocketListener.disabled() : websocket;
        }

        static Sockets defaults() {
            return new Sockets(null, null, null);
        }
    }

    /**
     * @param maxConnections bounds the accept loop. A socket server without one is a memory
     *                       exhaustion primitive for anyone who can open connections
     * @param readTimeout    idle read timeout, which is what actually retires a half-open socket;
     *                       TCP itself will hold one indefinitely
     */
    public record SocketListener(
            boolean enabled,
            String bindAddress,
            int port,
            int maxConnections,
            Duration readTimeout,
            int maxFrameBytes
    ) {
        public SocketListener {
            bindAddress = bindAddress == null || bindAddress.isBlank() ? "0.0.0.0" : bindAddress;
            maxConnections = maxConnections <= 0 ? 2_000 : maxConnections;
            readTimeout = readTimeout == null ? Duration.ofMinutes(5) : readTimeout;
            maxFrameBytes = maxFrameBytes <= 0 ? 8_192 : maxFrameBytes;
        }

        static SocketListener disabled(int defaultPort) {
            return new SocketListener(false, null, defaultPort, 0, null, 0);
        }
    }

    public record WebSocketListener(boolean enabled, String path, int maxSessions) {
        public WebSocketListener {
            path = path == null || path.isBlank() ? "/ws/v1/telemetry" : path;
            maxSessions = maxSessions <= 0 ? 2_000 : maxSessions;
        }

        static WebSocketListener disabled() {
            return new WebSocketListener(false, null, 0);
        }
    }
}
