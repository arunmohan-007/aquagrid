package com.aquagrid.platform.identity.infrastructure.persistence;

import com.aquagrid.platform.identity.domain.model.Role;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RoleRepository extends JpaRepository<Role, UUID> {

    @EntityGraph(attributePaths = "permissions")
    Optional<Role> findByCodeAndOrganizationIdIsNull(String code);

    /** Every system role, with permissions pre-fetched for the role catalogue endpoint. */
    @EntityGraph(attributePaths = "permissions")
    List<Role> findByOrganizationIdIsNullOrderByName();

    /** Every custom role authored by a tenant, with permissions pre-fetched. */
    @EntityGraph(attributePaths = "permissions")
    List<Role> findByOrganizationIdOrderByName(UUID organizationId);

    /**
     * Resolves the roles visible to a tenant: system roles (shared) plus that tenant's custom roles.
     * Used by the role picker when assigning roles to a user.
     */
    @EntityGraph(attributePaths = "permissions")
    @Query("""
            SELECT r FROM Role r
            WHERE r.organizationId IS NULL OR r.organizationId = :organizationId
            ORDER BY r.organizationId NULLS FIRST, r.name
            """)
    List<Role> findVisibleToTenant(@Param("organizationId") UUID organizationId);

    /** Locates a tenant-authored role by code, within the tenant that owns it. */
    Optional<Role> findByCodeAndOrganizationId(String code, UUID organizationId);

    /** Existence check for the unique-within-tenant custom-role code constraint. */
    boolean existsByCodeAndOrganizationId(String code, UUID organizationId);

    @EntityGraph(attributePaths = "permissions")
    Optional<Role> findById(UUID id);
}
