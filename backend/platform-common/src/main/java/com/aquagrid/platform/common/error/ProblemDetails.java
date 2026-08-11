package com.aquagrid.platform.common.error;

import com.aquagrid.platform.common.web.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;

/**
 * Builds the platform's RFC 7807 error document.
 *
 * <p>Extracted from the exception handler so that every advice — including the security advice in
 * {@code platform-security} — produces byte-identical error shapes. Clients parse one contract.
 */
public final class ProblemDetails {

    public static final String DOC_BASE = "https://docs.aquagrid.com/errors/";

    private ProblemDetails() {
    }

    public static ProblemDetail of(HttpStatus status, ErrorCode code, String detail, String path) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(DOC_BASE + code.name().toLowerCase().replace('_', '-')));
        problem.setTitle(code.getDefaultMessage());
        problem.setInstance(URI.create(path == null || path.isBlank() ? "/" : path));
        problem.setProperty("code", code.name());
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("traceId", MDC.get(CorrelationIdFilter.TRACE_ID_KEY));
        return problem;
    }

    public static ProblemDetail of(ErrorCode code, String detail, String path) {
        return of(code.getStatus(), code, detail, path);
    }
}
