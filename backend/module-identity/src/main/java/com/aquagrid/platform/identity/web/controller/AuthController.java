package com.aquagrid.platform.identity.web.controller;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.common.web.ClientIpResolver;
import com.aquagrid.platform.identity.application.command.AuthCommands;
import com.aquagrid.platform.identity.application.service.AuthenticationResult;
import com.aquagrid.platform.identity.application.service.AuthenticationService;
import com.aquagrid.platform.identity.application.service.MfaService;
import com.aquagrid.platform.identity.application.service.PasswordService;
import com.aquagrid.platform.identity.application.service.RefreshCookieService;
import com.aquagrid.platform.identity.application.service.SessionService;
import com.aquagrid.platform.identity.web.dto.AuthRequests;
import com.aquagrid.platform.identity.web.dto.AuthResponses;
import com.aquagrid.platform.security.core.AuthenticatedPrincipal;
import com.aquagrid.platform.security.core.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The authentication API.
 *
 * <p>The controller is intentionally thin: it resolves request context that only the web layer can
 * know (the real client IP, the user agent, the refresh cookie), delegates to an application
 * service, and translates the result into an HTTP response. It contains no branching on business
 * state and holds no transaction.
 *
 * <p>The client IP is taken from {@link ClientIpResolver} rather than
 * {@code request.getRemoteAddr()} or a raw header. Lockout, rate limiting and the audit trail all
 * key on this value, and a header-derived IP that is trusted unconditionally lets an attacker
 * evade every one of them by varying a string.
 */
