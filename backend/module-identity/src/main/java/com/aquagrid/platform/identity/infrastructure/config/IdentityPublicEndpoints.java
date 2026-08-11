package com.aquagrid.platform.identity.infrastructure.config;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.security.config.PublicEndpoint;
import com.aquagrid.platform.security.config.PublicEndpointProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The identity module's unauthenticated surface — deliberately small, and enumerated in one place
 * so it can be reviewed as a whole.
 *
 * <p>Every entry is method-scoped rather than path-scoped. {@code /auth/**} would be far easier to
 * write and would also expose {@code /auth/sessions} and {@code /auth/logout-all} to the world.
 *
 * <p>{@code /auth/mfa/challenge} is public because the caller is not yet authenticated — it
 * authenticates itself with the short-lived {@code mfaToken} in its body, which the endpoint
 * validates directly.
 */
@Component
public class IdentityPublicEndpoints implements PublicEndpointProvider {

    @Override
    public List<PublicEndpoint> publicEndpoints() {
        return List.of(
                PublicEndpoint.post(ApiPaths.AUTH + "/login"),
                PublicEndpoint.post(ApiPaths.AUTH + "/mfa/challenge"),
                PublicEndpoint.post(ApiPaths.AUTH + "/refresh"),
                PublicEndpoint.post(ApiPaths.AUTH + "/logout"),
                PublicEndpoint.post(ApiPaths.AUTH + "/password/forgot"),
                PublicEndpoint.post(ApiPaths.AUTH + "/password/reset"),
                PublicEndpoint.get(ApiPaths.AUTH + "/password/policy"),
                PublicEndpoint.get(ApiPaths.WELL_KNOWN + "/jwks.json"));
    }
}
