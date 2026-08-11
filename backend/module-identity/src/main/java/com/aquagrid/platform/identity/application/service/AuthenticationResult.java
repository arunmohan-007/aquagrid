package com.aquagrid.platform.identity.application.service;

import com.aquagrid.platform.identity.web.dto.AuthResponses;
import org.springframework.http.ResponseCookie;

/**
 * What an authentication use case produces: a JSON body and, when a session was created or rotated,
 * the refresh cookie to set.
 *
 * <p>The cookie is returned rather than written directly, so the service layer stays free of
 * {@code HttpServletResponse}. Only the controller touches the servlet API, which keeps these use
 * cases callable from a future gRPC or message-driven entry point.
 */
public record AuthenticationResult(AuthResponses.Authentication body, ResponseCookie refreshCookie) {

    public static AuthenticationResult of(AuthResponses.Authentication body, ResponseCookie cookie) {
        return new AuthenticationResult(body, cookie);
    }

    public static AuthenticationResult bodyOnly(AuthResponses.Authentication body) {
        return new AuthenticationResult(body, null);
    }
}
