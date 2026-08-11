package com.aquagrid.platform.gis.domain.enums;

import java.util.Locale;

/**
 * The geometry a layer declares it holds.
 *
 * <p>This is a <em>declaration</em>, not DDL. Every layer's features live in {@code gis.assets},
 * whose {@code geom} column is a bare PostGIS {@code geometry} shared by all of them, so nothing in
 * the database constrains a meter to be a point. The constraint is this enum plus
 * {@link #accepts(String)}, applied on the write path — which is also why it can be changed on a
 * layer without a migration.
 *
 * <p>{@link Family} is the simplification the brief asks for: an ordinary administrator chooses
 * Point, Line or Polygon, and the eight precise types stay available for the GIS specialist who
 * needs to say {@code MULTILINESTRING} because that is what their deliverables carry.
 */
public enum GeometryType {

    POINT(Family.POINT, false),
    MULTIPOINT(Family.POINT, true),
    LINESTRING(Family.LINE, false),
    MULTILINESTRING(Family.LINE, true),
    POLYGON(Family.POLYGON, false),
    MULTIPOLYGON(Family.POLYGON, true),
    /** Anything. The honest declaration for a layer surveyed both ways — see the facility layers. */
    GEOMETRY(Family.ANY, false),
    GEOMETRYCOLLECTION(Family.ANY, true);

    /** The three shapes a non-specialist actually chooses between, plus the unconstrained case. */
    public enum Family { POINT, LINE, POLYGON, ANY }

    private final Family family;
    private final boolean multi;

    GeometryType(Family family, boolean multi) {
        this.family = family;
        this.multi = multi;
    }

    public Family family() {
        return family;
    }

    public boolean isMulti() {
        return multi;
    }

    /** True for the three types the simple chooser offers, so the client can split the list itself. */
    public boolean isSimpleChoice() {
        return this == POINT || this == LINESTRING || this == POLYGON;
    }

    /**
     * Whether a feature of the given JTS geometry type may be written to a layer declaring this one.
     *
     * <p>Matching is by <em>family</em>, not by exact name, and the tolerance is deliberate. A
     * shapefile of tank outlines arrives as {@code Polygon} from one contractor and
     * {@code MultiPolygon} from the next, depending on which tool wrote it and whether any single
     * feature happened to have two rings; a GeoJSON of meters is {@code Point} but the same survey
     * exported from ArcGIS is {@code MultiPoint}. Rejecting on that distinction would fail imports
     * over a difference the operator cannot see and did not cause, while catching none of the
     * mistakes worth catching.
     *
     * <p>What it does catch is the mistake that matters: polygons loaded into the meters layer, a
     * pipe network imported as points. Those break the map, the network trace and every length
     * calculation, and they are always a mis-selected target rather than a tooling artefact.
     *
     * @param jtsGeometryType the value of {@code Geometry.getGeometryType()}, e.g. "MultiLineString"
     */
    public boolean accepts(String jtsGeometryType) {
        if (family == Family.ANY) {
            return true;
        }
        return familyOf(jtsGeometryType) == family;
    }

    /**
     * The family a JTS geometry type name belongs to, or {@link Family#ANY} for a name this does not
     * recognise.
     *
     * <p>An unknown name resolves to ANY rather than to a mismatch: JTS can hand back a type this
     * enum has never heard of (a curve, a surface) and refusing it here would mean a layer rejecting
     * geometry PostGIS is perfectly willing to store, on the strength of a name comparison.
     */
    public static Family familyOf(String jtsGeometryType) {
        if (jtsGeometryType == null) {
            return Family.ANY;
        }
        return switch (jtsGeometryType.toUpperCase(Locale.ROOT)) {
            case "POINT", "MULTIPOINT" -> Family.POINT;
            case "LINESTRING", "MULTILINESTRING", "LINEARRING" -> Family.LINE;
            case "POLYGON", "MULTIPOLYGON" -> Family.POLYGON;
            default -> Family.ANY;
        };
    }

    /**
     * The precise type that best describes a geometry the platform has just read from a file.
     *
     * <p>Used by the import wizard's "create a new layer from this file" path, where the layer's
     * declared type is inferred from the deliverable rather than typed by the operator. The multi
     * variant is chosen whenever the file contains one, because a layer declared {@code POLYGON}
     * that later receives a {@code MultiPolygon} would be rejected by a stricter reading of
     * {@link #accepts} than the one above, and inferring the narrower type from a sample is exactly
     * the guess that turns into that rejection two quarters later.
     */
    public static GeometryType inferFrom(String jtsGeometryType) {
        return switch (familyOf(jtsGeometryType)) {
            case POINT -> MULTIPOINT;
            case LINE -> MULTILINESTRING;
            case POLYGON -> MULTIPOLYGON;
            case ANY -> GEOMETRY;
        };
    }
}
