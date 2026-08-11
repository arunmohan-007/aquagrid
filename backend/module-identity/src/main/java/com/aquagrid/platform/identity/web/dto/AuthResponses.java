package com.aquagrid.platform.identity.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Outbound payloads for the authentication API.
 *
 * <p>The refresh token appears in none of them. It is delivered exclusively as an
 * {@code HttpOnly} cookie, so no amount of JavaScript — injected or otherwise — can read it.
 */
public final class AuthResponses {

    private AuthResponses() {
    }

    @Schema(name = "AuthenticationResponse",
            description = "Either a completed sign-in, or a pending second-factor challenge")
    @Builder
    public record Authentication(
            @Schema(description = "Bearer token for API calls. Absent when MFA is pending.")
            String accessToken,

            @Schema(example = "Bearer")
            String tokenType,

            @Schema(description = "Access token lifetime in seconds", example = "900")
            Long expiresIn,

            @Schema(description = "Absolute expiry, so the client can schedule a silent refresh")
            Instant expiresAt,

            @Schema(description = "True when a second factor is still required")
            boolean mfaRequired,

            @Schema(description = "Short-lived token accepted only by /auth/mfa/challenge")
            String mfaToken,

            @Schema(description = "True when the password must be changed before anything else")
            boolean mustChangePassword,

            CurrentUser user
    ) {
        public static Authentication mfaPending(String mfaToken, long expiresIn) {
            return Authentication.builder()
                    .mfaRequired(true)
                    .mfaToken(mfaToken)
                    .expiresIn(expiresIn)
                    .build();
        }
    }

    @Schema(name = "CurrentUserResponse", description = "The authenticated principal")
    @Builder
    public record CurrentUser(
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
            String timezone,
            String locale,
            Set<String> roles,
            Set<String> permissions,
            OrganizationSummary organization
    ) {
    }

    /**
     * Tenant context plus the map bootstrap payload.
     *
     * <p>{@code defaultCenter}/{@code defaultZoom}/{@code boundaryBbox} are returned here so the GIS
     * dashboard renders the correct extent on first paint. Fetching the tenant's extent separately
     * would mean the map visibly jumps from a world view to the service area on every load.
     */
    @Schema(name = "OrganizationSummary")
    @Builder
    public record OrganizationSummary(
            UUID id,
            String code,
            String name,
            String type,
            String timezone,
            String locale,
            String currencyCode,
            String unitSystem,
            @Schema(description = "[longitude, latitude] in EPSG:4326", example = "[76.9366, 8.5241]")
            double[] defaultCenter,
            @Schema(example = "12")
            Integer defaultZoom,
            @Schema(description = "[minX, minY, maxX, maxY] of the service area in EPSG:4326")
            double[] boundaryBbox
    ) {
    }

    @Schema(name = "SessionResponse", description = "An active device session")
    @Builder
    public record Session(
            UUID id,
            String deviceLabel,
            String userAgent,
            String clientIp,
            Instant issuedAt,
            Instant lastUsedAt,
            Instant expiresAt,
            @Schema(description = "True for the session making this request")
            boolean current
    ) {
    }

    @Schema(name = "MfaSetupResponse", description = "Enrolment material; not yet active")
    @Builder
    public record MfaSetup(
            @Schema(description = "Base32 seed, for manual entry")
            String secret,
            @Schema(description = "otpauth:// URI to render as a QR code")
            String provisioningUri
    ) {
    }

    @Schema(name = "MfaActivationResponse")
    @Builder
    public record MfaActivation(
            boolean mfaEnabled,
            @Schema(description = "Single-use recovery codes. Returned exactly once — they cannot "
                    + "be retrieved later because only their hashes are stored.")
            List<String> recoveryCodes
    ) {
    }

    @Schema(name = "PasswordPolicyResponse")
    @Builder
    public record PasswordPolicy(
            int minLength,
            int maxLength,
            boolean requireUppercase,
            boolean requireLowercase,
            boolean requireDigit,
            boolean requireSpecial,
            int historyDepth,
            List<String> rules
    ) {
    }

    @Schema(name = "MessageResponse", description = "Acknowledgement with no resource payload")
    public record Message(String message) {
    }
}
