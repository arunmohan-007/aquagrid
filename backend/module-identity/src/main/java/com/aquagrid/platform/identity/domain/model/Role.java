package com.aquagrid.platform.identity.domain.model;

import com.aquagrid.platform.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A named bundle of permissions.
 *
 * <p>{@code organizationId == null} marks a system role shipped with the product, shared by every
 * tenant and immutable through the API. A non-null value marks a tenant-authored custom role.
 *
 * <p>Permissions are fetched lazily and only ever loaded during token issuance, which happens once
 * per login rather than once per request — the reason authorisation can stay stateless afterwards.
 */
@Getter
@Setter
@Entity
@Table(name = "roles", schema = "identity")
public class Role extends AuditableEntity {

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "code", nullable = false, length = 60)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 300)
    private String description;

    @Column(name = "is_system", nullable = false)
    private boolean system;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "role_permissions",
            schema = "identity",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new LinkedHashSet<>();
}
