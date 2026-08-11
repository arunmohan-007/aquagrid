package com.aquagrid.platform.common.tenant;

import com.aquagrid.platform.common.domain.TenantAwareEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Enables the Hibernate tenant filter for the duration of every transactional service call.
 *
 * <p>Ordered to run <em>inside</em> the transaction advice so that a session already exists;
 * otherwise the filter would be applied to a session that is discarded immediately.
 */
@Aspect
@Component
@Order(100)
public class TenantFilterAspect {

    @PersistenceContext
    private EntityManager entityManager;

    @Around("@within(org.springframework.transaction.annotation.Transactional) "
            + "|| @annotation(org.springframework.transaction.annotation.Transactional)")
    public Object applyTenantFilter(ProceedingJoinPoint joinPoint) throws Throwable {
        UUID tenantId = TenantContext.current().orElse(null);
        if (tenantId != null && entityManager.isJoinedToTransaction()) {
            try {
                // The filter is registered by @FilterDef on TenantAwareEntity. It is a safety net,
                // not the primary tenant control (repositories scope explicitly), so if no entity
                // in the session's persistence unit registered it, we skip rather than fail.
                entityManager.unwrap(Session.class)
                        .enableFilter(TenantAwareEntity.TENANT_FILTER)
                        .setParameter(TenantAwareEntity.TENANT_PARAM, tenantId);
            } catch (org.hibernate.UnknownFilterException e) {
                // No entity extends TenantAwareEntity yet — the filter is not registered. Proceed
                // without it; repository-level predicates still enforce tenant isolation.
            }
        }
        return joinPoint.proceed();
    }
}
