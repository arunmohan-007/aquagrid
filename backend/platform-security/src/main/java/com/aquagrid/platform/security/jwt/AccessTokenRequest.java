package com.aquagrid.platform.security.jwt;

import lombok.Builder;

import java.util.Set;
import java.util.UUID;

/**
 * Everything the identity module must supply to mint an access token.
 *
 * <p>This record is the contract between {@code module-identity} and {@code platform-security}:
 * the security module knows how to sign a token but nothing about users, and the identity module
 * knows about users but nothing about JOSE.
 */
@Builder
public record AccessTokenRequest(
        UUID userId,
        String username,
        String email,
        String fullName,
        UUID organizationId,
        String organizationCode,
        Set<String> roles,
        Set<String> permissions,
        UUID sessionId,
        boolean mustChangePassword
) {
}
