package com.aquagrid.platform.gis.application.service;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.gis.domain.enums.AssetType;
import com.aquagrid.platform.gis.domain.model.Asset;
import com.aquagrid.platform.gis.domain.model.PumpStation;
import com.aquagrid.platform.gis.domain.model.Reservoir;
import com.aquagrid.platform.gis.domain.model.Tank;
import com.aquagrid.platform.gis.infrastructure.persistence.PumpStationRepository;
import com.aquagrid.platform.gis.infrastructure.persistence.ReservoirRepository;
import com.aquagrid.platform.gis.infrastructure.persistence.TankRepository;
import com.aquagrid.platform.gis.web.dto.AssetTypeDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Read/write for the asset type tables (Tank, Reservoir, PumpStation).
 *
 * <p>Each type row shares the supertype's identity (PK = FK to gis.assets). This service enforces
 * that invariant: it resolves the parent asset, asserts it is the right {@link AssetType}, then
 * upserts the type row. A tank record on a PIPELINE asset is rejected — the type row and the
 * supertype must agree.
 */
@Service
@RequiredArgsConstructor
public class AssetTypeService {

    private final AssetService assetService;
    private final TankRepository tankRepository;
    private final ReservoirRepository reservoirRepository;
    private final PumpStationRepository pumpStationRepository;

    // --- Tank ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AssetTypeDtos.TankDto getTank(UUID assetId, UUID organizationId) {
        return AssetTypeDtos.TankDto.from(requireTypeRow(assetId, organizationId, AssetType.TANK,
                () -> tankRepository.findById(assetId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                                "No tank record for asset " + assetId)), Tank.class));
    }

    @Transactional
    public AssetTypeDtos.TankDto upsertTank(UUID assetId, UUID organizationId,
                                            AssetTypeDtos.TankRequest request) {
        Asset asset = requireAssetType(assetId, organizationId, AssetType.TANK);
        Tank tank = tankRepository.findById(assetId).orElseGet(() -> {
            Tank t = new Tank();
            t.setAssetId(assetId);
            return t;
        });
        tank.setCapacityM3(request.capacityM3());
        if (request.tankType() != null) tank.setTankType(request.tankType());
        tank.setMaterial(request.material());
        tank.setBaseElevationM(request.baseElevationM());
        tank.setOverflowElevationM(request.overflowElevationM());
        tank.setInletElevationM(request.inletElevationM());
        tankRepository.save(tank);
        return AssetTypeDtos.TankDto.from(tank);
    }

    // --- Reservoir -----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AssetTypeDtos.ReservoirDto getReservoir(UUID assetId, UUID organizationId) {
        return AssetTypeDtos.ReservoirDto.from(requireTypeRow(assetId, organizationId, AssetType.RESERVOIR,
                () -> reservoirRepository.findById(assetId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                                "No reservoir record for asset " + assetId)), Reservoir.class));
    }

    @Transactional
    public AssetTypeDtos.ReservoirDto upsertReservoir(UUID assetId, UUID organizationId,
                                                      AssetTypeDtos.ReservoirRequest request) {
        requireAssetType(assetId, organizationId, AssetType.RESERVOIR);
        Reservoir reservoir = reservoirRepository.findById(assetId).orElseGet(() -> {
            Reservoir r = new Reservoir();
            r.setAssetId(assetId);
            return r;
        });
        reservoir.setMaxCapacityM3(request.maxCapacityM3());
        reservoir.setSourceType(request.sourceType());
        reservoir.setSurfaceAreaM2(request.surfaceAreaM2());
        reservoir.setMaxDepthM(request.maxDepthM());
        reservoir.setIntakeElevationM(request.intakeElevationM());
        reservoirRepository.save(reservoir);
        return AssetTypeDtos.ReservoirDto.from(reservoir);
    }

    // --- Pump Station -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AssetTypeDtos.PumpStationDto getPumpStation(UUID assetId, UUID organizationId) {
        return AssetTypeDtos.PumpStationDto.from(requireTypeRow(assetId, organizationId, AssetType.PUMP_STATION,
                () -> pumpStationRepository.findById(assetId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                                "No pump station record for asset " + assetId)), PumpStation.class));
    }

    @Transactional
    public AssetTypeDtos.PumpStationDto upsertPumpStation(UUID assetId, UUID organizationId,
                                                          AssetTypeDtos.PumpStationRequest request) {
        requireAssetType(assetId, organizationId, AssetType.PUMP_STATION);
        PumpStation pump = pumpStationRepository.findById(assetId).orElseGet(() -> {
            PumpStation p = new PumpStation();
            p.setAssetId(assetId);
            p.setPumpStates(java.util.List.of());
            return p;
        });
        pump.setPumpCount(request.pumpCount());
        pump.setRatedFlowLpm(request.ratedFlowLpm());
        pump.setRatedHeadM(request.ratedHeadM());
        pump.setRatedPowerKw(request.ratedPowerKw());
        pump.setSuctionElevationM(request.suctionElevationM());
        pump.setDischargeElevationM(request.dischargeElevationM());
        if (request.pumpCurve() != null) pump.setPumpCurve(request.pumpCurve());
        pumpStationRepository.save(pump);
        return AssetTypeDtos.PumpStationDto.from(pump);
    }

    // --- Shared --------------------------------------------------------------------------------

    private Asset requireAssetType(UUID assetId, UUID organizationId, AssetType expected) {
        Asset asset = assetService.requireInTenant(assetId, organizationId);
        if (asset.getAssetType() != expected) {
            throw new BusinessException(ErrorCode.OPERATION_NOT_PERMITTED,
                    "Asset " + asset.getAssetCode() + " is a " + asset.getAssetType()
                            + ", not a " + expected + ".");
        }
        return asset;
    }

    /**
     * Resolves a type row, asserting the parent asset exists, belongs to the tenant, and is the
     * expected type. The supplier fetches the type row (each type has its own repository).
     */
    @SuppressWarnings("unchecked")
    private <T> T requireTypeRow(UUID assetId, UUID organizationId, AssetType expected,
                                 java.util.function.Supplier<T> fetch, Class<T> type) {
        requireAssetType(assetId, organizationId, expected);
        return fetch.get();
    }
}
