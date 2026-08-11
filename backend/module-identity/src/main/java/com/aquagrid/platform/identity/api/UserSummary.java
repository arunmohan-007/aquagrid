package com.aquagrid.platform.identity.api;

import java.util.UUID;

/**
 * The minimum a consuming module needs to display a user.
 *
 * <p>Deliberately anaemic: no password state, no MFA state, no roles. A module that renders "last
 * modified by" has no business receiving credential metadata, and a narrow contract is what makes
 * the eventual extraction of this module a serialisation exercise rather than a redesign.
 */
public record UserSummary(
        UUID id,
        UUID organizationId,
        String username,
        String email,
        String fullName,
        String jobTitle,
        String avatarUrl,
        boolean active
) {
}
