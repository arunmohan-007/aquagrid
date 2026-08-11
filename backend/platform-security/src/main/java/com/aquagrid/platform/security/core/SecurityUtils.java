package com.aquagrid.platform.security.core;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

/** Convenience accessors for the authenticated caller. */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Optional<AuthenticatedPrincipal> currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return authentication.getPrincipal() instanceof AuthenticatedPrincipal principal
                ? Optional.of(principal)
                : Optional.empty();
    }

    /** @throws BusinessException with {@code AUTH_REQUIRED} when there is no authenticated caller. */
    public static AuthenticatedPrincipal requirePrincipal() {
        return currentPrincipal()
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
    }

    public static Optional<UUID> currentUserId() {
        return currentPrincipal().map(AuthenticatedPrincipal::userId);
    }

    public static Optional<UUID> currentOrganizationId() {
        return currentPrincipal().map(AuthenticatedPrincipal::organizationId);
    }
}
