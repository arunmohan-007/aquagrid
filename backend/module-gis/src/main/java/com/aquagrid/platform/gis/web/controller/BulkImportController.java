package com.aquagrid.platform.gis.web.controller;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.common.web.PageResponse;
import com.aquagrid.platform.gis.api.AttributeDefinition;
import com.aquagrid.platform.gis.api.LayerMetadataApi;
import com.aquagrid.platform.gis.application.command.LayerCommands;
import com.aquagrid.platform.gis.application.service.BulkImportService;
import com.aquagrid.platform.gis.application.service.BulkImportService.ColumnAnalysis;
import com.aquagrid.platform.gis.application.service.BulkImportService.JobStatus;
import com.aquagrid.platform.gis.application.service.LayerManagementService;
import com.aquagrid.platform.gis.domain.enums.AssetType;
import com.aquagrid.platform.gis.domain.enums.GeometryType;
import com.aquagrid.platform.gis.domain.metadata.ImportFieldAliases;
import com.aquagrid.platform.gis.domain.model.Layer;
import com.aquagrid.platform.gis.web.dto.LayerDtos;
import com.aquagrid.platform.gis.web.dto.MetadataDtos;
import com.aquagrid.platform.security.core.AuthenticatedPrincipal;
import com.aquagrid.platform.security.core.Permissions;
import com.aquagrid.platform.security.core.SecurityUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bulk asset import.
 *
 * <p>Uploads a GeoJSON or CSV file and starts an asynchronous import job. The job id is returned
 * immediately; the client polls {@code GET /bulk-import/{jobId}} for progress. A 50k-row file takes
 * minutes; holding the connection open for that long is how proxies and load balancers get stressed.
 */
