package com.aquagrid.platform.identity.application.mapper;

import com.aquagrid.platform.identity.domain.model.Organization;
import com.aquagrid.platform.identity.domain.model.Permission;
import com.aquagrid.platform.identity.domain.model.Role;
import com.aquagrid.platform.identity.domain.model.User;
import com.aquagrid.platform.identity.web.dto.AuthResponses;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Point;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Entity → DTO translation.
 *
 * <p>MapStruct generates the field copying at compile time, so adding a field to {@link User}
 * without handling it here is a build failure rather than a silently missing value in production.
 * The interesting parts — flattening roles to permissions and turning PostGIS geometry into a map
 * bootstrap payload — are written explicitly below, because generated code should never be asked
 * to express a business decision.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface UserMapper {

    @Mapping(target = "status", expression = "java(user.getStatus().name())")
    @Mapping(target = "roles", expression = "java(roleCodes(user))")
    @Mapping(target = "permissions", expression = "java(permissionCodes(user))")
    @Mapping(target = "timezone", expression = "java(user.effectiveTimezone())")
    @Mapping(target = "locale", expression = "java(user.effectiveLocale())")
    @Mapping(target = "organization", expression = "java(toOrganizationSummary(user.getOrganization()))")
    AuthResponses.CurrentUser toCurrentUser(User user);

    default Set<String> roleCodes(User user) {
        return user.getRoles().stream()
                .map(Role::getCode)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * The user's effective permissions: the union across all their roles.
     *
     * <p>Sorted, because this set goes into the JWT and a stable ordering makes tokens diffable in
     * logs and keeps token bytes deterministic for a given grant.
     */
    default Set<String> permissionCodes(User user) {
        return user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(Permission::getCode)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    default AuthResponses.OrganizationSummary toOrganizationSummary(Organization organization) {
        if (organization == null) {
            return null;
        }
        return AuthResponses.OrganizationSummary.builder()
                .id(organization.getId())
                .code(organization.getCode())
                .name(organization.getName())
                .type(organization.getType())
                .timezone(organization.getTimezone())
                .locale(organization.getLocale())
                .currencyCode(organization.getCurrencyCode())
                .unitSystem(organization.getUnitSystem())
                .defaultCenter(toLonLat(organization.getCentroid()))
                .defaultZoom((int) organization.getDefaultZoom())
                .boundaryBbox(toBbox(organization))
                .build();
    }

    /** GeoJSON axis order: longitude first. Reversing this is the classic "everything is in the sea" bug. */
    private static double[] toLonLat(Point point) {
        return point == null ? null : new double[]{point.getX(), point.getY()};
    }

    private static double[] toBbox(Organization organization) {
        if (organization.getBoundary() == null || organization.getBoundary().isEmpty()) {
            return null;
        }
        Envelope envelope = organization.getBoundary().getEnvelopeInternal();
        return new double[]{envelope.getMinX(), envelope.getMinY(),
                envelope.getMaxX(), envelope.getMaxY()};
    }
}
