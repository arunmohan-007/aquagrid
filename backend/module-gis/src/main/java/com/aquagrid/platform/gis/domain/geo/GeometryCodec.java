package com.aquagrid.platform.gis.domain.geo;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GeoJSON → JTS geometry parser.
 *
 * <p>The API accepts geometry as GeoJSON (the modern, web-native convention). Hibernate Spatial maps
 * JTS to PostGIS, so this parser is the single place where the wire format becomes the entity value.
 * WKT is a forgotten dialect for browser-side work; GeoJSON is what Leaflet, OpenLayers and every
 * modern tool emits.
 *
 * <p>Supports Point, LineString, Polygon and MultiPoint — the geometry types assets use. Adding
 * MultiLineString/MultiPolygon is a switch-case, not a redesign.
 *
 * <p>SRID is forced to 4326: the API contract is "geometry is lon/lat", and asserting it here is
 * cheaper than debugging a silently-reprojected geometry later.
 */
public final class GeometryCodec {

    private static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    private GeometryCodec() {
    }

    @SuppressWarnings("unchecked")
    public static Geometry fromGeoJson(Map<String, Object> geoJson) {
        if (geoJson == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Geometry is required.");
        }
        String type = (String) geoJson.get("type");
        if (type == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "GeoJSON 'type' is required.");
        }
        return switch (type) {
            case "Point" -> point((List<Number>) geoJson.get("coordinates"));
            case "LineString" -> lineString((List<List<Number>>) geoJson.get("coordinates"));
            case "Polygon" -> polygon((List<List<List<Number>>>) geoJson.get("coordinates"));
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Unsupported geometry type: " + type + ". Use Point, LineString or Polygon.");
        };
    }

    /** GeoJSON → [longitude, latitude]. Reversing this is the classic "everything is in the sea" bug. */
    private static Point point(List<Number> coords) {
        if (coords == null || coords.size() < 2) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Point requires [lon, lat].");
        }
        return FACTORY.createPoint(new Coordinate(coords.get(0).doubleValue(), coords.get(1).doubleValue()));
    }

    private static LineString lineString(List<List<Number>> coords) {
        if (coords == null || coords.size() < 2) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "LineString requires at least 2 points.");
        }
        return FACTORY.createLineString(toCoordinates(coords));
    }

    private static Polygon polygon(List<List<List<Number>>> rings) {
        if (rings == null || rings.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Polygon requires at least one ring.");
        }
        org.locationtech.jts.geom.LinearRing shell = FACTORY.createLinearRing(toCoordinates(rings.get(0)));
        org.locationtech.jts.geom.LinearRing[] holes = new org.locationtech.jts.geom.LinearRing[rings.size() - 1];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = FACTORY.createLinearRing(toCoordinates(rings.get(i)));
        }
        return FACTORY.createPolygon(shell, holes);
    }

    private static Coordinate[] toCoordinates(List<List<Number>> coords) {
        List<Coordinate> out = new ArrayList<>(coords.size());
        for (List<Number> c : coords) {
            out.add(new Coordinate(c.get(0).doubleValue(), c.get(1).doubleValue()));
        }
        return out.toArray(new Coordinate[0]);
    }

    /** Serialises a geometry back to a GeoJSON Point coordinates array, for the read path. */
    public static double[] toLonLat(Point point) {
        return point == null ? null : new double[]{point.getX(), point.getY()};
    }
}
