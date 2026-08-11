package com.aquagrid.platform.gis.application.service;

import com.aquagrid.platform.common.audit.AuditCategory;
import com.aquagrid.platform.common.audit.AuditEvent;
import com.aquagrid.platform.common.audit.AuditService;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.gis.domain.enums.AssetType;
import com.aquagrid.platform.gis.domain.model.Asset;
import com.aquagrid.platform.gis.domain.model.Valve;
import com.aquagrid.platform.gis.domain.model.ValveOperation;
import com.aquagrid.platform.gis.infrastructure.persistence.ValveOperationRepository;
import com.aquagrid.platform.gis.infrastructure.persistence.ValveRepository;
import com.aquagrid.platform.gis.web.dto.ValveDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Valve CRUD + the operate workflow.
 *
 * <p>Operating a valve is not a field update — it is a state transition with an evidence chain.
 * Every open/close writes a {@link ValveOperation} row (from-state, to-state, operator, reason) so
 * the question "was this valve operated correctly during the incident?" has a definitive answer.
 * The valve's {@code status} and the operation log are written in one transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ValveService {

    private final AssetService assetService;
    private final ValveRepository valveRepository;
    private final ValveOperationRepository operationRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public ValveDto.ValveDetailDto get(UUID assetId, UUID organizationId) {
        return ValveDto.ValveDetailDto.from(require(assetId, organizationId));
    }

    @Transactional
    public ValveDto.ValveDetailDto upsert(UUID assetId, UUID organizationId, ValveDto.ValveRequest request) {
        Asset asset = assetService.requireInTenant(assetId, organizationId);
        if (asset.getAssetType() != AssetType.VALVE) {
            throw new BusinessException(ErrorCode.OPERATION_NOT_PERMITTED,
                    "Asset " + asset.getAssetCode() + " is a " + asset.getAssetType() + ", not a VALVE.");
        }
        Valve valve = valveRepository.findById(assetId).orElseGet(() -> {
            Valve v = new Valve();
            v.setAssetId(assetId);
            return v;
        });
        valve.setNodeId(request.nodeId());
        if (request.valveType() != null) valve.setValveType(request.valveType());
        valve.setDiameterMm(request.diameterMm());
        if (request.status() != null) valve.setStatus(request.status());
        if (request.normalState() != null) valve.setNormalState(request.normalState());
        valve.setPressureSetpointBar(request.pressureSetpointBar());
        valve.setTurnsToOperate(request.turnsToOperate());
        valve.setManufacturer(request.manufacturer());
        valve.setModelNumber(request.modelNumber());
        valveRepository.save(valve);
        return ValveDto.ValveDetailDto.from(valve);
    }

    /**
     * Operates a valve: transitions its state and records the evidence.
     *
     * <p>Idempotent on the target state — operating an already-CLOSED valve to CLOSED is a no-op
     * that still logs (the operator attempted it), but does not error. An invalid target state is
     * rejected before any write.
     */
    @Transactional
    public ValveDto.OperationDto operate(UUID assetId, UUID organizationId, UUID operatorId,
                                         String clientIp, ValveDto.OperateRequest request) {
        Valve valve = require(assetId, organizationId);
        String toState = request.toState() == null ? "" : request.toState().toUpperCase();
        if (!toState.equals("OPEN") && !toState.equals("CLOSED")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Target state must be OPEN or CLOSED.");
        }
        String fromState = valve.operate(toState);
        valveRepository.save(valve);

        ValveOperation operation = new ValveOperation();
        operation.setOrganizationId(organizationId);
        operation.setValveAssetId(assetId);
        operation.setFromState(fromState);
        operation.setToState(toState);
        operation.setOperatedBy(operatorId);
        operation.setOperatedAt(Instant.now());
        operation.setReason(request.reason());
        operation.setWorkOrderId(request.workOrderId());
        operation.setClientIp(clientIp);
        operationRepository.save(operation);

        auditService.record(AuditEvent.builder()
                .organizationId(organizationId)
                .actorUserId(operatorId)
                .eventType("VALVE_OPERATED")
                .category(AuditCategory.DATA)
                .resourceType("Valve")
                .resourceId(assetId.toString())
                .success(true)
                .message("Valve %s: %s → %s".formatted(assetId, fromState, toState))
                .clientIp(clientIp)
                .metadata(Map.of("fromState", fromState, "toState", toState))
                .build());
        log.info("Valve {} operated {} -> {} by {}", assetId, fromState, toState, operatorId);
        return ValveDto.OperationDto.from(operation);
    }

    @Transactional(readOnly = true)
    public List<ValveDto.OperationDto> history(UUID assetId, UUID organizationId) {
        require(assetId, organizationId);
        return operationRepository.findByValveAssetIdOrderByOperatedAtDesc(assetId).stream()
                .map(ValveDto.OperationDto::from)
                .toList();
    }

    private Valve require(UUID assetId, UUID organizationId) {
        assetService.requireInTenant(assetId, organizationId);
        return valveRepository.findById(assetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No valve record for asset " + assetId));
    }
}
