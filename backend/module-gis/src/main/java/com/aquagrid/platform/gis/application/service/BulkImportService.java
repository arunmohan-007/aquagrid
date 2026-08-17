package com.aquagrid.platform.gis.application.service;

import com.aquagrid.platform.common.audit.AuditCategory;
import com.aquagrid.platform.common.audit.AuditEvent;
import com.aquagrid.platform.common.audit.AuditEventTypes;
import com.aquagrid.platform.common.audit.AuditService;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.gis.api.AttributeDefinition;
import com.aquagrid.platform.gis.api.LayerMetadataApi;
import com.aquagrid.platform.gis.domain.enums.AssetStatus;
import com.aquagrid.platform.gis.domain.enums.AssetType;
import com.aquagrid.platform.gis.domain.geo.GeometryCodec;
import com.aquagrid.platform.gis.domain.metadata.AttributeBinder;
import com.aquagrid.platform.gis.domain.model.Asset;
import com.aquagrid.platform.gis.domain.model.ImportRun;
import com.aquagrid.platform.gis.infrastructure.persistence.AssetRepository;
import com.aquagrid.platform.gis.infrastructure.persistence.ImportRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bulk asset import, with user-controlled column mapping driven by the attribute catalogue.
 *
 * <p>Two-phase flow, matching how ESRI / ArcGIS field-data import works:
 * <ol>
 *   <li><b>Analyze</b> — the user uploads a file; this service parses the header and a few sample
 *       rows and returns the detected columns. The frontend shows these alongside the layer's
 *       attributes and lets the user map each source column to a field (or ignore it).</li>
 *   <li><b>Import</b> — the user submits the column→field mapping; this service imports using that
 *       mapping rather than guessing column names. A column the user did not map is ignored.</li>
 * </ol>
 *
 * <p>This is the fix for the real-world problem: a utility's CSV has {@code Meter_No},
 * {@code Easting}, {@code Date_Commissioned} — none of which match the platform's
 * {@code asset_code}/{@code lon}/{@code install_date}. Guessing silently mis-maps; failing is
 * confusing. Letting the user say "Meter_No → asset_code" is correct and is what every enterprise
 * GIS import does.
 *
 * <p><b>What changed when Data Management landed.</b> The target field list used to be a table of
 * constants in this file, and every new field a utility wanted was a code change here, in the
 * frontend's copy of the same list, and in the {@code applyAttributes} method that decided which
 * fields were numbers. All three are gone. The importer now asks
 * {@link LayerMetadataApi#definitionsForAssetType} what the layer's fields are and hands each value
 * to {@link AttributeBinder}, which knows from the definition what type it is, where it goes and
 * what it must satisfy. An attribute created this morning is mappable this afternoon, and nothing
 * in this class knows its name.
 *
 * <p>A row whose {@code asset_code} or a field marked "Consider for duplicate check" in Data
 * Management matches an asset already on file updates that asset instead of colliding with it —
 * see {@link #resolveTarget}. A row whose matching value repeats within the same file is dropped
 * as a duplicate of the row that already claimed it. This turns a re-import of a previously
 * loaded file from a wall of "already exists" failures into the update it is meant to be.
 *
 * <p>Live job state is in-process ({@code ConcurrentHashMap}) for the duration of the run — correct
 * for v1 single-node, a multi-node deployment moves it to shared storage. Once a run finishes it is
 * written to {@code gis.import_run}, which is what survives a restart and is what the import
 * history in the hub reads from.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkImportService {

    /** Mapping value meaning "drop this column". Preserved from the pre-catalogue API. */
    public static final String IGNORE = "ignore";

    private final AssetRepository assetRepository;
    private final LayerMetadataApi metadataApi;
    private final ImportRunRepository importRunRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    private final Map<UUID, JobStatus> jobs = new ConcurrentHashMap<>();

    // ---- Phase 1: column detection -----------------------------------------------------------

    /**
     * Parses the uploaded file's header and sample rows, without importing.
     *
     * @return the detected columns (in file order) + up to 5 sample rows, so the user can map
     *         confidently with the data in front of them.
     */
    public ColumnAnalysis analyze(String contentType, byte[] payload) {
        if (contentType != null && contentType.contains("json")) {
            return analyzeGeoJson(payload);
        }
        if (contentType != null && (contentType.contains("csv") || contentType.contains("text/plain"))) {
            return analyzeCsv(payload);
        }
        throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "Only GeoJSON and CSV are accepted for analysis.");
    }

    private ColumnAnalysis analyzeGeoJson(byte[] payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode features = root.path("features");
            if (!features.isArray() || features.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "GeoJSON must be a non-empty FeatureCollection.");
            }
            // The columns are the union of keys across the first few features' properties.
            Map<String, Integer> columns = new LinkedHashMap<>();
            List<Map<String, String>> samples = new ArrayList<>();
            int sampleCount = Math.min(features.size(), 5);
            for (int i = 0; i < sampleCount; i++) {
                JsonNode props = features.get(i).path("properties");
                Map<String, String> row = new LinkedHashMap<>();
                props.fields().forEachRemaining(entry -> {
                    columns.putIfAbsent(entry.getKey(), columns.size());
                    row.put(entry.getKey(), entry.getValue().asText(""));
                });
                samples.add(row);
            }
            return new ColumnAnalysis(new ArrayList<>(columns.keySet()), samples, "GEOJSON");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid GeoJSON: " + e.getMessage());
        }
    }

    private ColumnAnalysis analyzeCsv(byte[] payload) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.ByteArrayInputStream(payload), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "CSV is empty.");
            }
            List<String> columns = List.of(header.split(","));
            List<Map<String, String>> samples = new ArrayList<>();
            String line;
            int rowCount = 0;
            while ((line = reader.readLine()) != null && rowCount < 5) {
                String[] parts = line.split(",", -1);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < columns.size() && i < parts.length; i++) {
                    row.put(columns.get(i), parts[i]);
                }
                samples.add(row);
                rowCount++;
            }
            return new ColumnAnalysis(columns, samples, "CSV");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid CSV: " + e.getMessage());
        }
    }

    // ---- Phase 2: import with the user's mapping ---------------------------------------------

    /**
     * Runs the import asynchronously, using the supplied column mapping.
     *
     * @param mapping source column name → attribute field name, as declared in Data Management. A
     *                source column absent from the map, or mapped to {@value #IGNORE}, is dropped.
     *                An attribute absent from the map gets its configured default.
     */
    @Async
    public void runImport(UUID jobId, UUID organizationId, UUID actorId, String actorUsername,
                          String fileName, String contentType, byte[] payload, AssetType defaultType,
                          UUID layerId, Map<String, String> mapping) {
        JobStatus status = new JobStatus();
        jobs.put(jobId, status);

        ImportRun run = new ImportRun();
        run.setOrganizationId(organizationId);
        run.setJobId(jobId);
        run.setActorUserId(actorId);
        run.setActorUsername(actorUsername);
        run.setLayerId(layerId);
        run.setAssetType(defaultType.name());
        run.setFileName(fileName);
        run.setFormat(contentType != null && contentType.contains("json") ? "GEOJSON" : "CSV");
        run.setStartedAt(Instant.now());
        run = importRunRepository.save(run);

        try {
            ResolvedMapping resolved = resolveMapping(organizationId, defaultType, mapping);
            if (contentType.contains("json")) {
                importGeoJson(organizationId, defaultType, layerId, payload, status, resolved);
            } else {
                importCsv(organizationId, defaultType, layerId, payload, status, resolved);
            }
            status.complete();
            log.info("Bulk import {} done: {} imported, {} replaced, {} skipped, {} failed",
                    jobId, status.imported, status.replaced, status.skipped, status.failed);
            persistRun(run, status, null);
        } catch (Exception e) {
            status.fail(e.getMessage());
            log.warn("Bulk import {} failed: {}", jobId, e.getMessage());
            persistRun(run, status, e.getMessage());
        }
    }

    /** Writes the finished job's counts and row detail to {@code gis.import_run}. */
    private void persistRun(ImportRun run, JobStatus status, String errorMessage) {
        boolean failed = errorMessage != null;
        run.setState(failed ? "FAILED" : "COMPLETED");
        run.setTotalRows(status.total);
        run.setImportedRows(status.imported);
        run.setReplacedRows(status.replaced);
        run.setSkippedRows(status.skipped);
        run.setFailedRows(status.failed);
        run.setErrorMessage(errorMessage);
        run.setFinishedAt(Instant.now());
        run.setRowDetails(status.rows.stream()
                .map(r -> Map.<String, Object>of(
                        "row", r.row(), "outcome", r.outcome(), "message", r.message() == null ? "" : r.message()))
                .toList());
        importRunRepository.save(run);

        auditService.record(AuditEvent.builder()
                .organizationId(run.getOrganizationId())
                .actorUserId(run.getActorUserId())
                .actorUsername(run.getActorUsername())
                .eventType(failed ? AuditEventTypes.IMPORT_RUN_FAILED : AuditEventTypes.IMPORT_RUN_COMPLETED)
                .category(AuditCategory.DATA)
                .resourceType("gis.import_run")
                .resourceId(run.getId().toString())
                .success(!failed)
                .message((failed ? "Bulk import failed: " + errorMessage
                        : "Bulk import completed") + " for " + run.getFileName())
                .metadata(Map.of(
                        "total", status.total, "imported", status.imported, "replaced", status.replaced,
                        "skipped", status.skipped, "failed", status.failed))
                .build());
    }

    /**
     * Turns the operator's column→field-name map into column→definition, once, before any row is
     * read.
     *
     * <p>Resolving per row would mean a mapping typo — a field name that does not exist, or one
     * retired since the profile was saved — surfacing as fifty thousand identical row errors
     * instead of as one refusal before anything was written. It also means the definitions and
     * their validation rules are loaded once rather than per row, which is the difference between
     * one query and hundreds of thousands.
     */
    private ResolvedMapping resolveMapping(UUID organizationId, AssetType defaultType,
                                           Map<String, String> mapping) {
        Map<String, AttributeDefinition> byFieldName = LayerMetadataService.byFieldName(
                metadataApi.definitionsForAssetType(organizationId, defaultType));
        if (byFieldName.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                    "No attribute catalogue for " + defaultType + ". The layer has no fields defined, "
                            + "so there is nothing to map source columns onto.");
        }

        Map<String, AttributeDefinition> byColumn = new LinkedHashMap<>();
        List<AttributeDefinition> duplicateCheckAttributes = new ArrayList<>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String target = entry.getValue();
            if (target == null || target.isBlank() || IGNORE.equals(target)) {
                continue;
            }
            AttributeDefinition definition = byFieldName.get(target);
            if (definition == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "'" + target + "' is not an active field on this layer. It may have been "
                                + "retired in Data Management since this mapping was saved.");
            }
            if (!definition.importable()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "'" + definition.displayName() + "' belongs to a typed detail table and "
                                + "cannot be filled by an import.");
            }
            byColumn.put(entry.getKey(), definition);
            if (definition.duplicateCheck() && definition.storage() == com.aquagrid.platform.gis
                    .domain.enums.AttributeStorage.JSONB) {
                duplicateCheckAttributes.add(definition);
            }
        }
        if (byColumn.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "No source column is mapped to a field.");
        }
        return new ResolvedMapping(byColumn, duplicateCheckAttributes, new HashSet<>());
    }

    private void importGeoJson(UUID organizationId, AssetType defaultType, UUID layerId,
                               byte[] payload, JobStatus status, ResolvedMapping mapping) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode features = root.path("features");
            for (JsonNode feature : features) {
                status.total++;
                try {
                    JsonNode props = feature.path("properties");
                    Map<String, String> values = new HashMap<>();
                    props.fields().forEachRemaining(e -> values.put(e.getKey(), e.getValue().asText("")));

                    MatchedAsset target = resolveTarget(organizationId, values, defaultType, mapping);
                    if (target == null) {
                        status.recordSkipped(status.total,
                                "Same asset code / duplicate-check value as an earlier row in this file.");
                        continue;
                    }

                    Asset asset = target.asset();
                    asset.setLayerId(layerId);
                    AttributeBinder.Coordinates coordinates = new AttributeBinder.Coordinates();
                    for (Map.Entry<String, AttributeDefinition> entry : mapping.byColumn().entrySet()) {
                        AttributeBinder.bind(asset, entry.getValue(), values.get(entry.getKey()), coordinates);
                    }
                    AttributeBinder.applyDefaultType(asset, defaultType);
                    /*
                     * A GeoJSON feature carries its own geometry, which always wins over anything a
                     * lon/lat mapping produced: the feature's geometry can be a line or a polygon,
                     * and reducing one to a point because two numeric columns happened to be mapped
                     * would quietly discard the shape the file exists to convey.
                     */
                    if (feature.path("geometry").isObject()) {
                        asset.setGeom(GeometryCodec.fromGeoJson(objectMapper.convertValue(
                                feature.path("geometry"), Map.class)));
                    }
                    finishNewAsset(asset, target.isNew());
                    assetRepository.save(asset);
                    status.recordSaved(status.total, target.isNew());
                } catch (Exception rowError) {
                    status.recordFailed(status.total, rowError.getMessage());
                }
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid GeoJSON: " + e.getMessage());
        }
    }

    private void importCsv(UUID organizationId, AssetType defaultType, UUID layerId,
                           byte[] payload, JobStatus status, ResolvedMapping mapping) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.ByteArrayInputStream(payload), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "CSV is empty.");
            }
            String[] cols = header.split(",", -1);

            String line;
            while ((line = reader.readLine()) != null) {
                status.total++;
                try {
                    String[] parts = line.split(",", -1);
                    Map<String, String> values = new HashMap<>();
                    for (int i = 0; i < cols.length && i < parts.length; i++) {
                        values.put(cols[i].trim(), parts[i]);
                    }

                    MatchedAsset target = resolveTarget(organizationId, values, defaultType, mapping);
                    if (target == null) {
                        status.recordSkipped(status.total,
                                "Same asset code / duplicate-check value as an earlier row in this file.");
                        continue;
                    }

                    Asset asset = target.asset();
                    asset.setLayerId(layerId);
                    AttributeBinder.Coordinates coordinates = new AttributeBinder.Coordinates();
                    for (Map.Entry<String, AttributeDefinition> entry : mapping.byColumn().entrySet()) {
                        AttributeBinder.bind(asset, entry.getValue(), values.get(entry.getKey()), coordinates);
                    }
                    AttributeBinder.applyDefaultType(asset, defaultType);
                    if (coordinates.isComplete()) {
                        asset.setGeom(GeometryCodec.fromGeoJson(Map.of("type", "Point",
                                "coordinates", List.of(coordinates.lon(), coordinates.lat()))));
                    }
                    if (asset.getGeom() == null) {
                        throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                                "No geometry: map a longitude and a latitude column.");
                    }
                    finishNewAsset(asset, target.isNew());
                    assetRepository.save(asset);
                    status.recordSaved(status.total, target.isNew());
                } catch (Exception rowError) {
                    status.recordFailed(status.total, rowError.getMessage());
                }
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid CSV: " + e.getMessage());
        }
    }

    /**
     * Finds the asset this row belongs to, or reports it as a within-file duplicate.
     *
     * <p>A row matches an existing asset by {@code asset_code} first (the platform's actual
     * identity field), then by the first attribute-bag field marked "Consider for duplicate check"
     * in Data Management that the row carries a value for. A match means the row updates that
     * asset — {@link AttributeBinder#bind} only ever writes the columns this file maps, so a field
     * the file is silent on keeps whatever value the asset already had. No match means a new asset,
     * exactly as before this method existed.
     *
     * <p>A layer with no field marked for duplicate check, and no {@code asset_code} mapped, has no
     * key to match on — every row is a new asset, same as before this method existed. That is a
     * configuration gap for an administrator to close in Data Management, not something this method
     * can guess at.
     *
     * <p>The same matching value appearing twice in one file is not two updates to the same asset;
     * it is treated as a duplicate row and the second (and any later) occurrence is skipped, so the
     * import's counts describe what happened rather than silently overwriting the first row's work
     * with the second's.
     *
     * @return null when this row's matching value was already claimed earlier in this file
     */
    private MatchedAsset resolveTarget(UUID organizationId, Map<String, String> sourceValues,
                                       AssetType defaultType, ResolvedMapping mapping) {
        String mappedAssetCode = mappedAssetCode(sourceValues, mapping);
        DuplicateCheckValue duplicateCheckValue = mappedAssetCode == null
                ? firstDuplicateCheckValue(sourceValues, mapping) : null;

        String dedupeKey = mappedAssetCode != null ? "code:" + mappedAssetCode
                : duplicateCheckValue != null
                        ? "attr:" + duplicateCheckValue.fieldName() + ':' + duplicateCheckValue.value() : null;
        if (dedupeKey != null && !mapping.seenKeys().add(dedupeKey)) {
            return null;
        }

        Asset existing = null;
        if (mappedAssetCode != null) {
            existing = assetRepository.findByOrganizationIdAndAssetCode(organizationId, mappedAssetCode)
                    .orElse(null);
        } else if (duplicateCheckValue != null) {
            existing = assetRepository.findFirstByAttributeValue(organizationId, defaultType.name(),
                    duplicateCheckValue.fieldName(), duplicateCheckValue.value()).orElse(null);
        }

        if (existing != null) {
            return new MatchedAsset(existing, false);
        }
        Asset created = new Asset();
        created.setOrganizationId(organizationId);
        created.setStatus(AssetStatus.IN_SERVICE);
        return new MatchedAsset(created, true);
    }

    /** The value this row's mapped {@code asset_code} column carries, trimmed, or null. */
    private String mappedAssetCode(Map<String, String> sourceValues, ResolvedMapping mapping) {
        for (Map.Entry<String, AttributeDefinition> entry : mapping.byColumn().entrySet()) {
            if ("asset_code".equals(entry.getValue().resolvedTarget())) {
                String raw = sourceValues.get(entry.getKey());
                return raw == null || raw.isBlank() ? null : raw.trim();
            }
        }
        return null;
    }

    /**
     * The first duplicate-check-flagged attribute this row carries a non-blank value for, or null.
     */
    private DuplicateCheckValue firstDuplicateCheckValue(Map<String, String> sourceValues, ResolvedMapping mapping) {
        for (AttributeDefinition attribute : mapping.duplicateCheckAttributes()) {
            for (Map.Entry<String, AttributeDefinition> entry : mapping.byColumn().entrySet()) {
                if (!entry.getValue().equals(attribute)) {
                    continue;
                }
                String raw = sourceValues.get(entry.getKey());
                if (raw != null && !raw.isBlank()) {
                    return new DuplicateCheckValue(attribute.fieldName(), raw.trim());
                }
            }
        }
        return null;
    }

    /**
     * The platform's unique key, and the one field that cannot be left to the catalogue. An asset
     * with no code cannot be looked up, joined to a device or referenced from a work order, and
     * {@code gis.assets} requires it NOT NULL. A generated code is a worse asset than a mapped one
     * and a far better one than a failed row. Only applied to a genuinely new asset — an existing
     * one being replaced already has a code.
     */
    private void finishNewAsset(Asset asset, boolean isNew) {
        if (!isNew) {
            return;
        }
        if (asset.getAssetCode() == null || asset.getAssetCode().isBlank()) {
            asset.setAssetCode("IMP-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (asset.getName() == null || asset.getName().isBlank()) {
            asset.setName(asset.getAssetCode());
        }
    }

    public JobStatus status(UUID jobId) {
        return jobs.get(jobId);
    }

    // ---- History -------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<ImportRunSummary> history(UUID organizationId, Pageable pageable) {
        return importRunRepository.findByOrganizationIdOrderByStartedAtDesc(organizationId, pageable)
                .map(ImportRunSummary::from);
    }

    @Transactional(readOnly = true)
    public ImportRunDetail historyDetail(UUID organizationId, UUID id) {
        ImportRun run = importRunRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Import run not found."));
        return new ImportRunDetail(ImportRunSummary.from(run), run.getRowDetails());
    }

    // ---- Public types ------------------------------------------------------------------------

    /**
     * The mapping, resolved once per job.
     *
     * @param byColumn                 source column → the attribute it fills, in the order the
     *                                 operator mapped them
     * @param duplicateCheckAttributes the attribute-bag fields marked "Consider for duplicate
     *                                 check" in Data Management, extracted once so the row loop
     *                                 does not re-scan every definition
     * @param seenKeys                 asset-code / duplicate-check-attribute values already claimed
     *                                 by a row in this file. Mutable and job-scoped — the job runs
     *                                 on one thread, so a plain HashSet is correct and a concurrent
     *                                 one would only cost.
     */
    private record ResolvedMapping(
            Map<String, AttributeDefinition> byColumn,
            List<AttributeDefinition> duplicateCheckAttributes,
            Set<String> seenKeys
    ) {
    }

    private record DuplicateCheckValue(String fieldName, String value) {
    }

    /** The asset a row binds onto, and whether it is new or being replaced. */
    private record MatchedAsset(Asset asset, boolean isNew) {
    }

    /** Phase-1 result: the file's columns and sample rows. */
    public record ColumnAnalysis(
            List<String> columns,
            List<Map<String, String>> sampleRows,
            String format
    ) {
    }

    /** One row's outcome, recorded for every row that was not a plain new-row import. */
    public record RowDetail(int row, String outcome, String message) {
    }

    /** Mutable job progress record. */
    public static class JobStatus {
        public volatile String state = "RUNNING";
        public volatile int total;
        public volatile int imported;
        public volatile int replaced;
        public volatile int skipped;
        public volatile int failed;
        public final List<RowDetail> rows = java.util.Collections.synchronizedList(new ArrayList<>());

        void recordSaved(int row, boolean isNew) {
            if (isNew) {
                imported++;
            } else {
                replaced++;
                rows.add(new RowDetail(row, "REPLACED", "Updated the matching existing asset."));
            }
        }

        void recordSkipped(int row, String message) {
            skipped++;
            rows.add(new RowDetail(row, "SKIPPED", message));
        }

        void recordFailed(int row, String message) {
            failed++;
            rows.add(new RowDetail(row, "FAILED", message));
        }

        void complete() {
            this.state = "COMPLETED";
        }

        void fail(String message) {
            this.state = "FAILED:" + message;
        }
    }

    /** One row in the import history list. */
    public record ImportRunSummary(
            UUID id, UUID jobId, String fileName, String format, String assetType, UUID layerId,
            String state, int total, int imported, int replaced, int skipped, int failed,
            String actorUsername, Instant startedAt, Instant finishedAt, String errorMessage
    ) {
        static ImportRunSummary from(ImportRun run) {
            return new ImportRunSummary(run.getId(), run.getJobId(), run.getFileName(), run.getFormat(),
                    run.getAssetType(), run.getLayerId(), run.getState(), run.getTotalRows(),
                    run.getImportedRows(), run.getReplacedRows(), run.getSkippedRows(), run.getFailedRows(),
                    run.getActorUsername(), run.getStartedAt(), run.getFinishedAt(), run.getErrorMessage());
        }
    }

    /** One import run's summary plus the per-row outcomes for its replaced/skipped/failed rows. */
    public record ImportRunDetail(ImportRunSummary summary, List<Map<String, Object>> rows) {
    }
}
