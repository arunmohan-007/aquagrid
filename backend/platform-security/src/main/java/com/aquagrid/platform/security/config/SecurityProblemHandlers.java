package com.aquagrid.platform.security.config;

import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.common.error.ProblemDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Renders authentication and authorisation failures using the same RFC 7807 contract as every
 * other error in the platform.
 *
 * <p>Spring Security's defaults return an empty body with a {@code WWW-Authenticate} header, which
 * forces the SPA to special-case 401/403 and gives the user nothing actionable. These handlers
 * ensure a client parses one error shape everywhere.
 *
 * <p>Note the deliberate vagueness: an expired token, a forged token and a token for a deleted
 * user all produce the same {@code AUTH_TOKEN_INVALID}. Detailed reasons help attackers more than
 * they help clients; the detail goes to the log with the trace id.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityProblemHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ErrorCode code = authException instanceof InvalidBearerTokenException
                ? ErrorCode.AUTH_TOKEN_INVALID
                : ErrorCode.AUTH_REQUIRED;
        log.debug("Authentication entry point triggered for {} {}: {}",
                request.getMethod(), request.getRequestURI(), authException.getMessage());
        write(response, ProblemDetails.of(code, code.getDefaultMessage(), request.getRequestURI()));
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.warn("Access denied for {} {}: {}", request.getMethod(), request.getRequestURI(),
                accessDeniedException.getMessage());
        write(response, ProblemDetails.of(ErrorCode.OPERATION_NOT_PERMITTED,
                "You do not have permission to perform this action", request.getRequestURI()));
    }

    private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
