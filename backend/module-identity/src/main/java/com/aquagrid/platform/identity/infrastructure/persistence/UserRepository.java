package com.aquagrid.platform.identity.infrastructure.persistence;

import com.aquagrid.platform.identity.domain.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Loads a user by globally-unique email, with organisation, roles and permissions in one query.
     *
     * <p>The entity graph is not an optimisation detail — without it, minting a token would issue
     * one query per role and one per role's permissions on the login path. {@code LEFT JOIN FETCH}
     * over two collections is safe here because the result is deduplicated by identity and the
     * cardinality is small (a user has a handful of roles).
     */
    @EntityGraph(attributePaths = {"organization", "roles", "roles.permissions"})
    Optional<User> findByEmailIgnoreCase(String email);

    /**
     * All users sharing a username. Username is unique only within a tenant (see
     * {@code uq_users_org_username}), so with no organisation code to disambiguate, the caller must
     * treat more than one match as unresolvable rather than picking one arbitrarily.
     */
    @EntityGraph(attributePaths = {"organization", "roles", "roles.permissions"})
    @Query("""
            SELECT u FROM User u
            WHERE lower(u.username) = lower(:username)
            """)
    java.util.List<User> findAllByUsernameIgnoreCase(@Param("username") String username);

    @EntityGraph(attributePaths = {"organization", "roles", "roles.permissions"})
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdWithAuthorities(@Param("id") UUID id);

    boolean existsByEmailIgnoreCase(String email);

    @Query("""
            SELECT count(u) > 0 FROM User u
            WHERE u.organization.id = :organizationId
              AND lower(u.username) = lower(:username)
              AND (:excludingId IS NULL OR u.id <> :excludingId)
            """)
    boolean existsByUsernameInOrganization(@Param("organizationId") UUID organizationId,
                                           @Param("username") String username,
                                           @Param("excludingId") UUID excludingId);

    @Query("""
            SELECT count(u) > 0 FROM User u
            WHERE lower(u.email) = lower(:email)
              AND (:excludingId IS NULL OR u.id <> :excludingId)
            """)
    boolean existsByEmail(@Param("email") String email, @Param("excludingId") UUID excludingId);

    /**
     * Tenant-scrolled user list. The {@code organization_id} predicate is the first join key because
     * tenancy is enforced in depth here (Hibernate filter + this predicate + the JWT claim).
     *
     * <p>No {@code roles} fetch join here deliberately: Hibernate refuses to paginate a collection
     * fetch join ({@code fail_on_pagination_over_collection_fetch} in application.yml), since doing
     * so would silently paginate in memory over the joined rows. Callers that need roles load them
     * for the returned page via {@link #findAllWithRolesByIdIn}.
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.organization.id = :organizationId
              AND (cast(:status as string) IS NULL OR cast(u.status as string) = cast(:status as string))
              AND (cast(:search as string) IS NULL
                   OR lower(u.fullName) LIKE lower(concat('%', cast(:search as string), '%'))
                   OR lower(u.email) LIKE lower(concat('%', cast(:search as string), '%'))
                   OR lower(u.username) LIKE lower(concat('%', cast(:search as string), '%')))
            """)
    Page<User> findForTenant(@Param("organizationId") UUID organizationId,
                             @Param("status") String status,
                             @Param("search") String search,
                             Pageable pageable);

    /** Batch-loads roles for a known set of users (e.g. one page) without pagination. */
    @EntityGraph(attributePaths = {"roles"})
    @Query("SELECT u FROM User u WHERE u.id IN :ids")
    java.util.List<User> findAllWithRolesByIdIn(@Param("ids") java.util.Collection<UUID> ids);

    @Query("SELECT COUNT(u) FROM User u")
    long countAllUsers();

    /** Users currently holding a given role — blocks role deletion while the role is in use. */
    @Query("""
            SELECT count(u) FROM User u JOIN u.roles r
            WHERE r.id = :roleId
            """)
    long countByRole(@Param("roleId") UUID roleId);
}
