package com.aquagrid.platform.iot.receiver.application.pipeline;

import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.iot.receiver.application.authentication.ReceiverAuthenticationService;
import com.aquagrid.platform.iot.receiver.application.security.IpAllowListService;
import com.aquagrid.platform.iot.receiver.application.security.ReceiverRateLimiter;
import com.aquagrid.platform.iot.receiver.domain.model.IdentifierType;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.spi.PacketAuthenticator;
import com.aquagrid.platform.iot.receiver.spi.ReceiverStage;
import com.aquagrid.platform.security.ratelimit.RateLimitDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The gate. Nothing reaches a database lookup without passing it.
 *
 * <p>Three checks, in ascending order of cost, and the ordering is the design:
 *
 * <ol>
 *   <li><b>Source allow-list</b> — a set membership test on an address the receiver already has.</li>
 *   <li><b>Per-source rate limit</b> — an in-memory token bucket. Runs before authentication so a
 *       flood costs a map lookup rather than a connection from the pool and a hash comparison per
 *       packet. A limiter placed after authentication protects nothing that matters.</li>
 *   <li><b>The authenticator chain</b> — which may hit the database, and by this point is only
 *       reached by traffic that has already been bounded.</li>
 * </ol>
 *
 * <p>Running first is also what stops this endpoint from being an oracle. Device resolution is a
 * lookup against identifiers a caller supplies; if it ran before authentication, an unauthenticated
 * prober could enumerate which DevEUIs and IMEIs are registered by watching which requests take
 * longer or fail differently.
 */
@Component
@RequiredArgsConstructor
public class AuthenticationStage implements ReceiverStage {

    private final IpAllowListService ipAllowList;
    private final ReceiverRateLimiter rateLimiter;
    private final ReceiverAuthenticationService authenticationService;

    @Override
    public String name() {
        return "AUTHENTICATION";
    }

    @Override
    public Decision execute(ReceptionContext context) {
        String sourceIp = context.getPacket().sourceIp();

        if (!ipAllowList.isAllowed(sourceIp)) {
            context.reject(ErrorCode.RECEIVER_IP_NOT_ALLOWED,
                    "Source address is not permitted to deliver packets");
            return Decision.HALT;
        }

        RateLimitDecision limit = rateLimiter.checkSource(sourceIp);
        if (!limit.allowed()) {
            context.reject(ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many packets from this source; retry in "
                            + limit.retryAfterSeconds() + "s");
            return Decision.HALT;
        }

        ReceiverAuthenticationService.Verdict verdict =
                authenticationService.authenticate(context.getPacket());
        PacketAuthenticator.AuthenticationResult result = verdict.result();

        if (!result.authenticated()) {
            context.reject(result.failure() == null
                            ? ErrorCode.RECEIVER_AUTHENTICATION_FAILED : result.failure(),
                    result.detail());
            // The scheme is recorded even on failure: "which credential did it try to use" is the
            // first question asked when a fleet stops being accepted after a key rotation.
            context.authenticatedAs(null, verdict.scheme());
            return Decision.HALT;
        }

        context.authenticatedAs(result.principal(), verdict.scheme());

        // An authenticator that proved a device binding contributes it as a derived identifier,
        // which the resolver prefers over anything the payload merely asserted.
        for (Map.Entry<IdentifierType, String> identifier : result.identifiers().entrySet()) {
            context.deriveIdentifier(identifier.getKey(), identifier.getValue());
        }
        if (result.tenantId() != null) {
            context.resolvedTenant(result.tenantId());
        }
        return Decision.CONTINUE;
    }

    @Override
    public int getOrder() {
        return Stages.AUTHENTICATION;
    }
}
