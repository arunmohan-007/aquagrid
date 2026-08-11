package com.aquagrid.platform.identity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The identity module's published contract.
 *
 * <p>This is the <b>only</b> package other modules may import. GIS, IoT and operations modules need
 * to resolve "who is user X" to render an assignee or an audit line; they must not do so by
 * injecting {@code UserRepository} or by joining to {@code identity.users} in their own queries.
 * Routing that need through a narrow interface is what keeps the identity module extractable into
 * its own service without a platform-wide refactor — the implementation can become an HTTP client
 * behind this same signature.
 */
public interface IdentityApi {

    Optional<UserSummary> findUser(UUID userId);

    boolean hasPermission(UUID userId, String permissionCode);

    /**
     * Resolves a tenant by its login code, e.g. {@code KWA-TVM}.
     *
     * <p>Published so other modules (the simulator, future notification routing) can resolve a
     * tenant without depending on identity's persistence layer. Returns the tenant id and its
     * timezone/locale defaults — the minimum a consumer needs.
     */
    Optional<TenantSummary> findTenantByCode(String code);
}
