package com.aquagrid.platform.identity.application.service;

import com.aquagrid.platform.common.audit.AuditCategory;
import com.aquagrid.platform.common.audit.AuditEvent;
import com.aquagrid.platform.common.audit.AuditEventTypes;
import com.aquagrid.platform.common.audit.AuditService;
import com.aquagrid.platform.common.audit.AuditSeverity;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.identity.domain.model.Permission;
import com.aquagrid.platform.identity.domain.model.Role;
import com.aquagrid.platform.identity.domain.policy.RolePolicy;
import com.aquagrid.platform.identity.infrastructure.persistence.PermissionRepository;
import com.aquagrid.platform.identity.infrastructure.persistence.RoleRepository;
import com.aquagrid.platform.identity.infrastructure.persistence.UserRepository;
import com.aquagrid.platform.identity.web.dto.UserManagementResponses;
import com.aquagrid.platform.security.core.Permissions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Role catalogue administration: list, create, update, delete tenant-authored roles.
 *
 * <p>System roles ({@code is_system == true}) are product data shared by every tenant. They are
 * readable through this service but never writable: a customer must not be able to widen
 * {@code SUPER_ADMIN} by editing the role the platform shipped. This is enforced by
 * {@link RolePolicy} and double-checked against the {@code ROLE_IS_SYSTEM} error.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleManagementService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<UserManagementResponses.RoleSummary> listVisibleRoles(UUID organizationId) {
        return roleRepository.findVisibleToTenant(organizationId).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserManagementResponses.RoleDetail getRole(UUID roleId, UUID organizationId) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROLE_NOT_FOUND));
        assertVisible(role, organizationId);
        return toDetail(role);
    }

    @Transactional
    public UserManagementResponses.RoleDetail createRole(UUID organizationId, UUID actorId, String clientIp,
                                                         String code, String name, String description,
                                                         Set<String> permissionCodes) {
        if (!RolePolicy.isValidCode(code)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Role code must be uppercase letters, digits or underscores, 2–60 characters.");
        }
        if (roleRepository.existsByCodeAndOrganizationId(code, organizationId)) {
            throw new BusinessException(ErrorCode.ROLE_CODE_TAKEN);
        }
        Set<Permission> permissions = resolvePermissions(permissionCodes);

        Role role = new Role();
        role.setOrganizationId(organizationId);
        role.setCode(code);
        role.setName(name);
        role.setDescription(description);
        role.setSystem(false);
        role.setPermissions(permissions);
        roleRepository.save(role);

        audit(organizationId, actorId, AuditEventTypes.ROLE_CREATED, role,
                "Role '%s' created with %d permission(s)".formatted(code, permissions.size()), true, clientIp);
        return toDetail(role);
    }

    @Transactional
    public UserManagementResponses.RoleDetail updateRole(UUID roleId, UUID organizationId, UUID actorId,
                                                         String clientIp, String name, String description,
                                                         Set<String> permissionCodes) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROLE_NOT_FOUND));
        if (!RolePolicy.isModifiable(role)) {
            throw new BusinessException(ErrorCode.ROLE_IS_SYSTEM);
        }
        assertVisible(role, organizationId);

        if (name != null && !name.isBlank()) {
            role.setName(name);
        }
        if (description != null) {
            role.setDescription(description.isBlank() ? null : description);
        }
        if (permissionCodes != null) {
            role.setPermissions(resolvePermissions(permissionCodes));
        }

        audit(organizationId, actorId, AuditEventTypes.ROLE_UPDATED, role,
                "Role '%s' updated".formatted(role.getCode()), true, clientIp);
        return toDetail(role);
    }

    /**
     * Deletes a tenant-authored role, provided no user currently holds it.
     *
     * <p>{@code ON DELETE CASCADE} on {@code role_permissions} cleans the grants. The
     * {@code user_roles} join is the live dependency that blocks deletion: silently stripping a role
     * from active users would change their effective permissions without notice.
     */
    @Transactional
    public void deleteRole(UUID roleId, UUID organizationId, UUID actorId, String clientIp) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROLE_NOT_FOUND));
        if (!RolePolicy.isModifiable(role)) {
            throw new BusinessException(ErrorCode.ROLE_IS_SYSTEM);
        }
        assertVisible(role, organizationId);

        long holders = userRepository.countByRole(role.getId());
        if (holders > 0) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT,
                    "Role is assigned to %d user(s) and cannot be deleted. Remove the assignments first."
                            .formatted(holders));
        }

        String code = role.getCode();
        roleRepository.delete(role);
        audit(organizationId, actorId, AuditEventTypes.ROLE_DELETED, role,
                "Role '%s' deleted".formatted(code), true, clientIp);
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private Set<Permission> resolvePermissions(Set<String> codes) {
        if (codes == null || codes.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A role must grant at least one permission.");
        }
        Set<Permission> found = new LinkedHashSet<>(permissionRepository.findAllByCodeIn(codes));
        if (found.size() != codes.size()) {
            Set<String> resolved = new TreeSet<>();
            found.forEach(p -> resolved.add(p.getCode()));
            Set<String> unknown = new TreeSet<>(codes);
            unknown.removeAll(resolved);
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Unknown permission code(s): " + String.join(", ", unknown));
        }
        return found;
    }

    private void assertVisible(Role role, UUID organizationId) {
        // System roles are visible to everyone; custom roles only to their owning tenant.
        if (!role.isSystem() && !organizationId.equals(role.getOrganizationId())) {
            throw new BusinessException(ErrorCode.ROLE_NOT_FOUND);
        }
    }

    private UserManagementResponses.RoleSummary toSummary(Role role) {
        return UserManagementResponses.RoleSummary.builder()
                .id(role.getId())
                .code(role.getCode())
                .name(role.getName())
                .description(role.getDescription())
                .system(role.isSystem())
                .permissionCount(role.getPermissions().size())
                .build();
    }

    private UserManagementResponses.RoleDetail toDetail(Role role) {
        return UserManagementResponses.RoleDetail.builder()
                .id(role.getId())
                .code(role.getCode())
                .name(role.getName())
                .description(role.getDescription())
                .system(role.isSystem())
                .permissions(role.getPermissions().stream()
                        .map(Permission::getCode)
                        .sorted()
                        .toList())
                .build();
    }

    private void audit(UUID orgId, UUID actorId, String type, Role role, String message,
                       boolean success, String clientIp) {
        auditService.record(AuditEvent.builder()
                .organizationId(orgId)
                .actorUserId(actorId)
                .eventType(type)
                .category(AuditCategory.AUTHORIZATION)
                .severity(AuditSeverity.INFO)
                .resourceType("Role")
                .resourceId(role.getId().toString())
                .success(success)
                .message(message)
                .clientIp(clientIp)
                .metadata(java.util.Map.of("roleCode", role.getCode()))
                .build());
    }

    /** Reference to the {@code ROLE_MANAGE} permission, for controllers that prefer symbolic checks. */
    public static final String REQUIRED_PERMISSION = Permissions.ROLE_MANAGE;
}