@Tag(name = "Assets", description = "Bulk import")
@RestController
@RequestMapping(value = ApiPaths.ASSETS + "/bulk-import", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class BulkImportController {

    private final BulkImportService bulkImportService;
    private final LayerMetadataApi metadataApi;
    private final LayerManagementService layerService;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_CREATE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Detect a file's columns before importing",
            description = "Phase 1 of the import. Returns the source file's column names and up to five "
                    + "sample rows so the user can map each column to a target field. Nothing is written.")
    public ColumnAnalysis analyze(@RequestParam("file") MultipartFile file) throws IOException {
        return bulkImportService.analyze(file.getContentType(), file.getBytes());
    }

    @GetMapping("/target-fields")
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_CREATE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List the fields a source column can be mapped to, for one layer",
            description = """
                    Served from the Data Management catalogue, not from a list compiled into the
                    application. An attribute created this morning appears here this afternoon, with
                    its label, description, sample value and the alternative column headings the
                    wizard should auto-match it against — no release, in either tier.

                    `geometry` narrows the result the way the wizard's own choice does. A line or a
                    polygon has no single longitude and latitude, so offering those two fields for
                    one would invite a mapping that cannot produce a shape. Only point imports see
                    them.

                    Fields belonging to typed detail tables (tank capacity, valve position) are
                    catalogued but never returned here — they carry constraints a supertype import
                    cannot satisfy and are set through the asset's own editor.""")
    public List<MetadataDtos.TargetFieldResponse> targetFields(
            @Parameter(description = "Asset type of the layer being imported", example = "PIPELINE")
            @RequestParam(defaultValue = "METER") String assetType,
            @Parameter(description = "Geometry the wizard is importing: point, line or polygon")
            @RequestParam(required = false) String geometry) {

        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        boolean point = geometry == null || "point".equalsIgnoreCase(geometry);

        return metadataApi.definitionsForAssetType(orgId, parseAssetType(assetType)).stream()
                .filter(AttributeDefinition::importable)
                // The geometry column itself is never mapped from a source column: a feature's
                // shape comes from the feature, and a CSV's from the lon/lat pair below it.
                .filter(d -> !"geom".equals(d.resolvedTarget()))
                .filter(d -> point || !isCoordinate(d))
                .map(d -> new MetadataDtos.TargetFieldResponse(
                        d.fieldName(), d.displayName(), d.description(), d.dataType().name(),
                        d.mandatory(), d.sampleValue(), ImportFieldAliases.forAttribute(d)))
                .toList();
    }

    private static boolean isCoordinate(AttributeDefinition definition) {
        return "lon".equals(definition.resolvedTarget()) || "lat".equals(definition.resolvedTarget());
    }

    /** A type the wizard does not recognise is a client bug, and says so rather than 500-ing. */
    private static AssetType parseAssetType(String raw) {
        try {
            return AssetType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'" + raw + "' is not a known asset type.");
        }
    }

    @PostMapping("/create-layer")
    @PreAuthorize("hasAuthority('" + Permissions.LAYER_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new layer for a file being imported",
            description = """
                    The "Create New Layer" branch of the wizard. Registers the layer through Layer
                    Management — the same call the registry screen makes — and returns it so the
                    wizard can continue into column mapping against it.

                    It creates no field-mapping system of its own: the layer's fields come from the
                    Data Management catalogue exactly as they do for an existing layer, and the
                    wizard's next step is the mapper it already had. What this adds is only the
                    layer.

                    The detected geometry type is the multi form of whatever the file carries —
                    `MULTIPOLYGON` for a polygon file — because a layer declared `POLYGON` would
                    later reject the first `MultiPolygon` a contractor delivers, and inferring the
                    narrower type from one sample is exactly the guess that produces that rejection
                    two quarters later.

                    `claimExistingFeatures` is true here and false on the registry's own create form:
                    the file that prompted this layer is about to be loaded, so its rows should
                    belong to it, whereas adopting an unrelated backlog on a button press is a
                    surprise nobody consented to.""")
    public LayerDtos.LayerResponse createLayerForImport(@Valid @RequestBody LayerDtos.CreateRequest request) {
        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        Layer created = layerService.create(principal.organizationId(), principal.userId(),
                principal.username(),
                new LayerCommands.Create(
                        request.code(), request.title(), request.description(), request.category(),
                        request.assetType() == null ? null : parseAssetType(request.assetType()),
                        request.geometryType() == null ? null
                                : GeometryType.valueOf(request.geometryType().trim().toUpperCase()),
                        request.crsAuthority(), request.srid(), request.active(),
                        request.visibleByDefault(), request.editable(), request.queryable(),
                        request.searchable(), request.importEnabled(), request.exportEnabled(),
                        request.vectorTileEnabled(), request.minZoom(), request.maxZoom(),
                        request.sortOrder(),
                        request.claimExistingFeatures() == null || request.claimExistingFeatures()));
        return LayerDtos.LayerResponse.from(layerService.get(principal.organizationId(), created.getId()));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_CREATE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Start a bulk import from GeoJSON or CSV",
            description = """
                    Phase 2 of the import. `mapping` is a JSON object of source column name → target
                    field code, as returned by `/analyze` and `/target-fields`. Columns absent from
                    the mapping are ignored — the importer never guesses column names.

                    `layerId` names the layer the rows are imported into. Omit it and the primary
                    layer for `defaultType` is used, which is what every client did before Layer
                    Management existed and remains correct for a tenant with one layer per type.
                    Passing it matters when a tenant has split a type across two layers: the rows
                    then land on the layer the operator chose rather than on whichever sorts first.

                    An import into a disabled or archived layer is refused. Both states mean "do not
                    write to this", and silently writing anyway would leave rows on a layer nothing
                    displays.""")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> start(@RequestParam("file") MultipartFile file,
                                     @RequestParam("mapping") String mapping,
                                     @RequestParam(defaultValue = "METER") String defaultType,
                                     @RequestParam(required = false) UUID layerId) throws IOException {
        var principal = SecurityUtils.requirePrincipal();
        UUID jobId = UUID.randomUUID();

        Layer layer = resolveTargetLayer(principal.organizationId(), layerId, defaultType);
        AssetType type = layer != null ? layer.getAssetType() : parseAssetType(defaultType);

        bulkImportService.runImport(jobId, principal.organizationId(), principal.userId(),
                principal.username(), file.getOriginalFilename(), file.getContentType(), file.getBytes(),
                type, layer == null ? null : layer.getId(), parseMapping(mapping));
        return Map.of("jobId", jobId, "status", "RUNNING");
    }

    /**
     * The layer an import writes to.
     *
     * <p>Null is a legitimate answer: a tenant provisioned after the layer-seeding migrations has no
     * layer rows, and refusing its imports would be a regression caused by a registry it never got.
     * Those rows keep {@code layer_id} null and are read through the asset-type fallback, exactly as
     * every row written before V1332 is.
     */
    private Layer resolveTargetLayer(UUID organizationId, UUID layerId, String defaultType) {
        if (layerId != null) {
            Layer layer = layerService.require(organizationId, layerId);
            if (!layer.isUsable()) {
                throw new BusinessException(ErrorCode.OPERATION_NOT_PERMITTED,
                        "'" + layer.getTitle() + "' is " + layer.getStatus() + ", so it does not accept "
                                + "imports. Enable it in Layer Management first.");
            }
            if (!layer.isImportEnabled()) {
                throw new BusinessException(ErrorCode.OPERATION_NOT_PERMITTED,
                        "Import is switched off for '" + layer.getTitle() + "' in Layer Management.");
            }
            return layer;
        }
        return metadataApi.layerIdForAssetType(organizationId, parseAssetType(defaultType))
                .map(id -> layerService.require(organizationId, id))
                .orElse(null);
    }

    /**
     * The mapping arrives as a JSON string because the request is multipart — a nested JSON part
     * would force the client to set a per-part content type, which browsers do not do for
     * {@code FormData}.
     */
    private Map<String, String> parseMapping(String mapping) {
        try {
            Map<String, String> parsed = objectMapper.readValue(mapping, new TypeReference<>() {
            });
            if (parsed == null || parsed.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "At least one source column must be mapped to a target field.");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "mapping must be a JSON object of column name to target field.");
        }
    }

    @GetMapping("/{jobId}")
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Poll a bulk import job's status")
    public JobStatus status(@PathVariable UUID jobId) {
        return bulkImportService.status(jobId);
    }

    @GetMapping("/history")
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List past bulk import runs",
            description = "Persisted history, so a run started last week is still visible after the "
                    + "polling UI that started it has long since closed. Newest first.")
    public PageResponse<BulkImportService.ImportRunSummary> history(
            @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.of(bulkImportService.history(
                SecurityUtils.requirePrincipal().organizationId(), pageable));
    }

    @GetMapping("/history/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Full detail for one past import run",
            description = "The run's counts plus the per-row outcome for every row that was replaced, "
                    + "skipped or failed. A plain successful insert is not listed row by row here — it "
                    + "is fully accounted for by the summary's imported count.")
    public BulkImportService.ImportRunDetail historyDetail(@PathVariable UUID id) {
        return bulkImportService.historyDetail(SecurityUtils.requirePrincipal().organizationId(), id);
    }
}
