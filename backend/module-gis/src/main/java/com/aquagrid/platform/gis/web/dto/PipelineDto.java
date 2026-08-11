package com.aquagrid.platform.gis.web.dto;

import com.aquagrid.platform.gis.domain.model.Pipeline;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Outbound pipeline representation + create/update request. */
public final class PipelineDto {

    private PipelineDto() {
    }

    @Schema(name = "Pipeline")
    @Builder
    public record PipelineDetailDto(
            UUID assetId,
            UUID fromNodeId,
            UUID toNodeId,
            BigDecimal diameterMm,
            String material,
            BigDecimal lengthM,
            String flowDirection,
            BigDecimal roughness,
            BigDecimal pressureClass
    ) {
        public static PipelineDetailDto from(Pipeline p) {
            return PipelineDetailDto.builder()
                    .assetId(p.getAssetId())
                    .fromNodeId(p.getFromNodeId())
                    .toNodeId(p.getToNodeId())
                    .diameterMm(p.getDiameterMm())
                    .material(p.getMaterial())
                    .lengthM(p.getLengthM())
                    .flowDirection(p.getFlowDirection())
                    .roughness(p.getRoughness())
                    .pressureClass(p.getPressureClass())
                    .build();
        }
    }

    /**
     * Create/update request. The geometry is a GeoJSON LineString of [lon,lat] points; the service
     * snaps the first/last points to network nodes automatically, so the caller draws a line and the
     * graph connects itself.
     */
    @Schema(name = "PipelineRequest")
    public record PipelineRequest(
            BigDecimal diameterMm,
            String material,
            String flowDirection,
            BigDecimal roughness,
            BigDecimal pressureClass,
            @Schema(description = "GeoJSON LineString coordinates: [[lon,lat], [lon,lat], ...]")
            List<List<Double>> coordinates
    ) {
    }

    @Schema(name = "TraceRequest")
    public record TraceRequest(
            UUID sourceNodeId,
            @Schema(description = "UP or DOWN") String direction,
            @Schema(description = "Max trace distance in metres", example = "5000") double maxDistanceM
    ) {
    }

    @Schema(name = "TraceVertex")
    public record TraceVertexDto(
            String nodeId,
            double costM,
            String geometryWkt
    ) {
    }
}
