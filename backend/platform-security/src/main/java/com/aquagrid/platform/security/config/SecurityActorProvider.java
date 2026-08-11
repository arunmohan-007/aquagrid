package com.aquagrid.platform.security.config;

import com.aquagrid.platform.common.config.CurrentActorProvider;
import com.aquagrid.platform.security.core.AuthenticatedPrincipal;
import com.aquagrid.platform.security.core.SecurityUtils;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Bridges the kernel's {@link CurrentActorProvider} abstraction to Spring Security, so JPA auditing
 * can stamp {@code created_by}/{@code updated_by} without the kernel depending on Spring Security.
 */
@Component
public class SecurityActorProvider implements CurrentActorProvider {

    @Override
    public Optional<UUID> currentUserId() {
        return SecurityUtils.currentPrincipal().map(AuthenticatedPrincipal::userId);
    }

    @Override
    public Optional<String> currentUsername() {
        return SecurityUtils.currentPrincipal().map(AuthenticatedPrincipal::username);
    }
}
