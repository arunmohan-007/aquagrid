package com.aquagrid.platform.gis.web.dto;

import com.aquagrid.platform.gis.domain.model.PumpStation;
import com.aquagrid.platform.gis.domain.model.Reservoir;
import com.aquagrid.platform.gis.domain.model.Tank;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outbound + request DTOs for the asset type tables (Modules 8, 9, 10).
 *
 * <p>Each type carries the supertype's identity ({@code assetId}) plus its engineering fields. The
 * request records include the base {@link AssetDto.AssetRequest} fields so a type can be created in
 * one call (asset + type row together) rather than two round trips.
 */
public final class AssetTypeDtos {

    private AssetTypeDtos() {
    }

    // ---- Tank ----------------------------------------------------------------------------------

    @Schema(name = "Tank")
    @Builder
    public record TankDto(
            UUID assetId,
            BigDecimal capacityM3,
            BigDecimal currentLevelM3,
            BigDecimal baseElevationM,
            BigDecimal overflowElevationM,
            BigDecimal inletElevationM,
            String tankType,
            String material
    ) {
        public static TankDto from(Tank t) {
            return TankDto.builder()
                    .assetId(t.getAssetId())
                    .capacityM3(t.getCapacityM3())
                    .currentLevelM3(t.getCurrentLevelM3())
                    .baseElevationM(t.getBaseElevationM())
                    .overflowElevationM(t.getOverflowElevationM())
                    .inletElevationM(t.getInletElevationM())
                    .tankType(t.getTankType())
                    .material(t.getMaterial())
                    .build();
        }
    }

    @Schema(name = "TankRequest")
    public record TankRequest(
            BigDecimal capacityM3,
            String tankType,
            String material,
            BigDecimal baseElevationM,
            BigDecimal overflowElevationM,
            BigDecimal inletElevationM
    ) {
    }

    // ---- Reservoir -----------------------------------------------------------------------------

    @Schema(name = "Reservoir")
    @Builder
    public record ReservoirDto(
            UUID assetId,
            BigDecimal maxCapacityM3,
            BigDecimal currentVolumeM3,
            String sourceType,
            BigDecimal surfaceAreaM2,
            BigDecimal maxDepthM,
            BigDecimal intakeElevationM
    ) {
        public static ReservoirDto from(Reservoir r) {
            return ReservoirDto.builder()
                    .assetId(r.getAssetId())
                    .maxCapacityM3(r.getMaxCapacityM3())
                    .currentVolumeM3(r.getCurrentVolumeM3())
                    .sourceType(r.getSourceType())
                    .surfaceAreaM2(r.getSurfaceAreaM2())
                    .maxDepthM(r.getMaxDepthM())
                    .intakeElevationM(r.getIntakeElevationM())
                    .build();
        }
    }

    @Schema(name = "ReservoirRequest")
    public record ReservoirRequest(
            BigDecimal maxCapacityM3,
            String sourceType,
            BigDecimal surfaceAreaM2,
            BigDecimal maxDepthM,
            BigDecimal intakeElevationM
    ) {
    }

    // ---- Pump Station -------------------------------------------------------------------------

    @Schema(name = "PumpStation")
    @Builder
    public record PumpStationDto(
            UUID assetId,
            int pumpCount,
            BigDecimal ratedFlowLpm,
            BigDecimal ratedHeadM,
            BigDecimal ratedPowerKw,
            List<String> pumpStates,
            List<Map<String, Object>> pumpCurve,
            BigDecimal suctionElevationM,
            BigDecimal dischargeElevationM
    ) {
        public static PumpStationDto from(PumpStation p) {
            return PumpStationDto.builder()
                    .assetId(p.getAssetId())
                    .pumpCount(p.getPumpCount())
                    .ratedFlowLpm(p.getRatedFlowLpm())
                    .ratedHeadM(p.getRatedHeadM())
                    .ratedPowerKw(p.getRatedPowerKw())
                    .pumpStates(p.getPumpStates())
                    .pumpCurve(p.getPumpCurve())
                    .suctionElevationM(p.getSuctionElevationM())
                    .dischargeElevationM(p.getDischargeElevationM())
                    .build();
        }
    }

    @Schema(name = "PumpStationRequest")
    public record PumpStationRequest(
            int pumpCount,
            BigDecimal ratedFlowLpm,
            BigDecimal ratedHeadM,
            BigDecimal ratedPowerKw,
            BigDecimal suctionElevationM,
            BigDecimal dischargeElevationM,
            List<Map<String, Object>> pumpCurve
    ) {
    }
}
