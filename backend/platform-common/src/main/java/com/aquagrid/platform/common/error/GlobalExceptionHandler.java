package com.aquagrid.platform.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single translation point from exception to HTTP response for the whole platform.
 *
 * <p>Every response is an RFC 7807 {@code application/problem+json} document carrying a stable
 * {@code code}, a {@code traceId} matching the server log line, and — for validation failures — a
 * per-field breakdown.
 *
 * <p>Two rules are enforced here and are not negotiable:
 * <ul>
 *   <li>Internal failures never leak their message, cause or stack trace to the caller. The caller
 *       receives a {@code traceId}; support correlates it against the log.</li>
 *   <li>Database constraint names are never echoed, because they disclose the schema.</li>
 * </ul>
 *
 * <p>Ordered last so that module-specific advices (e.g. the security advice) win where they
 * overlap.
 */
@Slf4j
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusiness(BusinessException ex,
                                                        HttpServletRequest request) {
        ErrorCode code = ex.getErrorCode();
        ProblemDetail problem =
                ProblemDetails.of(code, ex.getMessage(), request.getRequestURI());
        ex.getProperties().forEach(problem::setProperty);

        if (code.getStatus().is5xxServerError()) {
            log.error("Business failure {} on {}", code, request.getRequestURI(), ex);
        } else {
            log.debug("Business failure {} on {}: {}", code, request.getRequestURI(), ex.getMessage());
        }
        return ResponseEntity.status(code.getStatus())
                .headers(retryAfterHeaders(ex))
                .body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex,
                                                                    HttpServletRequest request) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            errors.add(Map.of(
                    "field", String.valueOf(violation.getPropertyPath()),
                    "message", String.valueOf(violation.getMessage())));
        }
        ProblemDetail problem = ProblemDetails.of(ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.getDefaultMessage(), request.getRequestURI());
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                               HttpServletRequest request) {
        log.info("Optimistic lock conflict on {}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetails.of(ErrorCode.CONCURRENT_MODIFICATION,
                        ErrorCode.CONCURRENT_MODIFICATION.getDefaultMessage(),
                        request.getRequestURI()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex,
                                                              HttpServletRequest request) {
        log.warn("Data integrity violation on {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetails.of(ErrorCode.RESOURCE_CONFLICT,
                        "The operation conflicts with existing data", request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                             HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ProblemDetails.of(ErrorCode.VALIDATION_FAILED,
                "Parameter '%s' has an invalid value".formatted(ex.getName()),
                request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ProblemDetails.of(ErrorCode.INTERNAL_ERROR,
                        ErrorCode.INTERNAL_ERROR.getDefaultMessage(), request.getRequestURI()));
    }

    // --- Spring MVC framework exceptions, normalised onto the same contract ---------------------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                   HttpHeaders headers,
                                                                   HttpStatusCode status,
                                                                   WebRequest request) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", fieldError.getField());
            entry.put("message", fieldError.getDefaultMessage());
            errors.add(entry);
        }
        ex.getBindingResult().getGlobalErrors().forEach(globalError ->
                errors.add(Map.of("field", globalError.getObjectName(),
                        "message", String.valueOf(globalError.getDefaultMessage()))));

        ProblemDetail problem = ProblemDetails.of(ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.getDefaultMessage(), path(request));
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * Overridden rather than declared as a separate {@code @ExceptionHandler}: since Spring 6.1
     * {@link ResponseEntityExceptionHandler} already claims this exception, and declaring it twice
     * makes the handler mapping ambiguous and fails context startup.
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ProblemDetails.of(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.VALIDATION_FAILED,
                        "The uploaded file exceeds the maximum permitted size", path(request)));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                   HttpHeaders headers,
                                                                   HttpStatusCode status,
                                                                   WebRequest request) {
        return ResponseEntity.badRequest().body(ProblemDetails.of(ErrorCode.MALFORMED_REQUEST,
                ErrorCode.MALFORMED_REQUEST.getDefaultMessage(), path(request)));
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest().body(ProblemDetails.of(ErrorCode.VALIDATION_FAILED,
                "Required parameter '%s' is missing".formatted(ex.getParameterName()),
                path(request)));
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ProblemDetails.of(ErrorCode.METHOD_NOT_ALLOWED,
                        ErrorCode.METHOD_NOT_ALLOWED.getDefaultMessage(), path(request)));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ProblemDetails.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                        ErrorCode.UNSUPPORTED_MEDIA_TYPE.getDefaultMessage(), path(request)));
    }

    @Override
    protected ResponseEntity<Object> handleNoHandlerFoundException(NoHandlerFoundException ex,
                                                                    HttpHeaders headers,
                                                                    HttpStatusCode status,
                                                                    WebRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ProblemDetails.of(ErrorCode.RESOURCE_NOT_FOUND,
                        "No handler for the requested path", path(request)));
    }

    // --- helpers --------------------------------------------------------------------------------

    private HttpHeaders retryAfterHeaders(BusinessException ex) {
        HttpHeaders headers = new HttpHeaders();
        if (ex.getProperties().get("retryAfterSeconds") instanceof Number seconds) {
            headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(seconds.longValue()));
        }
        return headers;
    }

    private String path(WebRequest request) {
        String description = request.getDescription(false);
        return description.startsWith("uri=") ? description.substring(4) : description;
    }
}
