package com.aquagrid.platform.identity.infrastructure.persistence;

import com.aquagrid.platform.identity.domain.model.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, UUID> {

    /**
     * Resolves permission rows by code. Used when materialising role grants: a request lists codes,
     * the service resolves them to rows, and any code with no row is rejected (an unknown permission
     * is almost always a typo that would silently widen or narrow authorisation).
     */
    @Query("""
            SELECT p FROM Permission p
            WHERE p.code IN :codes
            """)
    List<Permission> findAllByCodeIn(@Param("codes") Collection<String> codes);

    List<Permission> findAllByOrderByDomainAscResourceAscActionAsc();
}
