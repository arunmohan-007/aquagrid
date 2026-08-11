package com.aquagrid.platform.iot.receiver.application.service;

import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.common.tenant.TenantContext;
import com.aquagrid.platform.common.web.CorrelationIdFilter;
import com.aquagrid.platform.iot.receiver.api.ReceiverGateway;
import com.aquagrid.platform.iot.receiver.api.ReceptionOutcome;
import com.aquagrid.platform.iot.receiver.application.metrics.ReceiverMetrics;
import com.aquagrid.platform.iot.receiver.application.pipeline.ReceiverPipeline;
import com.aquagrid.platform.iot.receiver.domain.model.InboundPacket;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionStatus;
import com.aquagrid.platform.iot.receiver.spi.ReceptionObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The single entry point every transport delivers to.
 *
 * <p>Not transactional, and that is the most important thing about it. The pipeline's stages own
 * their own transactions — the ingest port commits the reading, the replay claim commits its own
 * row, the packet log and the dead letter each commit independently — because the alternative is
 * one transaction spanning the whole reception, in which a failure to write the log would roll back
 * the reading, and a rejected packet would roll back the evidence of its own rejection. Those are
 * the two outcomes this module exists to prevent.
 *
 * <p>What is guaranteed here instead is that <b>every packet is accounted for</b>. Whatever the
 * pipeline does — accept, refuse, throw — the {@code finally} block writes a packet log row, counts
 * it, records its metrics, notifies the {@link ReceptionObserver}s and clears the thread's tenant
 * binding. There is no path through this method that leaves a packet unrecorded.
 *
 * <p>Thread-safe: a context per packet, no mutable state on the service. TCP, UDP and WebSocket
 * receivers call this concurrently from per-connection virtual threads.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiverService implements ReceiverGateway {

    private final ReceiverPipeline pipeline;
    private final PacketLogService packetLogService;
    private final DeadLetterService deadLetterService;
    private final ReceiverStatisticsService statisticsService;
    private final ReceiverMetrics metrics;

    /**
     * Extensions that need to see every packet, including the refused ones.
     *
     * <p>Here rather than in the pipeline because a stage only runs if every stage before it let the
     * packet through, and a packet rejected at authentication is exactly the one whose payload
     * somebody needs to read — see {@link ReceptionObserver}. Injected as a list so an observer is
     * added by declaring a bean, with no edit to this class.
     */
    private final List<ReceptionObserver> observers;

    @Override
    public ReceptionOutcome receive(InboundPacket packet) {
        ReceptionContext context = new ReceptionContext(packet);
        String previousTraceId = MDC.get(CorrelationIdFilter.TRACE_ID_KEY);
        if (packet.correlationId() != null) {
            MDC.put(CorrelationIdFilter.TRACE_ID_KEY, packet.correlationId());
        }

        try {
            pipeline.run(context);
            return outcomeOf(context);
        } catch (RuntimeException e) {
            // The pipeline already contains per-stage failures, so reaching here means the runner
            // itself failed. Classified as internal so the packet is dead-lettered rather than lost.
            log.error("Receiver failed unexpectedly on packet {}", packet.packetId(), e);
            context.reject(ErrorCode.INTERNAL_ERROR, "Receiver failure");
            return ReceptionOutcome.reject(packet.packetId(), ErrorCode.INTERNAL_ERROR,
                    "Receiver failure");
        } finally {
            ReceptionStatus status = statusOf(context);
            try {
                packetLogService.record(context, status);
                statisticsService.record(context, status);
                metrics.recordReception(context, status);
                notifyObservers(context, status);
                if (context.isRejected()) {
                    deadLetterService.store(context);
                }
            } catch (RuntimeException e) {
                // Bookkeeping must never change the outcome already returned to the transport.
                log.error("Post-reception bookkeeping failed for packet {}", packet.packetId(), e);
            }
            // The tenant is bound per packet on a possibly-pooled thread. Leaving it set would let
            // the next packet's queries run under the previous packet's tenant filter.
            TenantContext.clear();
            if (previousTraceId == null) {
                MDC.remove(CorrelationIdFilter.TRACE_ID_KEY);
            } else {
                MDC.put(CorrelationIdFilter.TRACE_ID_KEY, previousTraceId);
            }
        }
    }

    /**
     * Runs every observer, and contains each one separately.
     *
     * <p>Per observer rather than around the loop: one failing observer must not stop the ones after
     * it. The whole point of the extension point is that a packet is recorded even when things have
     * gone wrong, and an unconditional loop would let a defect in the newest observer silently stop
     * the oldest one from writing.
     */
    private void notifyObservers(ReceptionContext context, ReceptionStatus status) {
        for (ReceptionObserver observer : observers) {
            try {
                observer.onReception(context, status);
            } catch (RuntimeException e) {
                log.error("Reception observer {} failed on packet {}",
                        observer.getClass().getSimpleName(), context.packetId(), e);
            }
        }
    }

    private static ReceptionStatus statusOf(ReceptionContext context) {
        if (context.isRejected()) {
            return ReceptionStatus.REJECTED;
        }
        return context.isDuplicate() ? ReceptionStatus.DUPLICATE : ReceptionStatus.ACCEPTED;
    }

    private static ReceptionOutcome outcomeOf(ReceptionContext context) {
        if (context.isRejected()) {
            return new ReceptionOutcome.Rejected(context.packetId(),
                    context.getRejection().code(), context.getRejection().detail());
        }
        if (context.isDuplicate()) {
            return new ReceptionOutcome.Duplicate(context.packetId(),
                    context.deviceIfResolved().map(device -> device.getId()).orElse(null));
        }
        return new ReceptionOutcome.Accepted(context.packetId(),
                context.deviceIfResolved().map(device -> device.getId()).orElse(null),
                context.getEvent() == null ? null : context.getEvent().eventId());
    }
}
