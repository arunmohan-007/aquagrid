package com.aquagrid.platform.identity.domain.policy;

import com.aquagrid.platform.identity.domain.model.Role;

import java.util.regex.Pattern;

/**
 * Pure domain rules governing roles.
 *
 * <p>No Spring, no JPA, no I/O — testable with plain JUnit in milliseconds. These are the rules
 * that an administrator editing a role can break, so they are enforced once here and trusted
 * everywhere above. The entity's CHECK constraints are the last line of defence; this class is the
 * first, and gives meaningful error codes rather than a {@code DataIntegrityViolationException}.
 */
public final class RolePolicy {

    /** Mirrors the {@code ck_roles_code_format} CHECK constraint so failure is reported, not 500'd. */
    public static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{1,59}$");

    /** Minimum meaningful permission count; a role with none grants nothing and is almost always a mistake. */
    public static final int MIN_PERMISSIONS = 1;

    private RolePolicy() {
    }

    /** System roles ({@code is_system == true}) are immutable data shipped with the product. */
    public static boolean isModifiable(Role role) {
        return role != null && !role.isSystem();
    }

    /** Validates a tenant-authored role code against the same format the database enforces. */
    public static boolean isValidCode(String code) {
        return code != null && CODE_PATTERN.matcher(code).matches();
    }

    /**
     * A custom role must belong to a tenant and must not be flagged system. The inverse combination
     * (system role belonging to a tenant) is rejected by the DB; this keeps it out of the API.
     */
    public static boolean isTenantRole(Role role, java.util.UUID organizationId) {
        return role != null && !role.isSystem()
                && organizationId != null && organizationId.equals(role.getOrganizationId());
    }
}
