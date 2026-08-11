package com.aquagrid.platform.iot.receiver.spi;

import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionStatus;

/**
 * Notified once for every packet the receiver took delivery of, whatever became of it.
 *
 * <p>Distinct from {@link ReceiverStage}, and the distinction is the point. A stage sits <em>in</em>
 * the chain: it may halt the packet, it runs only if every stage before it let the packet through,
 * and a packet refused at authentication never reaches one. An observer sits <em>after</em> the
 * chain, in {@code ReceiverService}'s {@code finally} block, and is therefore the only extension
 * point with the guarantee that matters here — <b>it runs for every packet, including the ones the
 * pipeline refused</b>.
 *
 * <p>That guarantee is why raw-payload retention is an observer rather than a stage. A packet
 * rejected because its device is not registered is exactly the packet whose payload someone needs to
 * read, and a stage placed anywhere in the chain would miss it.
 *
 * <h2>What an implementation must not do</h2>
 *
 * <ul>
 *   <li><b>Do not throw.</b> The outcome has already been returned to the transport by the time
 *       observers run. {@code ReceiverService} contains a throwing observer so that bookkeeping can
 *       never change a decision that has been made, but an implementation that relies on being
 *       caught is one whose failures are invisible — swallow and log your own.</li>
 *   <li><b>Do not join the packet's transaction.</b> There is no ambient transaction here by
 *       design: the pipeline's stages own their own, so that a failure to write a log cannot roll
 *       back a reading. Use {@code REQUIRES_NEW}, as {@code PacketLogService} does.</li>
 *   <li><b>Do not be slow.</b> This runs on the reception path, per packet, on the transport's
 *       thread.</li>
 * </ul>
 */
public interface ReceptionObserver {

    /**
     * Called once per packet, after the pipeline has finished with it.
     *
     * @param context the reception, in whatever state the pipeline left it. Most of it is nullable:
     *                a packet refused at authentication has no device, no tenant and no telemetry
     * @param status  {@code ACCEPTED}, {@code DUPLICATE} or {@code REJECTED}
     */
    void onReception(ReceptionContext context, ReceptionStatus status);
}
