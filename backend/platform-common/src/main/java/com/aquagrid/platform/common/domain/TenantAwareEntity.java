package com.aquagrid.platform.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.util.UUID;

/**
 * Base class for every tenant-owned aggregate.
 *
 * <p>The {@code tenantFilter} is enabled on each Hibernate session by
 * {@code TenantFilterAspect}, which rewrites every {@code SELECT} with
 * {@code organization_id = :tenantId}. This is a safety net, not the primary control: repositories
 * still scope their queries explicitly. It exists so that a forgotten predicate is a no-op rather
 * than a cross-tenant data leak.
 */
@Getter
@Setter
@MappedSuperclass
@FilterDef(name = TenantAwareEntity.TENANT_FILTER,
        parameters = @ParamDef(name = TenantAwareEntity.TENANT_PARAM, type = UUID.class))
@Filter(name = TenantAwareEntity.TENANT_FILTER,
        condition = "organization_id = :tenantId")
public abstract class TenantAwareEntity extends AuditableEntity {

    public static final String TENANT_FILTER = "tenantFilter";
    public static final String TENANT_PARAM = "tenantId";

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;
}
