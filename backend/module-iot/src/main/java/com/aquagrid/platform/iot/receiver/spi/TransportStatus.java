package com.aquagrid.platform.iot.receiver.spi;

import lombok.Builder;

import java.time.Instant;

/**
 * A transport's live self-report.
 *
 * <p>Answers the question that actually gets asked during an incident — "is the LoRaWAN listener
 * up, and when did it last see anything?" — from the transport itself rather than by inferring it
 * from an absence of rows. A listener that is running but has had no traffic for an hour looks
 * identical, in the database, to one that never started; only the transport can tell them apart,
 * and that difference decides whether the engineer restarts a service or calls the network operator.
 *
 * @param transport         the transport code
 * @param displayName       human-readable name
 * @param running           whether the listener is currently accepting
 * @param endpoint          where it listens — route, topic filter or {@code host:port}
 * @param stateful          whether it holds connections open between packets
 * @param activeConnections open connections; zero for stateless transports
 * @param packetsReceived   packets taken delivery of since start
 * @param errors            packets this transport could not even turn into an InboundPacket
 * @param lastPacketAt      when the last packet arrived; null if none has
 * @param startedAt         when the listener started
 */
@Builder
public record TransportStatus(
        String transport,
        String displayName,
        boolean running,
        String endpoint,
        boolean stateful,
        int activeConnections,
        long packetsReceived,
        long errors,
        Instant lastPacketAt,
        Instant startedAt
) {
}