@Slf4j
@Tag(name = "Authentication", description = "Sign-in, multi-factor, sessions and passwords")
@RestController
@RequestMapping(value = ApiPaths.AUTH, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationService authenticationService;
    private final PasswordService passwordService;
    private final MfaService mfaService;
    private final SessionService sessionService;
    private final RefreshCookieService refreshCookieService;
    private final ClientIpResolver clientIpResolver;

    // ---- Sign-in -------------------------------------------------------------------------------

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Sign in with a password",
            description = """
                    Verifies the first factor. Returns an access token and sets the refresh-token
                    cookie, unless the account has multi-factor authentication enabled — in which
                    case it returns `mfaRequired: true` and a short-lived `mfaToken` that is
                    accepted only by `/auth/mfa/challenge`.

                    Every credential failure returns the same `AUTH_INVALID_CREDENTIALS` response,
                    so the endpoint cannot be used to discover which accounts exist.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Signed in, or second factor required"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials"),
            @ApiResponse(responseCode = "423", description = "Account temporarily locked"),
            @ApiResponse(responseCode = "429", description = "Too many attempts")})
    public ResponseEntity<AuthResponses.Authentication> login(
            @Valid @RequestBody AuthRequests.Login request,
            HttpServletRequest httpRequest) {

        AuthenticationResult result = authenticationService.login(AuthCommands.Login.builder()
                .identifier(request.identifier())
                .password(request.password())
                .deviceLabel(resolveDeviceLabel(request.deviceLabel(), httpRequest))
                .clientIp(clientIpResolver.resolve(httpRequest))
                .userAgent(httpRequest.getHeader(HttpHeaders.USER_AGENT))
                .build());

        return respond(result);
    }

    @PostMapping(value = "/mfa/challenge", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Complete sign-in with a second factor",
            description = "Accepts a six-digit TOTP code or a single-use recovery code, "
                    + "authenticated by the `mfaToken` returned from `/auth/login`.")
    public ResponseEntity<AuthResponses.Authentication> mfaChallenge(
            @Valid @RequestBody AuthRequests.MfaChallenge request,
            HttpServletRequest httpRequest) {

        AuthenticationResult result = authenticationService.completeMfaChallenge(
                AuthCommands.MfaChallenge.builder()
                        .mfaToken(request.mfaToken())
                        .code(request.code())
                        .deviceLabel(resolveDeviceLabel(request.deviceLabel(), httpRequest))
                        .clientIp(clientIpResolver.resolve(httpRequest))
                        .userAgent(httpRequest.getHeader(HttpHeaders.USER_AGENT))
                        .build());

        return respond(result);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate the session and issue a new access token",
            description = """
                    Reads the refresh token from the `HttpOnly` cookie — it is never accepted in
                    the request body, so a cross-site page cannot supply one.

                    The presented token is invalidated and replaced. Presenting a token that has
                    already been rotated indicates the token leaked: every session in that family
                    is revoked and the caller receives `AUTH_REFRESH_TOKEN_REUSED`.""")
    public ResponseEntity<AuthResponses.Authentication> refresh(HttpServletRequest httpRequest) {
        AuthenticationResult result = authenticationService.refresh(AuthCommands.Refresh.builder()
                .refreshToken(refreshCookieService.read(httpRequest).orElse(null))
                .clientIp(clientIpResolver.resolve(httpRequest))
                .userAgent(httpRequest.getHeader(HttpHeaders.USER_AGENT))
                .build());

        return respond(result);
    }

    @PostMapping("/logout")
    @Operation(summary = "Sign out of this device",
            description = "Idempotent: succeeds and clears the cookie even without a valid session.")
    public ResponseEntity<AuthResponses.Message> logout(HttpServletRequest httpRequest) {
        ResponseCookie cleared = authenticationService.logout(
                refreshCookieService.read(httpRequest).orElse(null),
                SecurityUtils.currentUserId().orElse(null),
                clientIpResolver.resolve(httpRequest),
                httpRequest.getHeader(HttpHeaders.USER_AGENT));

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cleared.toString())
                .body(new AuthResponses.Message("Signed out"));
    }

    @PostMapping("/logout-all")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Sign out of every device")
    public ResponseEntity<AuthResponses.Message> logoutAll(HttpServletRequest httpRequest) {
        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        ResponseCookie cleared = authenticationService.logoutAll(principal.userId(),
                clientIpResolver.resolve(httpRequest),
                httpRequest.getHeader(HttpHeaders.USER_AGENT));

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cleared.toString())
                .body(new AuthResponses.Message("Signed out of all devices"));
    }

    // ---- Current user --------------------------------------------------------------------------

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "The authenticated user",
            description = "Includes roles, effective permissions and the tenant's map bootstrap "
                    + "payload (default centre, zoom and service-area bounding box), so the GIS "
                    + "dashboard opens on the correct extent without an extra round trip.")
    public AuthResponses.CurrentUser me() {
        return authenticationService.currentUser(SecurityUtils.requirePrincipal().userId());
    }

    // ---- Passwords -----------------------------------------------------------------------------

    @GetMapping("/password/policy")
    @Operation(summary = "The active password policy",
            description = "Public so the sign-up and reset forms can validate live, rather than "
                    + "rejecting the user after submission.")
    public AuthResponses.PasswordPolicy passwordPolicy() {
        return passwordService.describePolicy();
    }

    @PostMapping(value = "/password/change", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Change your password",
            description = "Requires the current password. On success every session is revoked, "
                    + "including this one, so the client must sign in again.")
    public AuthResponses.Message changePassword(
            @Valid @RequestBody AuthRequests.ChangePassword request,
            HttpServletRequest httpRequest) {

        passwordService.changePassword(SecurityUtils.requirePrincipal().userId(),
                request.currentPassword(), request.newPassword(),
                clientIpResolver.resolve(httpRequest));
        return new AuthResponses.Message("Password changed. Please sign in again.");
    }

    @PostMapping(value = "/password/forgot", consumes = MediaType.APPLICATION_JSON_VALUE)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202",
            description = "Always returned, whether or not the address is registered")
    @Operation(summary = "Request a password reset link",
            description = """
                    Always returns 202 with the same body. Confirming whether an address is
                    registered would turn this endpoint into a user-enumeration oracle, which is
                    the reconnaissance step of every credential-stuffing campaign.""")
    public ResponseEntity<AuthResponses.Message> forgotPassword(
            @Valid @RequestBody AuthRequests.ForgotPassword request,
            HttpServletRequest httpRequest) {

        passwordService.beginReset(request.email(), clientIpResolver.resolve(httpRequest))
                .ifPresent(token ->
                        // Module 20 (Notification Center) delivers this by email. Until then the
                        // token is logged at DEBUG only, never at INFO and never in the response.
                        log.debug("Password reset token issued: {}", token));

        return ResponseEntity.accepted().body(new AuthResponses.Message(
                "If an account exists for that address, a password reset link has been sent."));
    }

    @PostMapping(value = "/password/reset", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Complete a password reset",
            description = "The link is single-use and short-lived. On success every session is "
                    + "revoked, so an intruder who caused the reset is evicted.")
    public AuthResponses.Message resetPassword(
            @Valid @RequestBody AuthRequests.ResetPassword request,
            HttpServletRequest httpRequest) {

        passwordService.completeReset(request.token(), request.newPassword(),
                clientIpResolver.resolve(httpRequest));
        return new AuthResponses.Message("Your password has been reset. Please sign in.");
    }

    // ---- Multi-factor --------------------------------------------------------------------------

    @PostMapping("/mfa/setup")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Begin multi-factor enrolment",
            description = "Returns a seed and an `otpauth://` URI for the QR code. Nothing is "
                    + "activated until `/auth/mfa/activate` succeeds with a live code.")
    public AuthResponses.MfaSetup beginMfaEnrolment() {
        return mfaService.beginEnrolment(SecurityUtils.requirePrincipal().userId());
    }

    @PostMapping(value = "/mfa/activate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Activate multi-factor authentication",
            description = "Returns the recovery codes exactly once — only their hashes are stored, "
                    + "so they cannot be retrieved later.")
    public AuthResponses.MfaActivation activateMfa(
            @Valid @RequestBody AuthRequests.MfaActivate request,
            HttpServletRequest httpRequest) {

        return mfaService.activate(SecurityUtils.requirePrincipal().userId(), request.code(),
                clientIpResolver.resolve(httpRequest));
    }

    @PostMapping(value = "/mfa/disable", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Disable multi-factor authentication",
            description = "Requires both the password and a current code: a stolen session alone "
                    + "must not be able to weaken the account.")
    public AuthResponses.Message disableMfa(@Valid @RequestBody AuthRequests.MfaDisable request,
                                            HttpServletRequest httpRequest) {

        mfaService.disable(SecurityUtils.requirePrincipal().userId(), request.password(),
                request.code(), clientIpResolver.resolve(httpRequest));
        return new AuthResponses.Message("Multi-factor authentication disabled");
    }

    // ---- Sessions ------------------------------------------------------------------------------

    @GetMapping("/sessions")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Active device sessions")
    public List<AuthResponses.Session> sessions() {
        return sessionService.listSessions(SecurityUtils.requirePrincipal());
    }

    @DeleteMapping("/sessions/{sessionId}")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke one session")
    public void revokeSession(@PathVariable UUID sessionId, HttpServletRequest httpRequest) {
        sessionService.revokeSession(SecurityUtils.requirePrincipal(), sessionId,
                clientIpResolver.resolve(httpRequest));
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private ResponseEntity<AuthResponses.Authentication> respond(AuthenticationResult result) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (result.refreshCookie() != null) {
            builder.header(HttpHeaders.SET_COOKIE, result.refreshCookie().toString());
        }
        // Tokens must never be cached by a proxy or written to the browser's disk cache.
        builder.header(HttpHeaders.CACHE_CONTROL, "no-store");
        builder.header(HttpHeaders.PRAGMA, "no-cache");
        return builder.body(result.body());
    }

    private String resolveDeviceLabel(String supplied, HttpServletRequest request) {
        if (supplied != null && !supplied.isBlank()) {
            return supplied.trim();
        }
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        return userAgent == null ? "Unknown device"
                : userAgent.substring(0, Math.min(userAgent.length(), 120));
    }
}
