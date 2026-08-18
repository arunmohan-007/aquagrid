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
import jakarta.persistence.EntityManager;
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
 * <p>A row whose {@code asset_code} matches an asset already on file updates that asset instead of
 * colliding with it. So does a row that supplies a value for every field marked "Consider for
 * duplicate check" in Data Management, when that combination already matches an asset — one
 * flagged field alone is that field's value; more than one flagged field requires all of them to
 * agree, so two meters that happen to share one attribute are not merged on that basis alone. See
 * {@link #resolveTarget}. A row whose matching value repeats within the same file is dropped as a
 * duplicate of the row that already claimed it. This turns a re-import of a previously loaded file
 * from a wall of "already exists" failures into the update it is meant to be.
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
    private final EntityManager entityManager;

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

    // ---- Phase 2: preview, then import with the user's mapping --------------------------------

    /**
     * Resolves every row against the layer's assets exactly as {@link #runImport} would — new vs.
     * replace vs. duplicate-skip — without writing anything, so the wizard can show the operator
     * what they are about to commit to and let them back out before anything is saved.
     *
     * <p>Counts only what row identity resolution already knows for free: matching is a handful of
     * indexed reads, so previewing a large file costs little beyond parsing it. Field-level
     * validation (a missing mandatory value, a malformed number) is not run here — it stays a
     * property of the commit, the same way {@code toReplace} here can drift from the real run's
     * {@code replaced} count if another operator writes to the same layer in between.
     */
    public ImportPreview preview(UUID organizationId, String contentType, byte[] payload, AssetType defaultType,
                                 Map<String, String> mapping) {
        ResolvedMapping resolved = resolveMapping(organizationId, defaultType, mapping);
        boolean isJson = contentType != null && contentType.contains("json");
        return isJson ? previewGeoJson(organizationId, defaultType, payload, resolved)
                      : previewCsv(organizationId, defaultType, payload, resolved);
    }

    private ImportPreview previewGeoJson(UUID organizationId, AssetType defaultType, byte[] payload,
                                         ResolvedMapping mapping) {
        int total = 0;
        int toCreate = 0;
        int toReplace = 0;
        int duplicatesSkipped = 0;
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode features = root.path("features");
            for (JsonNode feature : features) {
                total++;
                JsonNode props = feature.path("properties");
                Map<String, String> values = new HashMap<>();
                props.fields().forEachRemaining(e -> values.put(e.getKey(), e.getValue().asText("")));

                RowResolution resolution = resolveTarget(organizationId, values, defaultType, mapping);
                if (resolution.matched() == null) {
                    duplicatesSkipped++;
                } else if (resolution.matched().isNew()) {
                    toCreate++;
                } else {
                    toReplace++;
                }
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid GeoJSON: " + e.getMessage());
        }
        return new ImportPreview(total, toCreate, toReplace, duplicatesSkipped);
    }

    private ImportPreview previewCsv(UUID organizationId, AssetType defaultType, byte[] payload,
                                     ResolvedMapping mapping) {
        int total = 0;
        int toCreate = 0;
        int toReplace = 0;
        int duplicatesSkipped = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.ByteArrayInputStream(payload), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "CSV is empty.");
            }
            String[] cols = header.split(",", -1);

            String line;
            while ((line = reader.readLine()) != null) {
                total++;
                String[] parts = line.split(",", -1);
                Map<String, String> values = new HashMap<>();
                for (int i = 0; i < cols.length && i < parts.length; i++) {
                    values.put(cols[i].trim(), parts[i]);
                }

                RowResolution resolution = resolveTarget(organizationId, values, defaultType, mapping);
                if (resolution.matched() == null) {
                    duplicatesSkipped++;
                } else if (resolution.matched().isNew()) {
                    toCreate++;
                } else {
                    toReplace++;
                }
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid CSV: " + e.getMessage());
        }
        return new ImportPreview(total, toCreate, toReplace, duplicatesSkipped);
    }

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
                importGeoJson(organizationId, defaultType, layerId, run.getId(), payload, status, resolved);
            } else {
                importCsv(organizationId, defaultType, layerId, run.getId(), payload, status, resolved);
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

    private void importGeoJson(UUID organizationId, AssetType defaultType, UUID layerId, UUID runId,
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

                    RowResolution resolution = resolveTarget(organizationId, values, defaultType, mapping);
                    if (resolution.matched() == null) {
                        status.recordSkipped(status.total, resolution.skipReason());
                        continue;
                    }
                    MatchedAsset target = resolution.matched();

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
                    finishNewAsset(asset, target.isNew(), runId);
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

    private void importCsv(UUID organizationId, AssetType defaultType, UUID layerId, UUID runId,
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

                    RowResolution resolution = resolveTarget(organizationId, values, defaultType, mapping);
                    if (resolution.matched() == null) {
                        status.recordSkipped(status.total, resolution.skipReason());
                        continue;
                    }
                    MatchedAsset target = resolution.matched();

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
                    finishNewAsset(asset, target.isNew(), runId);
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
     * Finds the asset this row belongs to, or reports it as a within-file duplicate or conflict.
     *
     * <p>A row matches an existing asset by {@code asset_code} (the platform's actual identity
     * field), and separately by the fields marked "Consider for duplicate check" in Data
     * Management — one flagged field is that field's value alone; more than one flagged field
     * requires the row to supply a value for <em>every</em> one of them, and all of those values
     * together must match an existing asset. A row silent on even one flagged field is not
     * eligible for attribute-based matching at all: a partial match would let two unrelated meters
     * merge because they happened to agree on the one field that was filled in. A match means the
     * row updates that asset — {@link AttributeBinder#bind} only ever writes the columns this file
     * maps, so a field the file is silent on keeps whatever value the asset already had. No match
     * means a new asset, exactly as before this method existed.
     *
     * <p>{@code asset_code} and the duplicate-check fields are independent keys. If both are
     * present on a row and they resolve to two different existing assets, that is a data conflict
     * — not something this method can resolve by picking one — so the row is skipped rather than
     * merged onto either asset.
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
     */
    private RowResolution resolveTarget(UUID organizationId, Map<String, String> sourceValues,
                                        AssetType defaultType, ResolvedMapping mapping) {
        String mappedAssetCode = mappedAssetCode(sourceValues, mapping);
        Map<String, String> duplicateCheckValues = allDuplicateCheckValues(sourceValues, mapping);

        String codeKey = mappedAssetCode != null ? "code:" + mappedAssetCode : null;
        String attrsKey = duplicateCheckValues != null ? "attrs:" + canonicalKey(duplicateCheckValues) : null;

        if (codeKey != null && mapping.seenKeys().contains(codeKey)) {
            return RowResolution.skip("Same asset code as an earlier row in this file.");
        }
        if (attrsKey != null && mapping.seenKeys().contains(attrsKey)) {
            return RowResolution.skip(
                    "Same duplicate-check field values as an earlier row in this file.");
        }

        Asset existingByCode = mappedAssetCode != null
                ? assetRepository.findByOrganizationIdAndAssetCode(organizationId, mappedAssetCode).orElse(null)
                : null;
        Asset existingByAttrs = duplicateCheckValues != null
                ? findByAllAttributeValues(organizationId, defaultType, duplicateCheckValues)
                : null;

        if (existingByCode != null && existingByAttrs != null
                && !existingByCode.getId().equals(existingByAttrs.getId())) {
            return RowResolution.skip("Asset code '" + mappedAssetCode + "' and the duplicate-check "
                    + "fields point to two different existing assets ('" + existingByCode.getAssetCode()
                    + "' and '" + existingByAttrs.getAssetCode() + "'). Fix the mismatch in the source "
                    + "file and re-import this row.");
        }

        if (codeKey != null) {
            mapping.seenKeys().add(codeKey);
        }
        if (attrsKey != null) {
            mapping.seenKeys().add(attrsKey);
        }

        Asset existing = existingByCode != null ? existingByCode : existingByAttrs;
        if (existing != null) {
            return RowResolution.matched(new MatchedAsset(existing, false));
        }
        Asset created = new Asset();
        created.setOrganizationId(organizationId);
        created.setStatus(AssetStatus.IN_SERVICE);
        return RowResolution.matched(new MatchedAsset(created, true));
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
     * Every duplicate-check-flagged attribute's value from this row, keyed by field name — only
     * when the row supplies a non-blank value for all of them. Null when no field is flagged, or
     * the row is silent on at least one that is.
     */
    private Map<String, String> allDuplicateCheckValues(Map<String, String> sourceValues, ResolvedMapping mapping) {
        if (mapping.duplicateCheckAttributes().isEmpty()) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (AttributeDefinition attribute : mapping.duplicateCheckAttributes()) {
            String raw = null;
            for (Map.Entry<String, AttributeDefinition> entry : mapping.byColumn().entrySet()) {
                if (entry.getValue().equals(attribute)) {
                    raw = sourceValues.get(entry.getKey());
                    break;
                }
            }
            if (raw == null || raw.isBlank()) {
                return null;
            }
            values.put(attribute.fieldName(), raw.trim());
        }
        return values;
    }

    /** A deterministic, order-independent-by-construction string for a set of field values. */
    private String canonicalKey(Map<String, String> values) {
        StringBuilder key = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            key.append(entry.getKey()).append('=').append(entry.getValue()).append('|');
        }
        return key.toString();
    }

    /**
     * The asset whose attribute bag carries every one of these field values, or null.
     *
     * <p>Built as a native query rather than repeated single-field lookups because the fields
     * marked for duplicate check are a set the administrator configures, not a fixed count — the
     * only way to require all of them to agree in one round trip is to build the {@code AND} chain
     * at the width the configuration actually has. Only the chain's shape (its parameter count) is
     * built from the configuration; every field name and value is bound as a parameter, never
     * concatenated into the SQL text.
     */
    private Asset findByAllAttributeValues(UUID organizationId, AssetType assetType, Map<String, String> values) {
        StringBuilder sql = new StringBuilder("SELECT * FROM gis.assets a WHERE a.organization_id = "
                + ":organizationId AND a.asset_type = :assetType");
        int i = 0;
        for (String ignored : values.keySet()) {
            sql.append(" AND a.attributes ->> cast(:key").append(i).append(" as text) = cast(:value")
                    .append(i).append(" as text)");
            i++;
        }
        sql.append(" LIMIT 1");

        jakarta.persistence.Query query = entityManager.createNativeQuery(sql.toString(), Asset.class)
                .setParameter("organizationId", organizationId)
                .setParameter("assetType", assetType.name());
        i = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            query.setParameter("key" + i, entry.getKey());
            query.setParameter("value" + i, entry.getValue());
            i++;
        }
        @SuppressWarnings("unchecked")
        List<Asset> results = query.getResultList();
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * The platform's unique key, and the one field that cannot be left to the catalogue. An asset
     * with no code cannot be looked up, joined to a device or referenced from a work order, and
     * {@code gis.assets} requires it NOT NULL. A generated code is a worse asset than a mapped one
     * and a far better one than a failed row. Only applied to a genuinely new asset — an existing
     * one being replaced already has a code.
     *
     * <p>Also stamps {@link Asset#setImportRunId}, only on this same new-asset path — a row this
     * run merely updated keeps whichever run (or none) created it, so deleting this run's data can
     * never remove an asset that predates it.
     */
    private void finishNewAsset(Asset asset, boolean isNew, UUID runId) {
        if (!isNew) {
            return;
        }
        if (asset.getAssetCode() == null || asset.getAssetCode().isBlank()) {
            asset.setAssetCode("IMP-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (asset.getName() == null || asset.getName().isBlank()) {
            asset.setName(asset.getAssetCode());
        }
        asset.setImportRunId(runId);
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
        ImportRun run = requireRun(organizationId, id);
        return new ImportRunDetail(ImportRunSummary.from(run), run.getRowDetails());
    }

    // ---- Delete a run's imported data ---------------------------------------------------------

    /**
     * How many assets a delete would remove, without removing anything.
     *
     * <p>Exact when the run stamped its inserts with {@link Asset#getImportRunId()} — every run
     * since V1338. A run from before that column existed has no such tag on anything it created, so
     * this falls back to {@link AssetRepository#countByImportWindow}, the closest approximation
     * available without a way to point back at exactly which rows a historical run wrote.
     */
    @Transactional(readOnly = true)
    public DeletePreview deletePreview(UUID organizationId, UUID runId) {
        ImportRun run = requireRun(organizationId, runId);
        if (run.getImportedRows() == 0) {
            return new DeletePreview(0, false);
        }
        long exact = assetRepository.countByImportRun(organizationId, runId);
        if (exact > 0) {
            return new DeletePreview(exact, false);
        }
        return new DeletePreview(estimateWindowCount(organizationId, run), true);
    }

    /**
     * Deletes the assets this run created — never ones it only replaced, see {@link #finishNewAsset}
     * — and records the outcome on the run itself so the history list can grey the action out rather
     * than let it fire twice.
     */
    @Transactional
    public DeletePreview deleteImportedData(UUID organizationId, UUID runId, UUID actorId,
                                            String actorUsername, String clientIp) {
        ImportRun run = requireRun(organizationId, runId);
        if (run.getDataDeletedAt() != null) {
            throw new BusinessException(ErrorCode.OPERATION_NOT_PERMITTED,
                    "This run's imported data was already deleted.");
        }
        if ("RUNNING".equals(run.getState())) {
            throw new BusinessException(ErrorCode.OPERATION_NOT_PERMITTED,
                    "This run is still in progress.");
        }

        int deleted = 0;
        boolean estimated = false;
        if (run.getImportedRows() > 0) {
            long exact = assetRepository.countByImportRun(organizationId, runId);
            if (exact > 0) {
                deleted = assetRepository.deleteByImportRun(organizationId, runId);
            } else {
                estimated = true;
                Instant from = windowStart(run);
                Instant to = windowEnd(run);
                deleted = assetRepository.deleteByImportWindow(organizationId, run.getAssetType(),
                        run.getLayerId(), from, to);
            }
        }

        run.setDataDeletedAt(Instant.now());
        run.setDataDeletedBy(actorId);
        run.setDeletedRowCount(deleted);
        run.setDeletedRowEstimated(estimated);
        importRunRepository.save(run);

        auditService.record(AuditEvent.builder()
                .organizationId(organizationId)
                .actorUserId(actorId)
                .actorUsername(actorUsername)
                .eventType(AuditEventTypes.IMPORT_RUN_DATA_DELETED)
                .category(AuditCategory.DATA)
                .resourceType("gis.import_run")
                .resourceId(run.getId().toString())
                .success(true)
                .message("Deleted " + deleted + " asset(s) imported by '" + run.getFileName() + "'"
                        + (estimated ? " (best-effort match — run predates exact tracking)" : ""))
                .clientIp(clientIp)
                .metadata(Map.of(
                        "deletedCount", String.valueOf(deleted),
                        "estimated", String.valueOf(estimated),
                        "fileName", run.getFileName() == null ? "" : run.getFileName()))
                .build());

        return new DeletePreview(deleted, estimated);
    }

    private long estimateWindowCount(UUID organizationId, ImportRun run) {
        if (run.getFinishedAt() == null) {
            return 0;
        }
        return assetRepository.countByImportWindow(organizationId, run.getAssetType(), run.getLayerId(),
                windowStart(run), windowEnd(run));
    }

    /*
     * A couple of seconds either side of the run's own started/finished timestamps, to absorb clock
     * and transaction-commit skew at the boundaries without the window growing wide enough to start
     * picking up an unrelated asset the same operator happened to create right before or after.
     */
    private static Instant windowStart(ImportRun run) {
        return run.getStartedAt().minusSeconds(2);
    }

    private static Instant windowEnd(ImportRun run) {
        return (run.getFinishedAt() == null ? run.getStartedAt() : run.getFinishedAt()).plusSeconds(2);
    }

    private ImportRun requireRun(UUID organizationId, UUID runId) {
        return importRunRepository.findByIdAndOrganizationId(runId, organizationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Import run not found."));
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

    /** The asset a row binds onto, and whether it is new or being replaced. */
    private record MatchedAsset(Asset asset, boolean isNew) {
    }

    /**
     * What {@link #resolveTarget} decided for one row: either an asset to bind onto, or a reason
     * the row was skipped instead (an in-file duplicate, or a code/attribute conflict).
     */
    private record RowResolution(MatchedAsset matched, String skipReason) {
        static RowResolution matched(MatchedAsset matched) {
            return new RowResolution(matched, null);
        }

        static RowResolution skip(String reason) {
            return new RowResolution(null, reason);
        }
    }

    /** Phase-1 result: the file's columns and sample rows. */
    public record ColumnAnalysis(
            List<String> columns,
            List<Map<String, String>> sampleRows,
            String format
    ) {
    }

    /**
     * What committing this file with this mapping would do, before anything is written — the
     * counts behind the wizard's "N new, M replaced, D duplicates skipped — proceed?" prompt.
     */
    public record ImportPreview(
            int totalRows,
            int toCreate,
            int toReplace,
            int duplicatesSkipped
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
            String actorUsername, Instant startedAt, Instant finishedAt, String errorMessage,
            Instant dataDeletedAt, Integer deletedRowCount, Boolean deletedRowEstimated
    ) {
        static ImportRunSummary from(ImportRun run) {
            return new ImportRunSummary(run.getId(), run.getJobId(), run.getFileName(), run.getFormat(),
                    run.getAssetType(), run.getLayerId(), run.getState(), run.getTotalRows(),
                    run.getImportedRows(), run.getReplacedRows(), run.getSkippedRows(), run.getFailedRows(),
                    run.getActorUsername(), run.getStartedAt(), run.getFinishedAt(), run.getErrorMessage(),
                    run.getDataDeletedAt(), run.getDeletedRowCount(), run.getDeletedRowEstimated());
        }
    }

    /** One import run's summary plus the per-row outcomes for its replaced/skipped/failed rows. */
    public record ImportRunDetail(ImportRunSummary summary, List<Map<String, Object>> rows) {
    }

    /**
     * How many assets a delete would remove (or removed), and whether that count came from the
     * exact {@code import_run_id} tag or the best-effort window match for a run that predates it.
     */
    public record DeletePreview(long count, boolean estimated) {
    }
}
