package com.aquagrid.platform.identity.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Outbound payloads for Module 2 (user & role administration).
 *
 * <p>Kept in a sibling file to {@link AuthResponses} rather than merged into it, because Module 2's
 * shape evolves on its own cadence and the auth DTOs are part of the sign-in contract that the SPA
 * bootstrap depends on. The {@code Message} record is shared by referencing it through
 * {@link AuthResponses.Message} to avoid a parallel acknowledgement type.
 */
public final class UserManagementResponses {

    private UserManagementResponses() {
    }

    @Schema(name = "MessageResponse")
    public record Message(String message) {
    }

    @Schema(name = "UserSummary", description = "A user, as shown in lists")
    @Builder
    public record UserSummary(
            UUID id,
            String username,
            String email,
            String fullName,
            String jobTitle,
            String status,
            boolean mfaEnabled,
            Instant lastLoginAt,
            List<String> roles
    ) {
    }

    @Schema(name = "UserDetail", description = "A user, with full profile and effective permissions")
    @Builder
    public record UserDetail(
            UUID id,
            String username,
            String email,
            String fullName,
            String jobTitle,
            String phone,
            String avatarUrl,
            String status,
            boolean mfaEnabled,
            boolean mustChangePassword,
            Instant lastLoginAt,
            String lastLoginIp,
            String timezone,
            String locale,
            Instant createdAt,
            List<String> roles,
            Set<String> permissions
    ) {
    }

    @Schema(name = "RoleSummary")
    @Builder
    public record RoleSummary(
            UUID id,
            String code,
            String name,
            String description,
            boolean system,
            int permissionCount
    ) {
    }

    @Schema(name = "RoleDetail")
    @Builder
    public record RoleDetail(
            UUID id,
            String code,
            String name,
            String description,
            boolean system,
            List<String> permissions
    ) {
    }

    @Schema(name = "InvitationSummary")
    @Builder
    public record InvitationSummary(
            UUID id,
            String email,
            String fullName,
            String username,
            List<String> roleCodes,
            Instant invitedAt,
            Instant expiresAt,
            @Schema(description = "PENDING, ACCEPTED, REVOKED or EXPIRED") String status
    ) {
    }

    @Schema(name = "InvitationCreatedResponse",
            description = "The opaque invitation token. Shown once; deliver it to the invitee.")
    public record InvitationCreated(
            String token,
            Instant expiresAt
    ) {
    }
}
