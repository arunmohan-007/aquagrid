package com.aquagrid.platform.gis.application.service;

import com.aquagrid.platform.common.audit.AuditCategory;
import com.aquagrid.platform.common.audit.AuditEvent;
import com.aquagrid.platform.common.audit.AuditEventTypes;
import com.aquagrid.platform.common.audit.AuditService;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.common.error.ResourceNotFoundException;
import com.aquagrid.platform.gis.domain.enums.AssetStatus;
import com.aquagrid.platform.gis.domain.enums.AssetType;
import com.aquagrid.platform.gis.domain.geo.GeometryCodec;
import com.aquagrid.platform.gis.domain.model.Asset;
import com.aquagrid.platform.gis.infrastructure.persistence.AssetRepository;
import com.aquagrid.platform.gis.web.dto.AssetDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Asset register write/read service.
 *
 * <p>Tenant-scoped (organisation comes from the principal, never the body), geometry parsed from
 * GeoJSON on the way in and serialised back on the way out, and every mutation audited. The asset
 * code uniqueness is enforced per-tenant — two utilities may both have asset code "MTR-001" without
 * colliding.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetService {

    private final AssetRepository assetRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<AssetDto> list(UUID organizationId, String assetType, String search, Pageable pageable) {
        AssetType typeFilter = assetType == null ? null : parseType(assetType);
        return assetRepository.findForTenant(organizationId,
                        typeFilter == null ? null : typeFilter.name(), search, pageable)
                .map(AssetDto::from);
    }

    @Transactional(readOnly = true)
    public AssetDto get(UUID assetId, UUID organizationId) {
        return AssetDto.from(requireInTenant(assetId, organizationId));
    }

    @Transactional
    public AssetDto create(UUID organizationId, UUID actorId, String clientIp, AssetDto.AssetRequest request) {
        if (assetRepository.existsByOrganizationIdAndAssetCode(organizationId, request.assetCode())) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT,
                    "Asset code " + request.assetCode() + " is already in use in this organisation.");
        }
        Asset asset = new Asset();
        asset.setOrganizationId(organizationId);
        asset.setAssetCode(request.assetCode());
        asset.setAssetType(parseType(request.assetType()));
        asset.setName(request.name());
        asset.setStatus(request.status() == null ? AssetStatus.IN_SERVICE : parseStatus(request.status()));
        asset.setInstallDate(request.installDate());
        asset.setDecommissionDate(request.decommissionDate());
        asset.setGeom(GeometryCodec.fromGeoJson(request.geometry()));
        if (request.attributes() != null) {
            asset.setAttributes(request.attributes());
        }
        assetRepository.save(asset);

        audit(organizationId, actorId, AuditEventTypes.USER_CREATED, asset,
                "Asset '%s' (%s) created".formatted(asset.getAssetCode(), asset.getAssetType()), true, clientIp);
        return AssetDto.from(asset);
    }

    @Transactional
    public AssetDto update(UUID assetId, UUID organizationId, UUID actorId, String clientIp,
                           AssetDto.AssetRequest request) {
        Asset asset = requireInTenant(assetId, organizationId);
        if (request.name() != null) asset.setName(request.name());
        if (request.status() != null) asset.setStatus(parseStatus(request.status()));
        if (request.installDate() != null) asset.setInstallDate(request.installDate());
        if (request.decommissionDate() != null) asset.setDecommissionDate(request.decommissionDate());
        if (request.geometry() != null) asset.setGeom(GeometryCodec.fromGeoJson(request.geometry()));
        if (request.attributes() != null) asset.setAttributes(request.attributes());

        audit(organizationId, actorId, AuditEventTypes.USER_UPDATED, asset,
                "Asset '%s' updated".formatted(asset.getAssetCode()), true, clientIp);
        return AssetDto.from(asset);
    }

    @Transactional
    public void delete(UUID assetId, UUID organizationId, UUID actorId, String clientIp) {
        Asset asset = requireInTenant(assetId, organizationId);
        assetRepository.delete(asset);
        audit(organizationId, actorId, AuditEventTypes.USER_DELETED, asset,
                "Asset '%s' deleted".formatted(asset.getAssetCode()), true, clientIp);
    }

    /** Resolves an asset and confirms it belongs to the calling tenant — 404, never 403, on mismatch. */
    public Asset requireInTenant(UUID assetId, UUID organizationId) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Asset", assetId));
        if (!asset.getOrganizationId().equals(organizationId)) {
            throw new ResourceNotFoundException("Asset", assetId);
        }
        return asset;
    }

    private static AssetType parseType(String value) {
        try {
            return AssetType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Unknown asset type: " + value);
        }
    }

    private static AssetStatus parseStatus(String value) {
        try {
            return AssetStatus.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Unknown asset status: " + value);
        }
    }

    private void audit(UUID orgId, UUID actorId, String type, Asset asset, String message,
                       boolean success, String clientIp) {
        auditService.record(AuditEvent.builder()
                .organizationId(orgId)
                .actorUserId(actorId)
                .eventType(type)
                .category(AuditCategory.DATA)
                .resourceType("Asset")
                .resourceId(asset.getId().toString())
                .success(success)
                .message(message)
                .clientIp(clientIp)
                .metadata(Map.of("assetCode", asset.getAssetCode(), "assetType", asset.getAssetType().name()))
                .build());
    }
}
