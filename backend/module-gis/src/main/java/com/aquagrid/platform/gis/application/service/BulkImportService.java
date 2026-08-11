package com.aquagrid.platform.gis.application.service;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.gis.api.AttributeDefinition;
import com.aquagrid.platform.gis.api.LayerMetadataApi;
import com.aquagrid.platform.gis.domain.enums.AssetStatus;
import com.aquagrid.platform.gis.domain.enums.AssetType;
import com.aquagrid.platform.gis.domain.geo.GeometryCodec;
import com.aquagrid.platform.gis.domain.metadata.AttributeBinder;
import com.aquagrid.platform.gis.domain.model.Asset;
import com.aquagrid.platform.gis.infrastructure.persistence.AssetRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
 * <p>Job state is in-process ({@code ConcurrentHashMap}). Correct for v1 single-node; a multi-node
 * deployment moves this to shared storage — call sites unchanged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkImportService {

    /** Mapping value meaning "drop this column". Preserved from the pre-catalogue API. */
    public static final String IGNORE = "ignore";

    private final AssetRepository assetRepository;
    private final LayerMetadataApi metadataApi;
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
    public void runImport(UUID jobId, UUID organizationId, UUID actorId, String contentType,
                          byte[] payload, AssetType defaultType, UUID layerId,
                          Map<String, String> mapping) {
        JobStatus status = new JobStatus();
        jobs.put(jobId, status);
        try {
            ResolvedMapping resolved = resolveMapping(organizationId, defaultType, mapping);
            if (contentType.contains("json")) {
                importGeoJson(organizationId, defaultType, layerId, payload, status, resolved);
            } else {
                importCsv(organizationId, defaultType, layerId, payload, status, resolved);
            }
            status.complete();
            log.info("Bulk import {} done: {} imported, {} failed", jobId, status.imported, status.failed);
        } catch (Exception e) {
            status.fail(e.getMessage());
            log.warn("Bulk import {} failed: {}", jobId, e.getMessage());
        }
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
        List<AttributeDefinition> uniqueAttributes = new ArrayList<>();
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
            if (definition.uniqueValue() && definition.storage() == com.aquagrid.platform.gis
                    .domain.enums.AttributeStorage.JSONB) {
                uniqueAttributes.add(definition);
            }
        }
        if (byColumn.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "No source column is mapped to a field.");
        }
        return new ResolvedMapping(byColumn, uniqueAttributes, new HashMap<>());
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

                    Asset asset = buildAsset(organizationId, values, defaultType, layerId, mapping);
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
                    assetRepository.save(asset);
                    status.imported++;
                } catch (Exception rowError) {
                    status.failed++;
                    status.errors.add("Feature " + status.total + ": " + rowError.getMessage());
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
                    Asset asset = buildAsset(organizationId, values, defaultType, layerId, mapping);
                    if (asset.getGeom() == null) {
                        throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                                "No geometry: map a longitude and a latitude column.");
                    }
                    assetRepository.save(asset);
                    status.imported++;
                } catch (Exception rowError) {
                    status.failed++;
                    status.errors.add("Line " + status.total + ": " + rowError.getMessage());
                }
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid CSV: " + e.getMessage());
        }
    }

    /**
     * Builds one asset by walking the resolved mapping.
     *
     * <p>No field name appears in this method, which is the point of the whole exercise. Each mapped
     * column's definition says what type its value is, where it belongs and what it must satisfy;
     * the binder does the rest.
     */
    private Asset buildAsset(UUID organizationId, Map<String, String> sourceValues,
                             AssetType defaultType, UUID layerId, ResolvedMapping mapping) {
        Asset asset = new Asset();
        asset.setOrganizationId(organizationId);
        asset.setStatus(AssetStatus.IN_SERVICE);
        /*
         * Claim the row for the layer being imported into (V1332).
         *
         * Set here rather than left to the asset-type fallback so that a tenant with two layers over
         * one asset type — domestic and bulk meters, say — gets each file's rows on the layer the
         * operator chose in the wizard, instead of both layers drawing everything. Null only when the
         * tenant has no layer row for the type at all, which the fallback still covers.
         */
        asset.setLayerId(layerId);

        AttributeBinder.Coordinates coordinates = new AttributeBinder.Coordinates();
        for (Map.Entry<String, AttributeDefinition> entry : mapping.byColumn().entrySet()) {
            AttributeBinder.bind(asset, entry.getValue(), sourceValues.get(entry.getKey()), coordinates);
        }
        AttributeBinder.applyDefaultType(asset, defaultType);

        if (coordinates.isComplete()) {
            asset.setGeom(GeometryCodec.fromGeoJson(Map.of("type", "Point",
                    "coordinates", List.of(coordinates.lon(), coordinates.lat()))));
        }

        /*
         * The platform's unique key, and the one field that cannot be left to the catalogue. An
         * asset with no code cannot be looked up, joined to a device or referenced from a work
         * order, and gis.assets requires it NOT NULL. A generated code is a worse asset than a
         * mapped one and a far better one than a failed row.
         */
        if (asset.getAssetCode() == null || asset.getAssetCode().isBlank()) {
            asset.setAssetCode("IMP-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (asset.getName() == null || asset.getName().isBlank()) {
            asset.setName(asset.getAssetCode());
        }

        enforceUniqueness(organizationId, asset, mapping);
        return asset;
    }

    /**
     * Enforces {@code unique} on attribute-bag fields.
     *
     * <p>Columns like {@code asset_code} need nothing here: {@code uq_assets_org_code} rejects a
     * duplicate and the row is reported as failed, which is both correct and free. A JSONB
     * attribute has no such index — one cannot be created without DDL, which is exactly what this
     * module avoids — so uniqueness is checked in two places: within the file, in memory, which
     * catches the overwhelmingly common case of a register that lists the same consumer twice; and
     * against stored data with a targeted query.
     *
     * <p>That query is per row per unique attribute, and it is the one place this importer is not
     * O(1) per row. It is acceptable because {@code unique} is opt-in and rare, and because the
     * alternative — importing duplicates into a field an administrator declared unique — is a data
     * problem that outlives the import by years. If a tenant ever marks a high-cardinality field
     * unique on a large layer, the fix is a partial expression index created by migration for that
     * field, not a weaker check here.
     */
    private void enforceUniqueness(UUID organizationId, Asset asset, ResolvedMapping mapping) {
        for (AttributeDefinition attribute : mapping.uniqueAttributes()) {
            Object value = asset.getAttributes().get(attribute.fieldName());
            if (value == null) {
                continue;
            }
            String text = value.toString();
            Set<String> seen = mapping.seenUniqueValues()
                    .computeIfAbsent(attribute.fieldName(), key -> new HashSet<>());
            if (!seen.add(text)) {
                throw new BusinessException(ErrorCode.RESOURCE_CONFLICT,
                        attribute.displayName() + " '" + text + "' appears more than once in this file, "
                                + "but the field is defined as unique.");
            }
            if (assetRepository.existsByAttributeValue(organizationId,
                    asset.getAssetType().name(), attribute.fieldName(), text)) {
                throw new BusinessException(ErrorCode.RESOURCE_CONFLICT,
                        attribute.displayName() + " '" + text + "' already exists on another asset, "
                                + "and the field is defined as unique.");
            }
        }
    }

    public JobStatus status(UUID jobId) {
        return jobs.get(jobId);
    }

    // ---- Public types ------------------------------------------------------------------------

    /**
     * The mapping, resolved once per job.
     *
     * @param byColumn          source column → the attribute it fills, in the order the operator
     *                          mapped them
     * @param uniqueAttributes  the attribute-bag fields declared unique, extracted once so the row
     *                          loop does not re-scan every definition
     * @param seenUniqueValues  values already used in this file, per field. Mutable and
     *                          job-scoped — the job runs on one thread, so a plain HashMap is
     *                          correct and a concurrent one would only cost.
     */
    private record ResolvedMapping(
            Map<String, AttributeDefinition> byColumn,
            List<AttributeDefinition> uniqueAttributes,
            Map<String, Set<String>> seenUniqueValues
    ) {
    }

    /** Phase-1 result: the file's columns and sample rows. */
    public record ColumnAnalysis(
            List<String> columns,
            List<Map<String, String>> sampleRows,
            String format
    ) {
    }

    /** Mutable job progress record. */
    public static class JobStatus {
        public volatile String state = "RUNNING";
        public volatile int total;
        public volatile int imported;
        public volatile int failed;
        public final List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());

        void complete() { this.state = "COMPLETED"; }
        void fail(String message) { this.state = "FAILED:" + message; }
    }
}
