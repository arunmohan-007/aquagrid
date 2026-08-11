package com.aquagrid.platform.common.config;

import java.util.Optional;
import java.util.UUID;

/**
 * Supplies the identity of whoever is performing the current unit of work.
 *
 * <p>Declared in the kernel and implemented in {@code platform-security}. This inversion is what
 * lets {@code platform-common} populate {@code created_by}/{@code updated_by} without taking a
 * compile-time dependency on Spring Security — keeping the kernel usable from batch jobs, message
 * listeners and tests that have no security context.
 */
public interface CurrentActorProvider {

    Optional<UUID> currentUserId();

    Optional<String> currentUsername();
}
