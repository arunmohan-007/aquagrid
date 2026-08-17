package com.aquagrid.platform.identity.application.service;

import com.aquagrid.platform.common.audit.AuditCategory;
import com.aquagrid.platform.common.audit.AuditEvent;
import com.aquagrid.platform.common.audit.AuditEventTypes;
import com.aquagrid.platform.common.audit.AuditService;
import com.aquagrid.platform.common.audit.AuditSeverity;
import com.aquagrid.platform.common.crypto.Hashes;
import com.aquagrid.platform.common.crypto.TokenGenerator;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.common.error.ResourceNotFoundException;
import com.aquagrid.platform.identity.application.command.UserManagementCommands;
import com.aquagrid.platform.identity.application.mapper.UserMapper;
import com.aquagrid.platform.identity.domain.enums.TokenRevocationReason;
import com.aquagrid.platform.identity.domain.enums.UserStatus;
import com.aquagrid.platform.identity.domain.model.Organization;
import com.aquagrid.platform.identity.domain.model.Role;
import com.aquagrid.platform.identity.domain.model.User;
import com.aquagrid.platform.identity.domain.model.UserInvitation;
import com.aquagrid.platform.identity.domain.policy.PasswordPolicy;
import com.aquagrid.platform.identity.infrastructure.persistence.OrganizationRepository;
import com.aquagrid.platform.identity.infrastructure.persistence.RoleRepository;
import com.aquagrid.platform.identity.infrastructure.persistence.UserInvitationRepository;
import com.aquagrid.platform.identity.infrastructure.persistence.UserRepository;
import com.aquagrid.platform.identity.web.dto.UserManagementResponses;
import com.aquagrid.platform.security.jwt.JwtProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * User administration: create, list, update, status, role assignment, admin password reset,
 * invitations and invitation activation.
 *
 * <p>Every mutation is tenant-scoped (the {@code organizationId} comes from the authenticated
 * principal, never from the request body), audited, and guarded against self-modification where
 * that would create a privilege hole (an admin cannot disable or delete their own account, or strip
 * their own roles — that is the classic way an admin locks themselves out or, worse, escalates by
 * accident during a late-night change).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserManagementService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final OrganizationRepository organizationRepository;
    private final UserInvitationRepository invitationRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordService passwordService;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;
    private final UserMapper userMapper;
    private final JwtProperties jwtProperties;
    private final Clock clock;
    private final CacheManager cacheManager;

    /** Issued to every admin-created account; {@code mustChangePassword} forces a change before use. */
    private static final String DEFAULT_PASSWORD = "123456";

    // --- Listing & lookup -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<UserManagementResponses.UserSummary> listUsers(UUID organizationId, UserStatus status,
                                                               String search, Pageable pageable) {
        Page<User> page = userRepository.findForTenant(organizationId, status, search, pageable);
        return page.map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public UserManagementResponses.UserDetail getUser(UUID userId, UUID organizationId) {
        User user = requireUserInTenant(userId, organizationId);
        return toDetail(user);
    }

    // --- Create ---------------------------------------------------------------------------------

    @Transactional
    public UserManagementResponses.UserDetail createUser(UUID organizationId, UUID actorId, String clientIp,
                                                         UserManagementCommands.CreateUser command) {
        assertUniqueUsername(organizationId, command.username(), null);
        assertUniqueEmail(command.email(), null);

        Organization org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_RESOLVED));

        User user = new User();
        user.setOrganization(org);
        user.setUsername(command.username());
        user.setEmail(command.email());
        user.setFullName(command.fullName());
        user.setJobTitle(command.jobTitle());
        user.setPhone(command.phone());
        // The account ships with a known default password. mustChangePassword forces the user to
        // replace it at first sign-in, so the window in which the default is valid is exactly one
        // login.
        user.setPasswordHash(passwordEncoder.encode(DEFAULT_PASSWORD));
        user.setPasswordUpdatedAt(clock.instant());
        user.setMustChangePassword(true);
        user.setStatus(UserStatus.ACTIVE);

        if (command.roleCodes() != null && !command.roleCodes().isEmpty()) {
            user.setRoles(resolveRoles(organizationId, command.roleCodes()));
        }
        userRepository.save(user);

        audit(org.getId(), actorId, AuditEventTypes.USER_CREATED, user,
                "User '%s' created".formatted(user.getUsername()), true, clientIp);
        return toDetail(user);
    }

    // --- Update ---------------------------------------------------------------------------------

    @Transactional
    public UserManagementResponses.UserDetail updateUser(UUID userId, UUID organizationId, UUID actorId,
                                                         String clientIp,
                                                         UserManagementCommands.UpdateUser command) {
        User user = requireUserInTenant(userId, organizationId);
        if (command.fullName() != null && !command.fullName().isBlank()) {
            user.setFullName(command.fullName());
        }
        if (command.jobTitle() != null) {
            user.setJobTitle(command.jobTitle().isBlank() ? null : command.jobTitle());
        }
        if (command.phone() != null) {
            user.setPhone(command.phone().isBlank() ? null : command.phone());
        }
        if (command.avatarUrl() != null) {
            user.setAvatarUrl(command.avatarUrl().isBlank() ? null : command.avatarUrl());
        }
        if (command.timezone() != null) {
            user.setTimezone(command.timezone().isBlank() ? null : command.timezone());
        }
        if (command.locale() != null) {
            user.setLocale(command.locale().isBlank() ? null : command.locale());
        }

        audit(organizationId, actorId, AuditEventTypes.USER_UPDATED, user,
                "User '%s' profile updated".formatted(user.getUsername()), true, clientIp);
        return toDetail(user);
    }

    // --- Status ---------------------------------------------------------------------------------

    @Transactional
    public UserManagementResponses.UserDetail changeStatus(UUID userId, UUID organizationId, UUID actorId,
                                                           String clientIp, UserStatus newStatus) {
        User user = requireUserInTenant(userId, organizationId);
        if (userId.equals(actorId)) {
            throw new BusinessException(ErrorCode.CANNOT_MODIFY_SELF_ROLE,
                    "You cannot change your own account status.");
        }
        if (user.getStatus() == newStatus) {
            return toDetail(user);
        }
        user.setStatus(newStatus);
        // Disabling or locking an account evicts active sessions immediately — a suspended user
        // must not keep working on a token minted before the suspension.
        if (newStatus == UserStatus.DISABLED || newStatus == UserStatus.LOCKED) {
            int revoked = refreshTokenService.revokeAllForUser(userId, TokenRevocationReason.ADMIN_REVOKED);
            log.info("Status change to {} for user {} revoked {} session(s)", newStatus, userId, revoked);
        }
        evictUserPermissionCache(userId);

        audit(organizationId, actorId, AuditEventTypes.USER_STATUS_CHANGED, user,
                "Status set to %s".formatted(newStatus), true, clientIp);
        return toDetail(user);
    }

    @Transactional
    public void deleteUser(UUID userId, UUID organizationId, UUID actorId, String clientIp) {
        User user = requireUserInTenant(userId, organizationId);
        if (userId.equals(actorId)) {
            throw new BusinessException(ErrorCode.CANNOT_DELETE_SELF);
        }
        refreshTokenService.revokeAllForUser(userId, TokenRevocationReason.ADMIN_REVOKED);
        userRepository.delete(user);
        audit(organizationId, actorId, AuditEventTypes.USER_DELETED, user,
                "User '%s' deleted".formatted(user.getUsername()), true, clientIp);
    }

    // --- Roles ----------------------------------------------------------------------------------

    @Transactional
    public UserManagementResponses.UserDetail assignRoles(UUID userId, UUID organizationId, UUID actorId,
                                                          String clientIp, Set<String> roleCodes) {
        User user = requireUserInTenant(userId, organizationId);
        if (userId.equals(actorId)) {
            throw new BusinessException(ErrorCode.CANNOT_MODIFY_SELF_ROLE,
                    "You cannot change your own roles. Ask another administrator.");
        }
        user.setRoles(resolveRoles(organizationId, roleCodes));
        evictUserPermissionCache(userId);

        audit(organizationId, actorId, AuditEventTypes.USER_ROLES_ASSIGNED, user,
                "Roles set to [%s]".formatted(String.join(", ", roleCodes)), true, clientIp);
        return toDetail(user);
    }

    // --- Admin password reset ------------------------------------------------------------------

    @Transactional
    public UserManagementResponses.Message adminResetPassword(UUID userId, UUID organizationId, UUID actorId,
                                                              String clientIp, String newPassword,
                                                              boolean mustChangePassword) {
        User user = requireUserInTenant(userId, organizationId);
        validatePasswordPolicy(user, newPassword);
        Instant now = clock.instant();
        user.applyNewPassword(passwordEncoder.encode(newPassword), now);
        user.setMustChangePassword(mustChangePassword);
        int revoked = refreshTokenService.revokeAllForUser(userId, TokenRevocationReason.ADMIN_REVOKED);

        audit(organizationId, actorId, AuditEventTypes.USER_PASSWORD_ADMIN_RESET, user,
                "Password reset by administrator; %d session(s) revoked".formatted(revoked),
                true, clientIp, AuditSeverity.WARNING);
        return new UserManagementResponses.Message("Password set. The user must sign in with the new password.");
    }

    // --- Invitations ----------------------------------------------------------------------------

    /**
     * Issues an invitation.
     *
     * @return the opaque plaintext token — shown once, to be delivered by the notification centre.
     *         The caller must not log or persist it beyond the response.
     */
    @Transactional
    public String invite(UUID organizationId, UUID actorId, String clientIp,
                         UserManagementCommands.InviteUser command) {
        assertUniqueUsername(organizationId, command.username(), null);
        assertUniqueEmail(command.email(), null);
        if (invitationRepository.hasOutstandingForEmail(organizationId, command.email())) {
            throw new BusinessException(ErrorCode.INVITATION_ALREADY_OUTSTANDING);
        }

        Organization org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_RESOLVED));
        // Resolve roles now so activation does not fail on a typo discovered at sign-up time.
        Set<String> codes = command.roleCodes() == null ? Set.of() : command.roleCodes();
        if (!codes.isEmpty()) {
            resolveRoles(organizationId, codes); // validates visibility, throws on unknown
        }

        String plaintext = TokenGenerator.opaqueToken();
        UserInvitation invitation = new UserInvitation();
        invitation.setOrganization(org);
        invitation.setEmail(command.email());
        invitation.setFullName(command.fullName());
        invitation.setUsername(command.username());
        invitation.setJobTitle(command.jobTitle());
        invitation.setPhone(command.phone());
        invitation.setTokenHash(Hashes.sha256Hex(plaintext));
        invitation.setRoleCodes(new LinkedHashSet<>(codes));
        invitation.setInvitedBy(actorId);
        invitation.setInvitedAt(clock.instant());
        invitation.setExpiresAt(clock.instant().plus(jwtProperties.invitationTtl()));
        invitation.setClientIp(clientIp);
        invitationRepository.save(invitation);

        audit(organizationId, actorId, AuditEventTypes.USER_INVITATION_ISSUED, null,
                "Invitation issued to %s".formatted(command.email()), true, clientIp,
                Map.of("email", command.email(), "username", command.username()));
        return plaintext;
    }

    /**
     * Activates an invitation: materialises the user, grants the requested roles, marks the
     * invitation consumed. The new user signs in immediately with the password they just chose.
     */
    @Transactional
    public UserManagementResponses.Message activate(String plaintextToken, String password,
                                                    String clientIp) {
        Instant now = clock.instant();
        UserInvitation invitation = invitationRepository
                .findOutstandingByTokenHash(Hashes.sha256Hex(plaintextToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITATION_TOKEN_INVALID));
        if (!invitation.isActivatable(now)) {
            // Expired invitations are reaped to revoked state so they can no longer be presented.
            if (invitation.isExpired(now)) {
                invitation.revoke(now, invitation.getInvitedBy());
            }
            throw new BusinessException(ErrorCode.INVITATION_TOKEN_INVALID);
        }

        // Re-check uniqueness at activation: another admin may have created a colliding user while
        // the invitation was outstanding. The earlier uniqueness check is a fast path, not a lock.
        if (userRepository.existsByEmail(invitation.getEmail(), null)
                || userRepository.existsByUsernameInOrganization(
                        invitation.getOrganization().getId(), invitation.getUsername(), null)) {
            log.warn("Invitation {} cannot activate: username/email collided after issuance",
                    invitation.getId());
            invitation.revoke(now, invitation.getInvitedBy());
            throw new BusinessException(ErrorCode.INVITATION_TOKEN_INVALID);
        }

        User user = new User();
        user.setOrganization(invitation.getOrganization());
        user.setUsername(invitation.getUsername());
        user.setEmail(invitation.getEmail());
        user.setFullName(invitation.getFullName());
        user.setJobTitle(invitation.getJobTitle());
        user.setPhone(invitation.getPhone());
        validatePasswordPolicy(user, password);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setPasswordUpdatedAt(now);
        user.setMustChangePassword(false);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(now); // accepting an emailed invitation confirms the address
        if (!invitation.getRoleCodes().isEmpty()) {
            user.setRoles(resolveRoles(invitation.getOrganization().getId(), invitation.getRoleCodes()));
        }
        userRepository.save(user);

        invitation.accept(now, user.getId());

        audit(invitation.getOrganization().getId(), user.getId(),
                AuditEventTypes.USER_INVITATION_ACCEPTED, user,
                "Invitation accepted by %s".formatted(user.getEmail()), true, clientIp);
        return new UserManagementResponses.Message("Account activated. You can now sign in.");
    }

    @Transactional
    public void revokeInvitation(UUID invitationId, UUID organizationId, UUID actorId, String clientIp) {
        UserInvitation invitation = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation", invitationId));
        if (!invitation.getOrganization().getId().equals(organizationId)) {
            throw new ResourceNotFoundException("Invitation", invitationId);
        }
        if (!invitation.isOutstanding()) {
            return; // idempotent: revoking an already-consumed invitation is a no-op
        }
        invitation.revoke(clock.instant(), actorId);
        audit(organizationId, actorId, AuditEventTypes.USER_INVITATION_REVOKED, null,
                "Invitation to %s revoked".formatted(invitation.getEmail()), true, clientIp,
                Map.of("email", invitation.getEmail()));
    }

    @Transactional(readOnly = true)
    public List<UserManagementResponses.InvitationSummary> listInvitations(UUID organizationId) {
        return invitationRepository.findAll().stream()
                .filter(i -> i.getOrganization().getId().equals(organizationId))
                .map(this::toInvitationSummary)
                .toList();
    }

    // --- Helpers --------------------------------------------------------------------------------

    private User requireUserInTenant(UUID userId, UUID organizationId) {
        User user = userRepository.findByIdWithAuthorities(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (!user.getOrganization().getId().equals(organizationId)) {
            // Do not reveal existence across tenants — return 404, never 403, to avoid an oracle.
            throw new ResourceNotFoundException("User", userId);
        }
        return user;
    }

    private void assertUniqueUsername(UUID organizationId, String username, UUID excludingId) {
        if (userRepository.existsByUsernameInOrganization(organizationId, username, excludingId)) {
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
    }

    private void assertUniqueEmail(String email, UUID excludingId) {
        if (userRepository.existsByEmail(email, excludingId)) {
            throw new BusinessException(ErrorCode.EMAIL_TAKEN);
        }
    }

    private Set<Role> resolveRoles(UUID organizationId, Set<String> codes) {
        List<Role> visible = roleRepository.findVisibleToTenant(organizationId);
        Map<String, Role> byCode = new java.util.HashMap<>();
        for (Role r : visible) {
            byCode.put(r.getCode(), r);
        }
        Set<Role> resolved = new LinkedHashSet<>();
        for (String code : codes) {
            Role role = byCode.get(code);
            if (role == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Unknown or unavailable role code: " + code);
            }
            resolved.add(role);
        }
        return resolved;
    }

    private void validatePasswordPolicy(User user, String newPassword) {
        PasswordPolicy policy = passwordService.policy();
        List<String> violations = policy.validate(newPassword, user.getUsername(), user.getEmail(),
                user.getFullName(), user.getOrganization().getCode(), user.getOrganization().getName());
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION,
                    "The password does not meet the security policy",
                    Map.of("violations", violations));
        }
    }

    private UserManagementResponses.UserSummary toSummary(User user) {
        return UserManagementResponses.UserSummary.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .jobTitle(user.getJobTitle())
                .status(user.getStatus().name())
                .mfaEnabled(user.isMfaEnabled())
                .lastLoginAt(user.getLastLoginAt())
                .roles(user.getRoles().stream().map(Role::getCode).sorted().toList())
                .build();
    }

    private UserManagementResponses.UserDetail toDetail(User user) {
        return UserManagementResponses.UserDetail.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .jobTitle(user.getJobTitle())
                .phone(user.getPhone())
                .avatarUrl(user.getAvatarUrl())
                .status(user.getStatus().name())
                .mfaEnabled(user.isMfaEnabled())
                .mustChangePassword(user.isMustChangePassword())
                .lastLoginAt(user.getLastLoginAt())
                .lastLoginIp(user.getLastLoginIp())
                .timezone(user.getTimezone())
                .locale(user.getLocale())
                .createdAt(user.getCreatedAt())
                .roles(user.getRoles().stream().map(Role::getCode).sorted().toList())
                .permissions(userMapper.permissionCodes(user))
                .build();
    }

    private UserManagementResponses.InvitationSummary toInvitationSummary(UserInvitation i) {
        return UserManagementResponses.InvitationSummary.builder()
                .id(i.getId())
                .email(i.getEmail())
                .fullName(i.getFullName())
                .username(i.getUsername())
                .roleCodes(i.getRoleCodes().stream().sorted().toList())
                .invitedAt(i.getInvitedAt())
                .expiresAt(i.getExpiresAt())
                .status(i.getAcceptedAt() != null ? "ACCEPTED"
                        : i.getRevokedAt() != null ? "REVOKED"
                        : i.isExpired(clock.instant()) ? "EXPIRED" : "PENDING")
                .build();
    }

    private void audit(UUID orgId, UUID actorId, String type, User user, String message,
                       boolean success, String clientIp) {
        audit(orgId, actorId, type, user, message, success, clientIp, AuditSeverity.INFO);
    }

    private void audit(UUID orgId, UUID actorId, String type, User user, String message,
                       boolean success, String clientIp, AuditSeverity severity) {
        auditService.record(AuditEvent.builder()
                .organizationId(orgId)
                .actorUserId(actorId)
                .eventType(type)
                .category(AuditCategory.DATA)
                .severity(severity)
                .resourceType("User")
                .resourceId(user == null ? null : user.getId().toString())
                .success(success)
                .message(message)
                .clientIp(clientIp)
                .build());
    }

    private void audit(UUID orgId, UUID actorId, String type, User user, String message,
                       boolean success, String clientIp, Map<String, Object> metadata) {
        auditService.record(AuditEvent.builder()
                .organizationId(orgId)
                .actorUserId(actorId)
                .eventType(type)
                .category(AuditCategory.DATA)
                .severity(AuditSeverity.INFO)
                .resourceType("User")
                .resourceId(user == null ? null : user.getId().toString())
                .success(success)
                .message(message)
                .clientIp(clientIp)
                .metadata(metadata)
                .build());
    }

    /**
     * Evicts a user's cached permission entries.
     *
     * <p>{@code IdentityApiService.hasPermission} caches per {@code userId:permissionCode}. We do not
     * know which codes the user holds at eviction time without a query, so the whole user's entries
     * are cleared via {@link org.springframework.cache.Cache#invalidate(Object)}-by-iteration — only
     * for the affected user, never the whole cache. The 5-minute TTL is a fallback bound; this makes
     * role/status changes take effect immediately on the next token issuance or background check.
     */
    private void evictUserPermissionCache(UUID userId) {
        var cache = cacheManager.getCache("userPermissions");
        if (cache == null) {
            return;
        }
        // Caffeine's native invalidate-by-key only matches the exact stored key. Because the cache
        // key is "userId:permissionCode", we clear the cache eagerly. This is acceptable: it is an
        // in-process L1 that repopulates on next read, and it is touched only on user-role changes
        // (rare) rather than on the request path (frequent).
        cache.clear();
        log.debug("Cleared userPermissions cache after change to user {}", userId);
    }

    /** Exposed for a scheduled reaper bean to call; reaping is an operational concern, not a use case. */
    @Scheduled(cron = "${aquagrid.identity.invitation.cleanup-cron:0 0 4 * * *}")
    @Transactional
    public int reapExpiredInvitations() {
        int reaped = invitationRepository.reapExpired(clock.instant());
        if (reaped > 0) {
            log.info("Reaped {} expired user invitations", reaped);
        }
        return reaped;
    }

    /** Convenience for callers that want the configured invitation TTL. */
    public Duration invitationTtl() {
        return jwtProperties.invitationTtl();
    }
}
