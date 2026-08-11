package com.aquagrid.platform.iot.receiver.application.metrics;

import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The receiver's Micrometer instrumentation, in one place.
 *
 * <p>Centralised rather than scattered through the stages for a reason that only shows up later:
 * metric names and tag keys are a contract with every dashboard and alert rule built on them, and a
 * counter registered inline in a stage is a contract nobody can find. Everything the receiver
 * publishes is named here, so renaming one is a compile-time task rather than an archaeology
 * exercise across twenty files.
 *
 * <p><b>Tag cardinality is bounded on purpose.</b> Nothing is ever tagged with a device id, a
 * tenant id or a source address. A time-series backend allocates a series per tag combination, so
 * tagging by device turns 100,000 meters into 100,000 series per metric and takes the monitoring
 * system down before it reports that anything is wrong. Per-device numbers live in
 * {@code receiver_packet_statistics}, which is built for exactly that grain; metrics stay at the
 * transport and outcome grain, where the cardinality is a couple of dozen.
 *
 * <p>Meters are resolved once and cached. {@code Counter.builder(..).register(..)} performs a map
 * lookup plus tag list allocation on every call, and this is the hottest path in the platform.
 */
@Component
public class ReceiverMetrics {

    public static final String PACKETS = "aquagrid.receiver.packets";
    public static final String PROCESSING = "aquagrid.receiver.processing";
    public static final String PAYLOAD_BYTES = "aquagrid.receiver.payload.bytes";
    public static final String STAGE = "aquagrid.receiver.stage";
    public static final String AUTH_FAILURES = "aquagrid.receiver.auth.failures";
    public static final String CONNECTIONS = "aquagrid.receiver.connections";
    public static final String DEAD_LETTERS = "aquagrid.receiver.deadletters";

    private final MeterRegistry registry;

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<String, DistributionSummary> summaries = new ConcurrentHashMap<>();

    /**
     * Live connection counts, published as gauges.
     *
     * <p>A gauge needs a value it can read at scrape time, so the receiver has to hold the number
     * rather than emit it — hence the map of counters the transports update and Micrometer samples.
     */
    private final Map<String, AtomicLong> connectionGauges = new ConcurrentHashMap<>();

    public ReceiverMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Records the outcome of one reception: count, duration, payload size.
     *
     * <p>Duration is measured from the moment the transport handed the packet over, not from the
     * start of the pipeline, so it includes the stages' own overhead. A receiver that is fast
     * because it skipped work is not fast.
     */
    public void recordReception(ReceptionContext context, ReceptionStatus status) {
        String transport = context.transport();
        String outcome = status.name();
        String errorCode = context.isRejected() ? context.getRejection().code().name() : "NONE";

        counter(PACKETS, "transport", transport, "status", outcome, "error", errorCode).increment();
        timer(PROCESSING, "transport", transport, "status", outcome)
                .record(context.elapsed());
        summary(PAYLOAD_BYTES, "transport", transport)
                .record(context.getPacket().size());
    }

    /** Per-stage timing, so "which stage is slow" needs no extra instrumentation to answer. */
    public void recordStage(String transport, String stage, long nanos) {
        timer(STAGE, "transport", transport, "stage", stage)
                .record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    /**
     * Authentication failures, tagged by scheme and reason but never by source address.
     *
     * <p>Counted separately from the packet counter even though every failure is also a rejection:
     * this is the series an alert rule watches, and a rule that has to filter a general-purpose
     * counter by an error tag is a rule that breaks when a new error code is added.
     */
    public void recordAuthenticationFailure(String transport, String scheme, String reason) {
        counter(AUTH_FAILURES, "transport", transport, "scheme", scheme, "reason", reason)
                .increment();
    }

    public void recordDeadLetter(String transport, String errorCode) {
        counter(DEAD_LETTERS, "transport", transport, "error", errorCode).increment();
    }

    /** Registers (once) and returns the live connection count for a transport. */
    public AtomicLong connectionGauge(String transport) {
        return connectionGauges.computeIfAbsent(transport, key -> {
            AtomicLong holder = new AtomicLong();
            registry.gauge(CONNECTIONS, io.micrometer.core.instrument.Tags.of("transport", key),
                    holder, AtomicLong::doubleValue);
            return holder;
        });
    }

    // ---- Cached meter lookups ----------------------------------------------------------------

    private Counter counter(String name, String... tags) {
        return counters.computeIfAbsent(key(name, tags),
                unused -> Counter.builder(name).tags(tags)
                        .description("Packets handled by the IoT receiver")
                        .register(registry));
    }

    private Timer timer(String name, String... tags) {
        return timers.computeIfAbsent(key(name, tags),
                unused -> Timer.builder(name).tags(tags)
                        // Percentile histograms, not client-side percentiles: only histogram
                        // buckets can be aggregated correctly across replicas, and a p99 computed
                        // per instance and then averaged is a number with no meaning.
                        .publishPercentileHistogram()
                        .register(registry));
    }

    private DistributionSummary summary(String name, String... tags) {
        return summaries.computeIfAbsent(key(name, tags),
                unused -> DistributionSummary.builder(name).tags(tags)
                        .baseUnit("bytes")
                        .register(registry));
    }

    private static String key(String name, String... tags) {
        return name + "|" + String.join("|", tags);
    }
}
