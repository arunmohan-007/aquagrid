package com.aquagrid.platform.iot.receiver.application.pipeline;

import com.aquagrid.platform.common.tenant.TenantContext;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.spi.ReceiverStage;
import org.springframework.stereotype.Component;

/**
 * Establishes what is known about the tenant before the device is resolved.
 *
 * <p>Which, honestly, is usually nothing — and saying so is the point of the stage existing.
 *
 * <p>The platform's tenancy invariant is that {@code organizationId} comes from the authenticated
 * principal and never from a request parameter. Device ingestion is the one path where there is no
 * authenticated user: a gateway delivers bytes carrying a radio identifier and nothing else. The
 * invariant is therefore satisfied differently here — the tenant is pinned from the resolved device
 * row in {@link DeviceResolutionStage}, which is the platform's own record of who owns that device
 * and is not attacker-influenced.
 *
 * <p>What this stage does is narrower: where the credential was itself tenant-scoped (a device
 * token, a signature verified against a device's key), the tenant is already known and is bound to
 * {@link TenantContext} now, so that the queries the next stage runs are covered by the Hibernate
 * tenant filter rather than relying on the explicit predicate alone. Where it is not known, nothing
 * is bound and resolution proceeds cross-tenant by design.
 *
 * <p>It deliberately does <b>not</b> read a tenant from the packet. A stage that did would be the
 * single line that converts this from a safe design into a cross-tenant write primitive.
 */
@Component
public class TenantResolutionStage implements ReceiverStage {

    @Override
    public String name() {
        return "TENANT_RESOLUTION";
    }

    @Override
    public Decision execute(ReceptionContext context) {
        if (context.getTenantId() != null) {
            TenantContext.set(context.getTenantId());
            context.note("tenantSource", "credential");
        } else {
            // Explicitly cleared rather than left alone. These threads are pooled — a virtual
            // thread per packet, but the servlet container's request threads are not — and an
            // inherited tenant from an unrelated unit of work is exactly the leak the filter exists
            // to prevent.
            TenantContext.clear();
            context.note("tenantSource", "device");
        }
        return Decision.CONTINUE;
    }

    @Override
    public int getOrder() {
        return Stages.TENANT_RESOLUTION;
    }
}
