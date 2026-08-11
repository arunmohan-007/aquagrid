package com.aquagrid.platform.security.ratelimit;

/**
 * Outcome of a rate-limit check.
 *
 * @param allowed            whether the caller may proceed
 * @param remainingTokens    how many further calls are permitted in the current window
 * @param retryAfterSeconds  when denied, how long the caller should wait; surfaced to the client
 *                           in the {@code Retry-After} header so the UI can show a real countdown
 *                           instead of an unexplained failure
 */
public record RateLimitDecision(boolean allowed, long remainingTokens, long retryAfterSeconds) {

    public static RateLimitDecision allowed(long remainingTokens) {
        return new RateLimitDecision(true, remainingTokens, 0);
    }

    public static RateLimitDecision denied(long retryAfterSeconds) {
        return new RateLimitDecision(false, 0, retryAfterSeconds);
    }
}
