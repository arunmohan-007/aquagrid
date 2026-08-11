package com.aquagrid.platform.security.config;

import com.aquagrid.platform.common.tenant.TenantContext;
import com.aquagrid.platform.security.core.SecurityUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Binds the authenticated caller's tenant to the current thread, and clears it afterwards.
 *
 * <p>Runs after the bearer-token filter, so a principal is already available. The tenant is taken
 * from the signed {@code org} claim and never from a header or a request parameter — a
 * client-supplied tenant id is a cross-tenant read waiting to happen.
 *
 * <p>The {@code finally} block is not optional: servlet containers pool threads, so a leaked
 * {@link ThreadLocal} would silently hand one tenant's context to the next tenant's request.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    private static final String MDC_ORG = "orgId";
    private static final String MDC_USER = "userId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            SecurityUtils.currentPrincipal().ifPresent(principal -> {
                if (principal.organizationId() != null) {
                    TenantContext.set(principal.organizationId());
                    MDC.put(MDC_ORG, principal.organizationId().toString());
                }
                if (principal.userId() != null) {
                    MDC.put(MDC_USER, principal.userId().toString());
                }
            });
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove(MDC_ORG);
            MDC.remove(MDC_USER);
        }
    }
}
