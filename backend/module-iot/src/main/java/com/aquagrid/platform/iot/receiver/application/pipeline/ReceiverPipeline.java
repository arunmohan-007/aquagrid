package com.aquagrid.platform.iot.receiver.application.pipeline;

import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.iot.receiver.application.metrics.ReceiverMetrics;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.spi.ReceiverStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the stage chain over one packet.
 *
 * <p>Deliberately small. Everything that decides anything lives in a stage; this only sequences
 * them, times them and makes sure a stage that throws cannot take the receiver down. Keeping the
 * runner free of policy is what allows a stage to be added, removed or reordered without any risk
 * of the orchestration having assumed something about it.
 *
 * <p>Three properties it guarantees, so no stage has to:
 *
 * <ul>
 *   <li><b>Halting is uniform.</b> A stage returns {@code HALT} having recorded why on the context.
 *       There is exactly one place a refusal becomes a log row, a metric and a response, rather
 *       than eight places that must agree.</li>
 *   <li><b>Timing is automatic.</b> Every stage is measured without having remembered to add a
 *       timer, so "which stage is slow" is answerable for stages nobody anticipated would be.</li>
 *   <li><b>A thrown stage is contained.</b> Turned into {@code INTERNAL_ERROR}, which is the
 *       classification the dispatcher dead-letters on — so a defect in one stage costs the
 *       readings' <em>latency</em>, not the readings.</li>
 * </ul>
 */
@Slf4j
@Service
public class ReceiverPipeline {

    private final List<ReceiverStage> stages;
    private final ReceiverMetrics metrics;

    public ReceiverPipeline(List<ReceiverStage> stages, ReceiverMetrics metrics) {
        List<ReceiverStage> ordered = new ArrayList<>(stages);
        ordered.sort(AnnotationAwareOrderComparator.INSTANCE);
        this.stages = List.copyOf(ordered);
        this.metrics = metrics;
        log.info("Receiver pipeline: {}", this.stages.stream().map(ReceiverStage::name).toList());
    }

    /** Runs every stage until one halts or the chain is exhausted. */
    public void run(ReceptionContext context) {
        for (ReceiverStage stage : stages) {
            long startedAt = System.nanoTime();
            try {
                ReceiverStage.Decision decision = stage.execute(context);
                if (decision == ReceiverStage.Decision.HALT) {
                    return;
                }
            } catch (RuntimeException e) {
                // Deliberately caught here rather than at the transport. A stage that throws is a
                // defect, and the packet may well be perfectly good — classifying it as an internal
                // error is what routes it to the dead-letter queue, so the reading survives the bug
                // and can be replayed once the bug is fixed.
                log.error("Stage {} failed on packet {}", stage.name(), context.packetId(), e);
                context.reject(ErrorCode.INTERNAL_ERROR,
                        "Receiver stage " + stage.name() + " failed");
                return;
            } finally {
                long elapsed = System.nanoTime() - startedAt;
                context.recordStage(stage.name(), elapsed);
                metrics.recordStage(context.transport(), stage.name(), elapsed);
            }
        }
    }

    public List<String> stageNames() {
        return stages.stream().map(ReceiverStage::name).toList();
    }
}
