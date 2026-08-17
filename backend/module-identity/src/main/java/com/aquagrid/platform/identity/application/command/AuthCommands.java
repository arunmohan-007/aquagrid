package com.aquagrid.platform.identity.application.command;

import lombok.Builder;

/**
 * Inputs to the authentication use cases.
 *
 * <p>Separate from the web DTOs on purpose: the request context ({@code clientIp},
 * {@code userAgent}) is resolved at the web edge and is part of the use case's input, but must not
 * be part of the request body — a client-supplied IP address is a forgery, and the whole point of
 * {@code ClientIpResolver} is that this value is derived, not accepted.
 */
public final class AuthCommands {

    private AuthCommands() {
    }

    @Builder
    public record Login(
            String identifier,
            String password,
            String deviceLabel,
            String clientIp,
            String userAgent
    ) {
    }

    @Builder
    public record MfaChallenge(
            String mfaToken,
            String code,
            String deviceLabel,
            String clientIp,
            String userAgent
    ) {
    }

    @Builder
    public record Refresh(
            String refreshToken,
            String clientIp,
            String userAgent
    ) {
    }
}
