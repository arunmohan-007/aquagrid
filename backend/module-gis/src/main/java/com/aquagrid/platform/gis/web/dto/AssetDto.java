package com.aquagrid.platform.gis.web.dto;

import com.aquagrid.platform.gis.domain.model.Asset;
import com.aquagrid.platform.gis.domain.model.AssetAttachment;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outbound asset representation.
 *
 * <p>Geometry is serialised to GeoJSON on the read path (the inverse of the GeoJSON accepted on
 * write) so a browser client can pass it straight to OpenLayers without a transform.
 */
@Schema(name = "Asset")
@Builder
public record AssetDto(
        UUID id,
        String assetCode,
        String assetType,
        @Schema(description = "The registry layer this asset belongs to, or null when it predates "
                + "Layer Management and falls back to its asset type's default layer")
        UUID layerId,
        String name,
        String status,
        LocalDate installDate,
        LocalDate decommissionDate,
        @Schema(description = "GeoJSON Point coordinates [lon, lat], or null for non-point geometry")
        double[] coordinates,
        @Schema(description = "Geometry type, e.g. Point/LineString/Polygon")
        String geometryType,
        @Schema(description = "Geodesic length in metres for line geometry; null for points and polygons")
        Double lengthM,
        Map<String, Object> attributes,
        Instant createdAt,
        Instant updatedAt
) {
    public static AssetDto from(Asset asset) {
        Geometry geom = asset.getGeom();
        double[] coords = null;
        if (geom instanceof Point point) {
            coords = new double[]{point.getX(), point.getY()};
        }
        return AssetDto.builder()
                .lengthM(lengthOf(geom))
                .id(asset.getId())
                .assetCode(asset.getAssetCode())
                .assetType(asset.getAssetType().name())
                .layerId(asset.getLayerId())
                .name(asset.getName())
                .status(asset.getStatus().name())
                .installDate(asset.getInstallDate())
                .decommissionDate(asset.getDecommissionDate())
                .coordinates(coords)
                .geometryType(geom.getGeometryType())
                .attributes(asset.getAttributes())
                .createdAt(asset.getCreatedAt())
                .updatedAt(asset.getUpdatedAt())
                .build();
    }

    /**
     * Geodesic length of a line geometry in metres, or null for anything that is not a line.
     *
     * <p>The map's inspection card leads with this number: for a main, "how long is it" is the
     * first question asked and the one an operator cannot answer by looking. It is computed here
     * rather than read from {@code gis.pipelines.length_m} because that table is populated only by
     * the pipeline editor — an imported network has asset rows and geometry but no engineering
     * record, and a card that showed no length for every imported pipe would be useless on exactly
     * the data most utilities start with.
     *
     * <p>Haversine on the 4326 coordinates rather than a planar sum: at Tamil Nadu's latitude a
     * planar length over a 20 km main is out by hundreds of metres. This mirrors the client-side
     * measure tool, so the card and the ruler agree.
     */
    private static Double lengthOf(Geometry geom) {
        if (geom == null) return null;
        String type = geom.getGeometryType();
        if (!"LineString".equals(type) && !"MultiLineString".equals(type)) return null;

        double total = 0;
        // Per part: concatenating a MultiLineString's coordinates would add a phantom segment
        // bridging the gap between two disjoint parts, inflating the length of a split main.
        for (int part = 0; part < geom.getNumGeometries(); part++) {
            Coordinate[] points = geom.getGeometryN(part).getCoordinates();
            for (int i = 1; i < points.length; i++) {
                total += haversineM(points[i - 1], points[i]);
            }
        }
        return total;
    }

    /** Great-circle distance between two lon/lat coordinates, in metres. */
    private static double haversineM(Coordinate a, Coordinate b) {
        final double earthRadiusM = 6_371_008.8;
        double dLat = Math.toRadians(b.y - a.y);
        double dLon = Math.toRadians(b.x - a.x);
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(a.y)) * Math.cos(Math.toRadians(b.y))
                * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * earthRadiusM * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    @Schema(name = "Attachment")
    public record AttachmentSummary(
            UUID id,
            String fileName,
            String contentType,
            long sizeBytes,
            Instant uploadedAt
    ) {
        public static AttachmentSummary from(AssetAttachment a) {
            return new AttachmentSummary(a.getId(), a.getFileName(), a.getContentType(),
                    a.getSizeBytes(), a.getUploadedAt());
        }
    }

    /** Create/update request. Geometry arrives as GeoJSON. */
    @Schema(name = "AssetRequest")
    public record AssetRequest(
            String assetCode,
            String assetType,
            String name,
            String status,
            LocalDate installDate,
            LocalDate decommissionDate,
            @Schema(description = "GeoJSON geometry (Point/LineString/Polygon), coordinates [lon, lat]")
            Map<String, Object> geometry,
            Map<String, Object> attributes
    ) {
    }
}
