package com.aquagrid.platform.identity.application.command;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * Input models for Module 2 user administration.
 *
 * <p>These are command objects, not persistence DTOs: they carry exactly what an application service
 * needs to execute a use case, validated at the web edge by Bean Validation so a service never has to
 * defend against nulls and blanks. Validation annotations live here, not in the web DTOs, because the
 * same command is reused by the simulator (Module 17) and the seeder when it provisions users.
 */
public final class UserManagementCommands {

    private UserManagementCommands() {
    }

    public record CreateUser(
            @NotBlank @Size(max = 320) @Email String email,
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{3,60}$") String username,
            @Size(max = 120) String jobTitle,
            @Size(max = 40) String phone,
            /** Role codes to grant. Must be visible to the tenant. */
            Set<String> roleCodes,
            /** When true the created user must set a password on first sign-in. */
            boolean mustChangePassword
    ) {
    }

    public record UpdateUser(
            @Size(max = 200) String fullName,
            @Size(max = 120) String jobTitle,
            @Size(max = 40) String phone,
            @Size(max = 500) String avatarUrl,
            String timezone,
            String locale
    ) {
    }

    public record ChangeUserStatus(
            @NotBlank String status
    ) {
    }

    public record AssignRoles(
            /** The complete target set. Roles the user holds but not listed here are removed. */
            Set<String> roleCodes
    ) {
    }

    public record AdminResetPassword(
            @Size(min = 1, max = 256) String newPassword,
            boolean mustChangePassword
    ) {
    }

    public record InviteUser(
            @NotBlank @Size(max = 320) @Email String email,
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{3,60}$") String username,
            @Size(max = 120) String jobTitle,
            @Size(max = 40) String phone,
            Set<String> roleCodes
    ) {
    }

    public record AcceptInvitation(
            @NotBlank String token,
            @Size(min = 1, max = 256) String password
    ) {
    }
}
