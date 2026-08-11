package com.aquagrid.platform.identity.web.controller;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.common.web.ClientIpResolver;
import com.aquagrid.platform.common.web.PageResponse;
import com.aquagrid.platform.identity.application.command.UserManagementCommands;
import com.aquagrid.platform.identity.application.service.UserManagementService;
import com.aquagrid.platform.identity.domain.enums.UserStatus;
import com.aquagrid.platform.identity.web.dto.UserManagementResponses;
import com.aquagrid.platform.security.core.Permissions;
import com.aquagrid.platform.security.core.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

/**
 * User administration API (Module 2).
 *
 * <p>Every endpoint is tenant-scoped: the tenant comes from the authenticated principal's JWT, never
 * from the request, so one tenant can never address another tenant's users. Each mutation is
 * audited and guarded against self-modification where that would create a privilege or lockout hole.
 *
 * <p>The invitation acceptance endpoint is the single public method here — the invitee is not yet
 * authenticated, so it sits on {@code /users/invitations/accept} without a {@code @PreAuthorize}.
 */
@Tag(name = "Users", description = "User and invitation administration")
@RestController
@RequestMapping(value = ApiPaths.USERS, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class UserController {

    private final UserManagementService userManagementService;
    private final ClientIpResolver clientIpResolver;

    // --- Listing & lookup -----------------------------------------------------------------------

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.USER_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List users in the current tenant")
    public PageResponse<UserManagementResponses.UserSummary> listUsers(
            @Parameter(description = "Filter by status") @RequestParam(required = false) String status,
            @Parameter(description = "Substring match on name, email or username")
            @RequestParam(required = false) String search,
            @PageableDefault(size = 25) Pageable pageable) {

        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        UserStatus statusFilter = status == null ? null : parseStatus(status);
        return PageResponse.of(
                userManagementService.listUsers(orgId, statusFilter, search, pageable));
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get a user's detail")
    public UserManagementResponses.UserDetail getUser(@PathVariable UUID userId) {
        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        return userManagementService.getUser(userId, orgId);
    }

    // --- Create / update / delete ---------------------------------------------------------------

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.USER_CREATE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create a user",
            description = "The created account has no usable password until an administrator sets one "
                    + "or issues an invitation. Prefer invitations — they avoid transmitting a password.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User created"),
            @ApiResponse(responseCode = "409", description = "Username or email already in use")})
    @ResponseStatus(HttpStatus.CREATED)
    public UserManagementResponses.UserDetail createUser(@Valid @RequestBody UserManagementCommands.CreateUser request,
                                                         HttpServletRequest httpRequest) {
        var principal = SecurityUtils.requirePrincipal();
        return userManagementService.createUser(principal.organizationId(), principal.userId(),
                clientIpResolver.resolve(httpRequest), request);
    }

    @PatchMapping(value = "/{userId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Update a user's profile")
    public UserManagementResponses.UserDetail updateUser(@PathVariable UUID userId,
                                                        @Valid @RequestBody UserManagementCommands.UpdateUser request,
                                                        HttpServletRequest httpRequest) {
        var principal = SecurityUtils.requirePrincipal();
        return userManagementService.updateUser(userId, principal.organizationId(), principal.userId(),
                clientIpResolver.resolve(httpRequest), request);
    }

    @PutMapping(value = "/{userId}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Change a user's status",
            description = "Disabling or locking a user revokes every active session immediately.")
    public UserManagementResponses.UserDetail changeStatus(@PathVariable UUID userId,
                                                           @Valid @RequestBody UserManagementCommands.ChangeUserStatus request,
                                                           HttpServletRequest httpRequest) {
        var principal = SecurityUtils.requirePrincipal();
        return userManagementService.changeStatus(userId, principal.organizationId(), principal.userId(),
                clientIpResolver.resolve(httpRequest), parseStatus(request.status()));
    }

    @PutMapping(value = "/{userId}/roles", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Set the user's roles",
            description = "The request is the complete target set: roles held but not listed are removed. "
                    + "An administrator cannot change their own roles.")
    public UserManagementResponses.UserDetail assignRoles(@PathVariable UUID userId,
                                                          @Valid @RequestBody UserManagementCommands.AssignRoles request,
                                                          HttpServletRequest httpRequest) {
        var principal = SecurityUtils.requirePrincipal();
        Set<String> codes = request.roleCodes() == null ? Set.of() : request.roleCodes();
        return userManagementService.assignRoles(userId, principal.organizationId(), principal.userId(),
                clientIpResolver.resolve(httpRequest), codes);
    }

    @PutMapping(value = "/{userId}/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Reset a user's password (administrator)",
            description = "All of the user's sessions are revoked. The password policy is enforced.")
    public UserManagementResponses.Message adminResetPassword(@PathVariable UUID userId,
                                                             @Valid @RequestBody UserManagementCommands.AdminResetPassword request,
                                                             HttpServletRequest httpRequest) {
        var principal = SecurityUtils.requirePrincipal();
        return userManagementService.adminResetPassword(userId, principal.organizationId(),
                principal.userId(), clientIpResolver.resolve(httpRequest), request.newPassword(),
                request.mustChangePassword());
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_DELETE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Delete a user",
            description = "Hard delete. An administrator cannot delete their own account.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable UUID userId, HttpServletRequest httpRequest) {
        var principal = SecurityUtils.requirePrincipal();
        userManagementService.deleteUser(userId, principal.organizationId(), principal.userId(),
                clientIpResolver.resolve(httpRequest));
    }

    // --- Invitations ----------------------------------------------------------------------------

    @GetMapping("/invitations")
    @PreAuthorize("hasAuthority('" + Permissions.USER_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List outstanding and consumed invitations")
    public java.util.List<UserManagementResponses.InvitationSummary> listInvitations() {
        return userManagementService.listInvitations(SecurityUtils.requirePrincipal().organizationId());
    }

    @PostMapping(value = "/invitations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.USER_CREATE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Invite a user",
            description = """
                    Returns an opaque token exactly once. It is never retrievable again — only its
                    SHA-256 hash is stored. The notification centre (Module 20) delivers it; until then
                    the inviter must convey it securely.""")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<UserManagementResponses.InvitationCreated> invite(
            @Valid @RequestBody UserManagementCommands.InviteUser request,
            HttpServletRequest httpRequest) {
        var principal = SecurityUtils.requirePrincipal();
        String token = userManagementService.invite(principal.organizationId(), principal.userId(),
                clientIpResolver.resolve(httpRequest), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new UserManagementResponses.InvitationCreated(token,
                        java.time.Instant.now().plus(userManagementService.invitationTtl())));
    }

    @DeleteMapping("/invitations/{invitationId}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Revoke an invitation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvitation(@PathVariable UUID invitationId, HttpServletRequest httpRequest) {
        var principal = SecurityUtils.requirePrincipal();
        userManagementService.revokeInvitation(invitationId, principal.organizationId(),
                principal.userId(), clientIpResolver.resolve(httpRequest));
    }

    @PostMapping(value = "/invitations/accept", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Accept an invitation and set the initial password",
            description = "Public: the invitee is not yet authenticated. The token is single-use and "
                    + "short-lived; on success the user can sign in immediately.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account activated"),
            @ApiResponse(responseCode = "400", description = "Token invalid, expired or revoked")})
    public UserManagementResponses.Message acceptInvitation(
            @Valid @RequestBody UserManagementCommands.AcceptInvitation request,
            HttpServletRequest httpRequest) {
        return userManagementService.activate(request.token(), request.password(),
                clientIpResolver.resolve(httpRequest));
    }

    // --- Helpers --------------------------------------------------------------------------------

    private UserStatus parseStatus(String status) {
        try {
            return UserStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Unknown status. Valid values: PENDING, ACTIVE, DISABLED, LOCKED.");
        }
    }
}
