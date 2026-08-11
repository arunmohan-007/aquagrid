package com.aquagrid.platform.identity.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Inbound payloads for the authentication API.
 *
 * <p>Grouped in one file because they are one cohesive contract read together; each is an immutable
 * record with its constraints declared, so springdoc generates accurate documentation and the
 * validation cannot drift from the docs.
 *
 * <p>Note what is <em>not</em> constrained: {@code password} on login has no pattern or maximum.
 * Rejecting a login payload for failing the password policy would tell an attacker the policy and
 * would break users whose password predates a policy change. Policy is enforced when a password is
 * <em>set</em>, never when it is presented.
 */
public final class AuthRequests {

    private AuthRequests() {
    }

    @Schema(name = "LoginRequest", description = "Credential (first factor) authentication")
    public record Login(
            @Schema(description = "Email address, or username when organizationCode is supplied",
                    example = "j.mathew@kwa.kerala.gov.in", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "Email or username is required")
            @Size(max = 320)
            String identifier,

            @Schema(description = "Password", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "Password is required")
            String password,

            @Schema(description = "Organization code. Required only when signing in with a username.",
                    example = "KWA-TVM")
            @Size(max = 60)
            String organizationCode,

            @Schema(description = "Human-readable device label shown on the sessions screen",
                    example = "Chrome on Windows")
            @Size(max = 120)
            String deviceLabel
    ) {
    }

    @Schema(name = "MfaChallengeRequest", description = "Second factor verification")
    public record MfaChallenge(
            @Schema(description = "The mfaToken returned by /login",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "Verification session is required")
            String mfaToken,

            @Schema(description = "Six-digit authenticator code, or a recovery code",
                    example = "492013", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "Verification code is required")
            @Size(max = 20)
            String code,

            @Schema(description = "Human-readable device label")
            @Size(max = 120)
            String deviceLabel
    ) {
    }

    @Schema(name = "ChangePasswordRequest")
    public record ChangePassword(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "Current password is required")
            String currentPassword,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "New password is required")
            @Size(max = 128, message = "Password must be at most 128 characters")
            String newPassword
    ) {
    }

    @Schema(name = "ForgotPasswordRequest")
    public record ForgotPassword(
            @Schema(example = "j.mathew@kwa.kerala.gov.in",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "Email is required")
            @Email(message = "Must be a valid email address")
            @Size(max = 320)
            String email
    ) {
    }

    @Schema(name = "ResetPasswordRequest")
    public record ResetPassword(
            @Schema(description = "Token from the emailed reset link",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "Reset token is required")
            String token,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "New password is required")
            @Size(max = 128, message = "Password must be at most 128 characters")
            String newPassword
    ) {
    }

    @Schema(name = "MfaActivateRequest", description = "Confirms enrolment with a live code")
    public record MfaActivate(
            @Schema(example = "492013", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "Verification code is required")
            @Pattern(regexp = "\\d{6}", message = "Must be a six-digit code")
            String code
    ) {
    }

    @Schema(name = "MfaDisableRequest",
            description = "Disabling a factor requires proof of both factors")
    public record MfaDisable(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "Password is required")
            String password,

            @Schema(example = "492013", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "Verification code is required")
            @Size(max = 20)
            String code
    ) {
    }
}
