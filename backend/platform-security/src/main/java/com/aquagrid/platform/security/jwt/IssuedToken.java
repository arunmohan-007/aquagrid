package com.aquagrid.platform.security.jwt;

import java.time.Instant;

/**
 * A minted token together with the metadata a client needs.
 *
 * @param tokenId  the {@code jti}, recorded in the audit trail so a specific token can be traced
 * @param expiresAt absolute expiry, so the SPA can schedule a silent refresh instead of waiting
 *                  for a 401
 */
public record IssuedToken(String value, String tokenId, Instant issuedAt, Instant expiresAt) {

    public long expiresInSeconds() {
        return Math.max(0, expiresAt.getEpochSecond() - issuedAt.getEpochSecond());
    }
}
