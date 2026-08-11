package com.aquagrid.platform.common.tenant;

import java.util.Optional;
import java.util.UUID;

/**
 * Holds the tenant of the current unit of work.
 *
 * <p>Populated by the security layer from the {@code org} JWT claim and cleared unconditionally at
 * the end of the request. Kept as a {@link ThreadLocal} rather than a request-scoped bean so that
 * it is also available to {@code @Async} work, scheduled jobs and message listeners, which have no
 * HTTP request.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID organizationId) {
        CURRENT.set(organizationId);
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** @throws IllegalStateException when invoked outside a tenant-scoped unit of work. */
    public static UUID require() {
        UUID value = CURRENT.get();
        if (value == null) {
            throw new IllegalStateException("No tenant bound to the current thread");
        }
        return value;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Runs {@code action} under an explicit tenant, restoring the previous one afterwards. */
    public static void runAs(UUID organizationId, Runnable action) {
        UUID previous = CURRENT.get();
        try {
            CURRENT.set(organizationId);
            action.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
