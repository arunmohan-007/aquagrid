package com.aquagrid.platform.identity.domain.model;

import com.aquagrid.platform.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;

/**
 * The tenant root.
 *
 * <p>Physically owned by the platform kernel migration ({@code core.organizations}) and mapped here
 * because authentication is the first thing that needs it. Module 3 extends this class with
 * hierarchy management, branding and licensing rather than introducing a parallel entity.
 *
 * <p>{@code centroid} and {@code boundary} are present from the first migration deliberately: the
 * tenant's default map extent is returned by {@code /auth/me}, so the GIS dashboard opens on the
 * correct area on first paint with no extra round trip, and {@code boundary} becomes the predicate
 * for spatial row-level authorisation later.
 */
@Getter
@Setter
@Entity
@Table(name = "organizations", schema = "core")
public class Organization extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Organization parent;

    // citext, not varchar: tenant codes compare case-insensitively in the database, so the mapping
    // must declare the actual type or schema validation fails against a real PostgreSQL.
    @Column(name = "code", nullable = false, updatable = false, columnDefinition = "citext")
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "legal_name", length = 300)
    private String legalName;

    @Column(name = "type", nullable = false, length = 40)
    private String type;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "contact_email", columnDefinition = "citext")
    private String contactEmail;

    @Column(name = "timezone", nullable = false, length = 60)
    private String timezone;

    @Column(name = "locale", nullable = false, length = 20)
    private String locale;

    // CHAR(3), not VARCHAR(3) — ISO 4217 codes are fixed width and V1001 declares them that way.
    // PostgreSQL reports CHAR as "bpchar", so the JDBC type code is what has to match, not the name.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency_code", nullable = false, length = 3, columnDefinition = "char(3)")
    private String currencyCode;

    @Column(name = "unit_system", nullable = false, length = 20)
    private String unitSystem;

    @Column(name = "centroid", columnDefinition = "geometry(Point,4326)")
    private Point centroid;

    @Column(name = "boundary", columnDefinition = "geometry(MultiPolygon,4326)")
    private MultiPolygon boundary;

    @Column(name = "default_zoom", nullable = false)
    private short defaultZoom;

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
