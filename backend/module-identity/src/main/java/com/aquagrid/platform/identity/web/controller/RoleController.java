package com.aquagrid.platform.identity.web.controller;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.common.web.ClientIpResolver;
import com.aquagrid.platform.identity.application.service.RoleManagementService;
import com.aquagrid.platform.identity.web.dto.UserManagementResponses;
import com.aquagrid.platform.security.core.Permissions;
import com.aquagrid.platform.security.core.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Role catalogue API (Module 2).
 *
 * <p>System roles are readable by anyone with {@code identity:role:read} but only tenant-authored
 * roles are writable, and only by {@code identity:role:manage}. The immutability of system roles is
 * enforced in the service via {@link com.aquagrid.platform.identity.domain.policy.RolePolicy}.
 */
@Tag(name = "Roles", description = "Role and permission catalogue")
@RestController
@RequestMapping(value = ApiPaths.ROLES, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class RoleController {

    private final RoleManagementService roleManagementService;
    private final ClientIpResolver clientIpResolver;

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List roles visible to the current tenant",
            description = "System roles (shared) plus this tenant's custom roles.")
    public List<UserManagementResponses.RoleSummary> listRoles() {
        return roleManagementService.listVisibleRoles(SecurityUtils.requirePrincipal().organizationId());
    }

    @GetMapping("/{roleId}")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get a role with its permissions")
    public UserManagementResponses.RoleDetail getRole(@PathVariable UUID roleId) {
        return roleManagementService.getRole(roleId,
                SecurityUtils.requirePrincipal().organizationId());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create a tenant-authored role")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Role created"),
            @ApiResponse(responseCode = "409", description = "Role code already in use")})
    @ResponseStatus(HttpStatus.CREATED)
    public UserManagementResponses.RoleDetail createRole(@Valid @RequestBody RoleRequest request,
                                                         HttpServletRequest httpRequest) {
        var principal = SecurityUtils.requirePrincipal();
        return roleManagementService.createRole(principal.organizationId(), principal.userId(),
                clientIpResolver.resolve(httpRequest), request.code(), request.name(),
                request.description(), request.permissions());
    }

    @PutMapping(value = "/{roleId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Update a tenant-authored role",
            description = "System roles cannot be modified. Any field may be omitted to leave it unchanged.")
    public UserManagementResponses.RoleDetail updateRole(@PathVariable UUID roleId,
                                                         @Valid @RequestBody RoleRequest request,
                                                         HttpServletRequest httpRequest) {
        var principal = SecurityUtils.requirePrincipal();
        return roleManagementService.updateRole(roleId, principal.organizationId(), principal.userId(),
                clientIpResolver.resolve(httpRequest), request.name(), request.description(),
                request.permissions());
    }

    @DeleteMapping("/{roleId}")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Delete a tenant-authored role",
            description = "Refused while the role is assigned to any user.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRole(@PathVariable UUID roleId, HttpServletRequest httpRequest) {
        var principal = SecurityUtils.requirePrincipal();
        roleManagementService.deleteRole(roleId, principal.organizationId(), principal.userId(),
                clientIpResolver.resolve(httpRequest));
    }

    /** Request body for create/update role. */
    @io.swagger.v3.oas.annotations.media.Schema(name = "RoleRequest")
    public record RoleRequest(
            @Size(max = 60) String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 300) String description,
            Set<String> permissions
    ) {
    }
}
