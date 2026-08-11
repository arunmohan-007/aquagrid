package com.aquagrid.platform.common.error;

import lombok.Getter;

import java.util.Map;

/**
 * Base type for every expected, domain-meaningful failure.
 *
 * <p>Carries an {@link ErrorCode} (contract), an optional human message and an optional properties
 * map that is surfaced verbatim in the RFC 7807 response — used for things like
 * {@code retryAfterSeconds} or {@code lockedUntil} that a client must act upon.
 *
 * <p>Stack traces are suppressed: these are control flow, not defects, and they are raised at
 * volume on the authentication path.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final transient ErrorCode errorCode;
    private final transient Map<String, Object> properties;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), Map.of(), null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of(), null);
    }

    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> properties) {
        this(errorCode, message, properties, null);
    }

    public BusinessException(ErrorCode errorCode,
                             String message,
                             Map<String, Object> properties,
                             Throwable cause) {
        super(message, cause, false, false);
        this.errorCode = errorCode;
        this.properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
