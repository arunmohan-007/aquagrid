package com.aquagrid.platform.security.core;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller, reconstructed from the access token.
 *
 * <p>Immutable and free of JPA: application services depend on this, never on the {@code User}
 * entity, so the identity module can be extracted without every other module following it.
 */
public record AuthenticatedPrincipal(
        UUID userId,
        String username,
        String email,
        String fullName,
        UUID organizationId,
        String organizationCode,
        Set<String> roles,
        Set<String> permissions,
        UUID sessionId,
        boolean mustChangePassword
) {
    public AuthenticatedPrincipal {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public boolean hasPermission(String permissionCode) {
        return permissions.contains(permissionCode);
    }

    public boolean hasRole(String roleCode) {
        return roles.contains(roleCode);
    }
}
