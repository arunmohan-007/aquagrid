package com.aquagrid.platform.iot.receiver.infrastructure.config;

import com.aquagrid.platform.security.config.PublicEndpoint;
import com.aquagrid.platform.security.config.PublicEndpointProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The receiver's unauthenticated surface — one route, method-scoped.
 *
 * <p>"Public" here means only that Spring Security does not require a JWT, and it must, because the
 * callers are gateways and meters that have no user session and cannot obtain one. It does not mean
 * unauthenticated: every packet is authenticated inside the pipeline by
 * {@code ReceiverAuthenticationService}, against schemes devices can actually use — API keys,
 * device tokens, HMAC signatures. Bypassing the filter chain is what lets the receiver apply a
 * device-appropriate credential model instead of forcing hardware to speak OAuth.
 *
 * <p>Scoped to {@code POST} on the ingress path and nothing else, following the pattern identity
 * established: {@code /receiver/**} would be far easier to write and would also expose the packet
 * search, the transport statistics and the replay endpoint to the world. Those live under the same
 * prefix and every one of them stays behind {@code @PreAuthorize}.
 *
 * <p>The route is contributed unconditionally rather than per enabled transport. The controller
 * answers {@code 501} for a transport with no receiver bean, so an unlisted technology is refused
 * by the application with an explanation rather than by the filter chain with a 401 — which is the
 * difference between a gateway operator diagnosing their integration in a minute and in an
 * afternoon.
 */
@Component
public class ReceiverPublicEndpoints implements PublicEndpointProvider {

    @Override
    public List<PublicEndpoint> publicEndpoints() {
        return List.of(PublicEndpoint.post(ReceiverModuleConfig.INGRESS_BASE + "/*"));
    }
}
