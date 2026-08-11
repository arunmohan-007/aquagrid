package com.aquagrid.platform.identity.api;

import java.util.UUID;

/**
 * The minimum a consuming module needs to know about a tenant.
 *
 * <p>Companion to {@link UserSummary}: narrow by design, so widening the contract is a deliberate
 * act and the identity module stays extractable. Carries the tenant's locale defaults because the
 * simulator and the notification centre both need them to produce local-clock-correct traffic and
 * to render times in the tenant's timezone.
 */
public record TenantSummary(
        UUID id,
        String code,
        String name,
        String timezone,
        String locale
) {
}
