package com.aquagrid.platform.identity.domain.policy;

import com.aquagrid.platform.identity.domain.model.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the role policy. No Spring, no database — these run in milliseconds and lock
 * the rules that an administrator editing a role can break.
 */
class RolePolicyTest {

    @Test
    @DisplayName("a system role is never modifiable")
    void systemRoleIsImmutable() {
        Role role = role("ZONE_SUPERVISOR", true, null);
        assertThat(RolePolicy.isModifiable(role)).isFalse();
    }

    @Test
    @DisplayName("a tenant-authored role is modifiable")
    void tenantRoleIsModifiable() {
        UUID org = UUID.randomUUID();
        Role role = role("ZONE_SUPERVISOR", false, org);
        assertThat(RolePolicy.isModifiable(role)).isTrue();
    }

    @Test
    @DisplayName("accepts a well-formed role code")
    void acceptsValidCode() {
        assertThat(RolePolicy.isValidCode("ZONE_SUPERVISOR")).isTrue();
        assertThat(RolePolicy.isValidCode("A1")).isTrue();
        assertThat(RolePolicy.isValidCode("MUNICIPAL_READ_ONLY_2")).isTrue();
    }

    @Test
    @DisplayName("rejects malformed role codes the way the database CHECK would")
    void rejectsInvalidCode() {
        assertThat(RolePolicy.isValidCode(null)).isFalse();
        assertThat(RolePolicy.isValidCode("")).isFalse();
        assertThat(RolePolicy.isValidCode("lowercase")).isFalse();      // must start uppercase
        assertThat(RolePolicy.isValidCode("1STARTS_WITH_DIGIT")).isFalse();
        assertThat(RolePolicy.isValidCode("HAS-DASH")).isFalse();        // only _ is allowed
        assertThat(RolePolicy.isValidCode("HAS SPACE")).isFalse();
        assertThat(RolePolicy.isValidCode("A")).isFalse();               // too short (needs 2+)
    }

    @Test
    @DisplayName("a custom role is a tenant role only when it is not system and belongs to the tenant")
    void tenantRoleBelongsToTenant() {
        UUID org = UUID.randomUUID();
        assertThat(RolePolicy.isTenantRole(role("R", false, org), org)).isTrue();
        // system role, even matching org, is not a tenant role
        assertThat(RolePolicy.isTenantRole(role("SUPER_ADMIN", true, null), org)).isFalse();
        // custom role belonging to a different tenant
        assertThat(RolePolicy.isTenantRole(role("R", false, UUID.randomUUID()), org)).isFalse();
    }

    private Role role(String code, boolean system, UUID organizationId) {
        Role role = new Role();
        role.setCode(code);
        role.setSystem(system);
        role.setOrganizationId(organizationId);
        return role;
    }
}
