package com.aquagrid.platform.iot.receiver.application.service;

import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionStatus;
import com.aquagrid.platform.iot.receiver.infrastructure.persistence.PacketStatisticsRepository;
import com.aquagrid.platform.iot.receiver.infrastructure.persistence.TransportStatisticsRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Accumulates packet counters in memory and flushes them to the hourly rollups.
 *
 * <p>Buffered rather than written per packet, and the arithmetic is why. At a thousand packets a
 * second, incrementing a row per packet is two thousand statements a second contending on a handful
 * of hot rows — every replica trying to lock the same bucket. Accumulating in memory and flushing
 * every thirty seconds turns that into a few dozen upserts a minute, and the counters are exact
 * because the addition happens inside the database.
 *
 * <p>The trade is bounded and stated: up to one flush interval of counters is lost if the process
 * dies abruptly. That is acceptable for statistics precisely because they are statistics — the
 * packet log, which is the forensic record, is written synchronously and loses nothing. Losing
 * thirty seconds of a throughput chart is not the same kind of loss as losing a reading, and
 * conflating the two would make the hot path pay for a guarantee it does not need.
 *
 * <p>{@link LongAdder} rather than {@code AtomicLong}: many virtual threads increment these
 * concurrently, and an adder spreads the contention across cells instead of serialising every
 * writer on one cache line.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiverStatisticsService {

    private final PacketStatisticsRepository packetStatistics;
    private final TransportStatisticsRepository transportStatistics;

    /**
     * Used only by the shutdown flush, which cannot rely on the {@code @Transactional} proxy.
     *
     * <p>{@link #flushOnShutdown} is invoked by the container on <em>this</em> instance, not through
     * the proxy, so a plain call to {@link #flush} arrives with no transaction and every upsert in
     * it fails with {@code TransactionRequiredException}. The annotation looked like it was working
     * and was not, which is the failure mode self-invocation always has.
     */
    private final TransactionTemplate transactionTemplate;

    private final Map<DeviceBucket, Counters> deviceCounters = new ConcurrentHashMap<>();
    private final Map<TransportBucket, Counters> transportCounters = new ConcurrentHashMap<>();

    /** Hourly buckets, keyed by device. Transport is part of the key so a re-provisioned device
     *  does not silently merge two networks' traffic into one series. */
    private record DeviceBucket(UUID organizationId, UUID deviceId, String transport,
                                Instant bucketStart) {
    }

    /** Hourly buckets, keyed by transport. {@code organizationId} is null for packets refused
     *  before any device — and therefore any tenant — was known. */
    private record TransportBucket(UUID organizationId, String transport, Instant bucketStart) {
    }

    private static final class Counters {
        private final LongAdder accepted = new LongAdder();
        private final LongAdder duplicates = new LongAdder();
        private final LongAdder rejected = new LongAdder();
        private final LongAdder bytes = new LongAdder();
        private final LongAdder processingMs = new LongAdder();
        private volatile long maxProcessingMs;
        private volatile long lastPacketEpochMs;

        void record(ReceptionStatus status, int size, long millis, long atEpochMs) {
            switch (status) {
                case ACCEPTED -> accepted.increment();
                case DUPLICATE -> duplicates.increment();
                case REJECTED -> rejected.increment();
            }
            bytes.add(size);
            processingMs.add(millis);
            // Racy by construction: two threads can both read a stale max and one write is lost.
            // Accepted deliberately — synchronising the hot path to make a diagnostic maximum exact
            // would cost more than the occasional understated outlier is worth.
            if (millis > maxProcessingMs) {
                maxProcessingMs = millis;
            }
            if (atEpochMs > lastPacketEpochMs) {
                lastPacketEpochMs = atEpochMs;
            }
        }
    }

    /** Called once per reception, on the ingestion path. Must stay allocation-light and lock-free. */
    public void record(ReceptionContext context, ReceptionStatus status) {
        Instant bucket = context.getPacket().receivedAt().truncatedTo(ChronoUnit.HOURS);
        long millis = context.elapsedMillis();
        long atEpochMs = context.getPacket().receivedAt().toEpochMilli();
        int size = context.getPacket().size();
        UUID tenantId = context.getTenantId();

        transportCounters
                .computeIfAbsent(new TransportBucket(tenantId, context.transport(), bucket),
                        key -> new Counters())
                .record(status, size, millis, atEpochMs);

        // Per-device counters need a device. Packets refused before resolution are counted against
        // their transport only — which is exactly where an operator looks for them.
        if (context.deviceIfResolved().isPresent() && tenantId != null) {
            deviceCounters
                    .computeIfAbsent(new DeviceBucket(tenantId, context.deviceIfResolved().get().getId(),
                            context.transport(), bucket), key -> new Counters())
                    .record(status, size, millis, atEpochMs);
        }
    }

    @Scheduled(fixedDelayString = "${aquagrid.iot.receiver.statistics-flush-interval:PT30S}")
    @Transactional
    public void flush() {
        drainDeviceCounters();
        drainTransportCounters();
    }

    /**
     * Flushes on the way down, so a graceful shutdown does not discard the last interval.
     *
     * <p>Runs the drain inside an explicit transaction rather than calling {@link #flush}. A
     * {@code @PreDestroy} method is called by the container directly on the bean, so the
     * {@code @Transactional} proxy is not in the path and the upserts would run with no transaction
     * — which is what this method did until the fleet simulator generated enough traffic for there
     * to be anything left to flush, and every graceful shutdown started logging
     * {@code TransactionRequiredException}. The counters were being silently discarded before that,
     * too; there was simply nothing in them to lose.
     */
    @PreDestroy
    public void flushOnShutdown() {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                drainDeviceCounters();
                drainTransportCounters();
            });
        } catch (RuntimeException e) {
            // Never rethrown: a failed statistics flush must not turn a graceful shutdown into a
            // failed one. The packet log is the forensic record and is written synchronously.
            log.warn("Final statistics flush failed", e);
        }
    }

    private void drainDeviceCounters() {
        for (DeviceBucket key : java.util.Set.copyOf(deviceCounters.keySet())) {
            // Removed before reading, so increments arriving mid-flush land in a fresh bucket and
            // are picked up next time rather than being read and then overwritten to zero.
            Counters counters = deviceCounters.remove(key);
            if (counters == null) {
                continue;
            }
            try {
                packetStatistics.accumulate(key.organizationId(), key.deviceId(), key.transport(),
                        key.bucketStart(), counters.accepted.sum(), counters.duplicates.sum(),
                        counters.rejected.sum(), counters.bytes.sum(),
                        counters.processingMs.sum(), Instant.ofEpochMilli(counters.lastPacketEpochMs));
            } catch (RuntimeException e) {
                log.warn("Failed to flush device statistics for {} — counters for this interval are lost",
                        key.deviceId(), e);
            }
        }
    }

    private void drainTransportCounters() {
        for (TransportBucket key : java.util.Set.copyOf(transportCounters.keySet())) {
            Counters counters = transportCounters.remove(key);
            if (counters == null) {
                continue;
            }
            try {
                transportStatistics.accumulate(key.organizationId(), key.transport(),
                        key.bucketStart(), counters.accepted.sum(), counters.duplicates.sum(),
                        counters.rejected.sum(), counters.bytes.sum(), counters.processingMs.sum(),
                        counters.maxProcessingMs, Instant.ofEpochMilli(counters.lastPacketEpochMs));
            } catch (RuntimeException e) {
                log.warn("Failed to flush transport statistics for {}", key.transport(), e);
            }
        }
    }
}
