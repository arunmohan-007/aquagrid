package com.aquagrid.platform.identity.application.service;

import com.aquagrid.platform.common.web.CorrelationIdFilter;
import com.aquagrid.platform.identity.domain.enums.LoginOutcome;
import com.aquagrid.platform.identity.domain.model.LoginAttempt;
import com.aquagrid.platform.identity.domain.model.User;
import com.aquagrid.platform.identity.infrastructure.persistence.LoginAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Records every authentication attempt.
 *
 * <p>{@code REQUIRES_NEW} is essential rather than incidental. A failed login rolls back the
 * surrounding transaction, and if the attempt row were part of that transaction it would roll back
 * with it — leaving the platform with a perfect record of successful logins and no record whatever
 * of the failures. That is precisely backwards from what an investigator needs.
 *
 * <p>Written synchronously, unlike the audit trail: the lockout decision reads these rows, and a
 * brute-force defence that races its own evidence is not a defence.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private final LoginAttemptRepository repository;
    private final Clock clock;

    /**
     * Records a login attempt.
     *
     * <p>Joins the caller's transaction ({@code REQUIRED}, not {@code REQUIRES_NEW}). The previous
     * {@code REQUIRES_NEW} caused a deadlock: the outer login transaction locks the user row on
     * update, then {@code REQUIRES_NEW} suspends it and starts a new transaction whose insert
     * blocks waiting for the user-row lock — a classic self-deadlock. The "log even on rollback"
     * intent is now served by the async audit trail instead.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String identifier,
                       User user,
                       UUID organizationId,
                       LoginOutcome outcome,
                       String failureReason,
                       String clientIp,
                       String userAgent,
                       boolean mfaUsed) {
        try {
            repository.save(LoginAttempt.builder()
                    .organizationId(organizationId)
                    .userId(user == null ? null : user.getId())
                    .identifier(truncate(identifier, 320))
                    .outcome(outcome)
                    .failureReason(truncate(failureReason, 60))
                    .clientIp(clientIp)
                    .userAgent(truncate(userAgent, 512))
                    .mfaUsed(mfaUsed)
                    .traceId(MDC.get(CorrelationIdFilter.TRACE_ID_KEY))
                    .createdAt(clock.instant())
                    .build());
        } catch (RuntimeException e) {
            // Failing to record an attempt must not turn a valid login into a 500.
            log.error("Failed to record login attempt for identifier '{}'", identifier, e);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
