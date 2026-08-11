package com.aquagrid.platform.gis.web.dto;

import com.aquagrid.platform.gis.domain.model.Valve;
import com.aquagrid.platform.gis.domain.model.ValveOperation;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class ValveDto {

    private ValveDto() {
    }

    @Schema(name = "Valve")
    @Builder
    public record ValveDetailDto(
            UUID assetId,
            UUID nodeId,
            String valveType,
            BigDecimal diameterMm,
            String status,
            String normalState,
            BigDecimal pressureSetpointBar,
            Integer turnsToOperate,
            String manufacturer,
            String modelNumber
    ) {
        public static ValveDetailDto from(Valve v) {
            return ValveDetailDto.builder()
                    .assetId(v.getAssetId())
                    .nodeId(v.getNodeId())
                    .valveType(v.getValveType())
                    .diameterMm(v.getDiameterMm())
                    .status(v.getStatus())
                    .normalState(v.getNormalState())
                    .pressureSetpointBar(v.getPressureSetpointBar())
                    .turnsToOperate(v.getTurnsToOperate())
                    .manufacturer(v.getManufacturer())
                    .modelNumber(v.getModelNumber())
                    .build();
        }
    }

    @Schema(name = "ValveRequest")
    public record ValveRequest(
            UUID nodeId,
            String valveType,
            BigDecimal diameterMm,
            String status,
            String normalState,
            BigDecimal pressureSetpointBar,
            Integer turnsToOperate,
            String manufacturer,
            String modelNumber
    ) {
    }

    @Schema(name = "ValveOperateRequest")
    public record OperateRequest(
            @Schema(description = "OPEN or CLOSED") String toState,
            String reason,
            UUID workOrderId
    ) {
    }

    @Schema(name = "ValveOperation")
    public record OperationDto(
            Long id,
            UUID valveAssetId,
            String fromState,
            String toState,
            UUID operatedBy,
            Instant operatedAt,
            String reason
    ) {
        public static OperationDto from(ValveOperation o) {
            return new OperationDto(o.getId(), o.getValveAssetId(), o.getFromState(),
                    o.getToState(), o.getOperatedBy(), o.getOperatedAt(), o.getReason());
        }
    }
}
