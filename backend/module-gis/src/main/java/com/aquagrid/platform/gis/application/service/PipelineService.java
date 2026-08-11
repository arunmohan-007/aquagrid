package com.aquagrid.platform.gis.application.service;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.gis.domain.enums.AssetType;
import com.aquagrid.platform.gis.domain.geo.GeometryCodec;
import com.aquagrid.platform.gis.domain.model.Asset;
import com.aquagrid.platform.gis.domain.model.Pipeline;
import com.aquagrid.platform.gis.infrastructure.persistence.PipelineRepository;
import com.aquagrid.platform.gis.web.dto.PipelineDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.LineString;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pipeline CRUD — and the bridge to the network topology.
 *
 * <p>Every pipe write does three things in one transaction:
 * <ol>
 *   <li>Snaps the line's endpoints to network nodes (creating nodes where none exist within
 *       tolerance) — this is what turns a set of drawn lines into a connected graph.</li>
 *   <li>Persists the pipeline row with its {@code from_node}/{@code to_node}.</li>
 *   <li>Rebuilds the pgRouting edge table so traces see the new pipe immediately.</li>
 * </ol>
 *
 * <p>The parent asset must exist and be of type PIPELINE — the type row and supertype must agree,
 * the same rule the other type modules enforce.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineService {

    private final AssetService assetService;
    private final PipelineRepository pipelineRepository;
    private final NetworkTopologyService topologyService;

    @Transactional(readOnly = true)
    public PipelineDto.PipelineDetailDto get(UUID assetId, UUID organizationId) {
        Pipeline pipeline = require(assetId, organizationId);
        return PipelineDto.PipelineDetailDto.from(pipeline);
    }

    @Transactional
    public PipelineDto.PipelineDetailDto upsert(UUID assetId, UUID organizationId,
                                                PipelineDto.PipelineRequest request) {
        Asset asset = assetService.requireInTenant(assetId, organizationId);
        if (asset.getAssetType() != AssetType.PIPELINE) {
            throw new BusinessException(ErrorCode.OPERATION_NOT_PERMITTED,
                    "Asset " + asset.getAssetCode() + " is a " + asset.getAssetType() + ", not a PIPELINE.");
        }
        if (request.coordinates() == null || request.coordinates().size() < 2) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A pipeline requires at least two coordinate points (a LineString).");
        }

        // Parse the coordinates into a LineString, then snap endpoints to network nodes.
        LineString geom = (LineString) GeometryCodec.fromGeoJson(Map.of(
                "type", "LineString", "coordinates", request.coordinates()));

        UUID fromNode = topologyService.snapOrCreate(organizationId,
                geom.getStartPoint().getX(), geom.getStartPoint().getY(), "JUNCTION");
        UUID toNode = topologyService.snapOrCreate(organizationId,
                geom.getEndPoint().getX(), geom.getEndPoint().getY(), "JUNCTION");

        Pipeline pipeline = pipelineRepository.findById(assetId).orElseGet(() -> {
            Pipeline p = new Pipeline();
            p.setAssetId(assetId);
            return p;
        });
        pipeline.setGeom(geom);
        pipeline.setFromNodeId(fromNode);
        pipeline.setToNodeId(toNode);
        pipeline.setDiameterMm(request.diameterMm());
        pipeline.setMaterial(request.material());
        if (request.flowDirection() != null) pipeline.setFlowDirection(request.flowDirection());
        pipeline.setRoughness(request.roughness());
        pipeline.setPressureClass(request.pressureClass());
        pipelineRepository.save(pipeline);

        // Rebuild the edge table so traces include this pipe. Within the same transaction, so a
        // failure rolls the pipe back too.
        topologyService.rebuild(organizationId);
        log.info("Pipeline {} saved: {} nodes, from {} to {}", assetId,
                request.coordinates().size(), fromNode, toNode);
        return PipelineDto.PipelineDetailDto.from(pipeline);
    }

    private Pipeline require(UUID assetId, UUID organizationId) {
        assetService.requireInTenant(assetId, organizationId);
        return pipelineRepository.findById(assetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No pipeline record for asset " + assetId));
    }
}
