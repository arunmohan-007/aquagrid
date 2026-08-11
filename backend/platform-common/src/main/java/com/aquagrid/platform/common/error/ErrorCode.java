package com.aquagrid.platform.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * The machine-readable error vocabulary of the platform.
 *
 * <p>These codes are part of the public API contract: clients branch on {@code code}, never on the
 * human-readable {@code detail}. Codes are additive — never renamed, never repurposed.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // ---- Generic -----------------------------------------------------------------------------
    INTERNAL_ERROR("An unexpected error occurred", HttpStatus.INTERNAL_SERVER_ERROR),
    VALIDATION_FAILED("Request validation failed", HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST("The request could not be parsed", HttpStatus.BAD_REQUEST),
    RESOURCE_NOT_FOUND("Resource not found", HttpStatus.NOT_FOUND),
    RESOURCE_CONFLICT("Resource conflict", HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION("The resource was modified by another user", HttpStatus.CONFLICT),
    OPERATION_NOT_PERMITTED("Operation not permitted", HttpStatus.FORBIDDEN),
    RATE_LIMIT_EXCEEDED("Too many requests", HttpStatus.TOO_MANY_REQUESTS),
    UNSUPPORTED_MEDIA_TYPE("Unsupported media type", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    METHOD_NOT_ALLOWED("HTTP method not allowed", HttpStatus.METHOD_NOT_ALLOWED),

    // ---- Tenancy -----------------------------------------------------------------------------
    TENANT_NOT_RESOLVED("Tenant could not be resolved", HttpStatus.FORBIDDEN),
    TENANT_INACTIVE("Organization is not active", HttpStatus.FORBIDDEN),

    // ---- Authentication ----------------------------------------------------------------------
    AUTH_REQUIRED("Authentication required", HttpStatus.UNAUTHORIZED),
    AUTH_INVALID_CREDENTIALS("Invalid credentials", HttpStatus.UNAUTHORIZED),
    AUTH_ACCOUNT_LOCKED("Account is temporarily locked", HttpStatus.LOCKED),
    AUTH_ACCOUNT_DISABLED("Account is disabled", HttpStatus.FORBIDDEN),
    AUTH_ACCOUNT_PENDING("Account activation is pending", HttpStatus.FORBIDDEN),
    AUTH_TOKEN_INVALID("Token is invalid", HttpStatus.UNAUTHORIZED),
    AUTH_TOKEN_EXPIRED("Token has expired", HttpStatus.UNAUTHORIZED),
    AUTH_REFRESH_TOKEN_MISSING("Refresh token missing", HttpStatus.UNAUTHORIZED),
    AUTH_REFRESH_TOKEN_REUSED("Refresh token reuse detected; all sessions revoked",
            HttpStatus.UNAUTHORIZED),
    AUTH_PASSWORD_CHANGE_REQUIRED("Password change required", HttpStatus.FORBIDDEN),

    // ---- Multi-factor ------------------------------------------------------------------------
    MFA_REQUIRED("Multi-factor authentication required", HttpStatus.UNAUTHORIZED),
    MFA_CODE_INVALID("Invalid verification code", HttpStatus.UNAUTHORIZED),
    MFA_ALREADY_ENABLED("Multi-factor authentication is already enabled", HttpStatus.CONFLICT),
    MFA_NOT_ENROLLED("Multi-factor authentication is not enrolled", HttpStatus.CONFLICT),

    // ---- Password ----------------------------------------------------------------------------
    PASSWORD_POLICY_VIOLATION("Password does not meet the security policy", HttpStatus.BAD_REQUEST),
    PASSWORD_REUSED("Password was used recently and cannot be reused", HttpStatus.BAD_REQUEST),
    PASSWORD_RESET_TOKEN_INVALID("Password reset link is invalid or has expired",
            HttpStatus.BAD_REQUEST),

    // ---- User administration (Module 2) -----------------------------------------------------
    USERNAME_TAKEN("Username is already in use in this organisation", HttpStatus.CONFLICT),
    EMAIL_TAKEN("Email address is already registered", HttpStatus.CONFLICT),
    ROLE_IS_SYSTEM("System roles cannot be modified or deleted", HttpStatus.FORBIDDEN),
    ROLE_CODE_TAKEN("Role code is already in use", HttpStatus.CONFLICT),
    ROLE_NOT_FOUND("Role was not found", HttpStatus.NOT_FOUND),
    INVITATION_TOKEN_INVALID("Invitation is invalid, expired or has been revoked",
            HttpStatus.BAD_REQUEST),
    INVITATION_ALREADY_OUTSTANDING("An outstanding invitation already exists for that email",
            HttpStatus.CONFLICT),
    CANNOT_MODIFY_SELF_ROLE("You cannot change your own roles or status", HttpStatus.FORBIDDEN),
    CANNOT_DELETE_SELF("You cannot delete your own account", HttpStatus.FORBIDDEN),

    // ---- Data Management: the attribute catalogue ----------------------------------------------
    // Changing an attribute's field name or data type is not refused, but it is not silent either:
    // both reshape data that already exists, and the client is expected to obtain the operator's
    // explicit confirmation and resubmit. A generic 409 would be indistinguishable from a duplicate
    // and the client could not tell which dialog to raise.
    ATTRIBUTE_CHANGE_REQUIRES_CONFIRMATION(
            "This change affects existing data and must be confirmed", HttpStatus.CONFLICT),
    ATTRIBUTE_FIELD_NAME_TAKEN("An attribute with that field name already exists on this layer",
            HttpStatus.CONFLICT),
    ATTRIBUTE_IS_SYSTEM("System attributes cannot be renamed, retyped or deactivated",
            HttpStatus.FORBIDDEN),

    // ---- IoT receiver (Module 18) --------------------------------------------------------------
    // Every way a packet can fail to become telemetry, named once. The receiver classifies each
    // rejection with one of these and stores it on the packet log, so "why did this meter go quiet"
    // is answered from the log rather than reconstructed from the gateway's own diagnostics. They
    // double as the RFC 7807 `code` on the ingress response for transports that can carry one.
    RECEIVER_UNKNOWN_DEVICE("The device is not registered", HttpStatus.NOT_FOUND),
    RECEIVER_UNKNOWN_TENANT("The packet could not be attributed to a tenant", HttpStatus.FORBIDDEN),
    RECEIVER_AUTHENTICATION_FAILED("Packet authentication failed", HttpStatus.UNAUTHORIZED),
    RECEIVER_UNSUPPORTED_TRANSPORT("No receiver is registered for that transport",
            HttpStatus.NOT_IMPLEMENTED),
    RECEIVER_MALFORMED_PAYLOAD("The payload could not be decoded", HttpStatus.BAD_REQUEST),
    RECEIVER_DUPLICATE_PACKET("The packet has already been ingested", HttpStatus.CONFLICT),
    RECEIVER_REPLAY_DETECTED("The packet replays an already-observed nonce or frame counter",
            HttpStatus.CONFLICT),
    RECEIVER_CHECKSUM_FAILED("The payload checksum does not match", HttpStatus.BAD_REQUEST),
    RECEIVER_VALIDATION_FAILED("The decoded telemetry failed validation", HttpStatus.BAD_REQUEST),
    RECEIVER_PAYLOAD_TOO_LARGE("The packet exceeds the configured size limit",
            HttpStatus.PAYLOAD_TOO_LARGE),
    RECEIVER_IP_NOT_ALLOWED("The source address is not permitted to deliver packets",
            HttpStatus.FORBIDDEN),
    RECEIVER_DEVICE_NOT_OPERATIONAL("The device is not in a state that accepts telemetry",
            HttpStatus.CONFLICT),
    RECEIVER_TIMEOUT("The receiver timed out processing the packet", HttpStatus.GATEWAY_TIMEOUT),
    RECEIVER_CONNECTION_FAILED("The transport connection failed", HttpStatus.BAD_GATEWAY);

    private final String defaultMessage;
    private final HttpStatus status;
}
