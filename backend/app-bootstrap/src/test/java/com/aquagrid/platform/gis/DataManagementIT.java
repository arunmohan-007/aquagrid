package com.aquagrid.platform.gis;

import com.aquagrid.platform.AbstractIntegrationTest;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.gis.api.AttributeDefinition;
import com.aquagrid.platform.gis.api.LayerMetadataApi;
import com.aquagrid.platform.gis.application.command.AttributeCommands;
import com.aquagrid.platform.gis.application.service.AssetExportService;
import com.aquagrid.platform.gis.application.service.BulkImportService;
import com.aquagrid.platform.gis.application.service.LayerMetadataService;
import com.aquagrid.platform.gis.domain.enums.AssetType;
import com.aquagrid.platform.gis.domain.enums.AttributeDataType;
import com.aquagrid.platform.gis.domain.enums.AttributeStorage;
import com.aquagrid.platform.gis.domain.model.Asset;
import com.aquagrid.platform.gis.domain.model.Layer;
import com.aquagrid.platform.gis.domain.model.LayerAttribute;
import com.aquagrid.platform.gis.infrastructure.persistence.AssetRepository;
import com.aquagrid.platform.gis.infrastructure.persistence.LayerRepository;
import com.aquagrid.platform.identity.infrastructure.persistence.OrganizationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Data Management, end to end against real PostGIS.
 *
 * <p>The claim under test is the module's whole reason for existing: a field created at runtime
 * reaches the importer, the store and the exporter with no code change, and retiring it removes it
 * from all three without destroying anything. Every assertion here corresponds to a way that claim
 * could be quietly false — a field the mapper offers but the importer ignores, an export whose
 * column list has drifted from the catalogue, a "soft" delete that turns out to lose data, a rename
 * that leaves values stranded under the old key.
 *
 * <p>Runs against the real schema because most of the mechanism is in SQL: the JSONB attribute bag,
 * the key-rename statement, the catalogue-assembled export query and the seed itself.
 */
class DataManagementIT extends AbstractIntegrationTest {

    /** Distinguishes rows across tests without a per-test cleanup. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private LayerMetadataService metadataService;
    @Autowired
    private LayerMetadataApi metadataApi;
    @Autowired
    private BulkImportService importService;
    @Autowired
    private AssetExportService exportService;
    @Autowired
    private LayerRepository layerRepository;
    @Autowired
    private AssetRepository assetRepository;
    @Autowired
    private OrganizationRepository organizationRepository;

    private UUID organizationId() {
        return organizationRepository.findByCodeIgnoreCase("SYSTEM").orElseThrow().getId();
    }

    private Layer layer(String code) {
        return layerRepository.findByOrganizationIdAndCode(organizationId(), code).orElseThrow();
    }

    private String unique(String prefix) {
        return prefix + "_" + SEQ.incrementAndGet();
    }

    // ---- The seed -----------------------------------------------------------------------------

    @Test
    @DisplayName("the catalogue is seeded from the fields the code already reads")
    void catalogueIsSeededFromExistingCode() {
        List<AttributeDefinition> meters =
                metadataApi.definitionsForAssetType(organizationId(), AssetType.METER);

        assertThat(meters).isNotEmpty();
        assertThat(meters).extracting(AttributeDefinition::fieldName)
                .contains("asset_code", "name", "status", "install_date", "geom", "lon", "lat");

        // Supertype columns are system rows backed by real columns, not bag keys.
        AttributeDefinition assetCode = byName(meters, "asset_code");
        assertThat(assetCode.system()).isTrue();
        assertThat(assetCode.storage()).isEqualTo(AttributeStorage.COLUMN);
        assertThat(assetCode.mandatory()).isTrue();
        assertThat(assetCode.uniqueValue()).isTrue();
        // Every layer's built-in identity field, already usable as the bulk importer's re-import key.
        assertThat(assetCode.duplicateCheck()).isTrue();

        // The pipe-network deliverable fields the importer used to hard-code are catalogue rows now.
        List<AttributeDefinition> pipelines =
                metadataApi.definitionsForAssetType(organizationId(), AssetType.PIPELINE);
        assertThat(pipelines).extracting(AttributeDefinition::fieldName)
                .contains("slno", "diameter", "digital_length", "panchayat", "start_date");
        assertThat(byName(pipelines, "diameter").storage()).isEqualTo(AttributeStorage.JSONB);

        // Every attribute carries a sample value, which is what makes the exported data dictionary
        // usable by a contractor who has never seen the platform.
        assertThat(meters).allSatisfy(definition ->
                assertThat(definition.sampleValue()).as(definition.fieldName()).isNotBlank());
    }

    @Test
    @DisplayName("the grid's own opening query returns the layer's fields")
    void gridDefaultQueryReturnsRows() {
        UUID orgId = organizationId();
        Layer pipelines = layer("pipelines");

        // Exactly what the Data Management screen sends on first paint: a layer, no search, and
        // every tri-state filter unset. The screen showed "0 fields" against a rail that correctly
        // counted 20, so the filters — not the data — were dropping every row.
        long counted = metadataService.listLayers(orgId).stream()
                .filter(summary -> summary.id().equals(pipelines.getId()))
                .findFirst().orElseThrow()
                .activeAttributeCount();

        var page = metadataService.search(orgId,
                new LayerMetadataService.AttributeQuery(pipelines.getId(), null, null, null, null, null, null),
                PageRequest.of(0, 25, org.springframework.data.domain.Sort.by("sortOrder", "fieldName")));

        assertThat(counted).isGreaterThan(0);
        // The rail and the grid read the same rows through different queries; if they disagree,
        // one of them is lying to the operator about what the layer contains.
        assertThat(page.getTotalElements()).isEqualTo(counted);
        assertThat(page.getContent()).isNotEmpty();

        // And with no layer chosen at all — the "All layers" default.
        var everything = metadataService.search(orgId,
                new LayerMetadataService.AttributeQuery(null, null, null, null, null, null, null),
                PageRequest.of(0, 25, org.springframework.data.domain.Sort.by("sortOrder", "fieldName")));
        assertThat(everything.getTotalElements()).isGreaterThanOrEqualTo(counted);
    }

    @Test
    @DisplayName("typed detail-table fields are catalogued but never offered to the importer")
    void typeTableFieldsAreCataloguedButNotImportable() {
        AttributeDefinition capacity = byName(
                metadataApi.definitionsForAssetType(organizationId(), AssetType.TANK), "capacity_m3");

        assertThat(capacity.storage()).isEqualTo(AttributeStorage.TYPE_TABLE);
        assertThat(capacity.storageTable()).isEqualTo("tanks");
        // Offering it would invite a mapping the supertype import cannot honour: gis.tanks needs
        // capacity_m3 NOT NULL and positive, which a meter-style import cannot guarantee.
        assertThat(capacity.importable()).isFalse();
    }

    // ---- The claim ----------------------------------------------------------------------------

    @Test
    @DisplayName("a field created at runtime is imported, stored and exported with no code change")
    void createdFieldFlowsThroughImportAndExport() throws Exception {
        UUID orgId = organizationId();
        Layer meters = layer("meters");
        String fieldName = unique("consumer_no");

        metadataService.create(orgId, null, "test", new AttributeCommands.Create(
                meters.getId(), fieldName, "Consumer Number", "The utility's consumer reference.",
                AttributeDataType.TEXT, 40, null, null, null, "TN-0001",
                false, false, false, true, true, true, null, "Added by the integration test"));

        // The importer offers it, because it reads the catalogue rather than a list in its own file.
        assertThat(metadataApi.definitionsForAssetType(orgId, AssetType.METER))
                .extracting(AttributeDefinition::fieldName)
                .contains(fieldName);

        String assetCode = unique("DM-METER").toUpperCase();
        String csv = """
                Meter_No,Consumer,Easting,Northing
                %s,TN-99001,78.1420,11.6643
                """.formatted(assetCode);

        UUID jobId = UUID.randomUUID();
        importService.runImport(jobId, orgId, null, "test", "meters.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8), AssetType.METER, layer("meters").getId(), Map.of(
                        "Meter_No", "asset_code",
                        "Consumer", fieldName,
                        "Easting", "lon",
                        "Northing", "lat"));
        awaitImport(jobId);

        BulkImportService.JobStatus status = importService.status(jobId);
        assertThat(status.rows).isEmpty();
        assertThat(status.imported).isEqualTo(1);

        Asset imported = assetRepository
                .findForTenant(orgId, AssetType.METER.name(), assetCode, PageRequest.of(0, 1))
                .getContent().getFirst();
        assertThat(imported.getAttributes()).containsEntry(fieldName, "TN-99001");
        assertThat(imported.getGeom()).isNotNull();

        // And the exporter emits it, under its display name, because it reads the same catalogue.
        AssetExportService.ExportData export =
                exportService.read(orgId, meters.getId(), false, assetCode, false);
        assertThat(export.columns()).extracting(AttributeDefinition::fieldName).contains(fieldName);
        assertThat(export.rows()).singleElement()
                .satisfies(row -> assertThat(row).containsEntry(fieldName, "TN-99001"));
    }

    @Test
    @DisplayName("retiring a field hides it from exports without destroying a single value")
    void retiringHidesTheFieldAndKeepsTheData() throws Exception {
        UUID orgId = organizationId();
        Layer meters = layer("meters");
        String fieldName = unique("ward_code");

        LayerAttribute attribute = metadataService.create(orgId, null, "test",
                new AttributeCommands.Create(meters.getId(), fieldName, "Ward Code", null,
                        AttributeDataType.TEXT, 20, null, null, null, "W-07",
                        false, false, false, true, true, true, null, null));

        String assetCode = unique("DM-WARD").toUpperCase();
        UUID jobId = UUID.randomUUID();
        importService.runImport(jobId, orgId, null, "test", "meters.csv", "text/csv",
                ("Code,Ward,X,Y\n%s,W-07,78.14,11.66\n".formatted(assetCode))
                        .getBytes(StandardCharsets.UTF_8),
                AssetType.METER, layer("meters").getId(),
                Map.of("Code", "asset_code", "Ward", fieldName, "X", "lon", "Y", "lat"));
        awaitImport(jobId);
        assertThat(importService.status(jobId).imported).isEqualTo(1);

        metadataService.deactivate(attribute.getId(), orgId, null, "test", "Superseded by DMA zones");

        // Gone from the export…
        assertThat(exportService.read(orgId, meters.getId(), false, assetCode, false).columns())
                .extracting(AttributeDefinition::fieldName)
                .doesNotContain(fieldName);
        // …and from what the importer will accept, which is what "hidden from future imports" means.
        assertThat(metadataApi.definitionsForAssetType(orgId, AssetType.METER))
                .extracting(AttributeDefinition::fieldName)
                .doesNotContain(fieldName);

        // But the value is exactly where it was. This is the difference between soft and real.
        Asset stored = assetRepository
                .findForTenant(orgId, AssetType.METER.name(), assetCode, PageRequest.of(0, 1))
                .getContent().getFirst();
        assertThat(stored.getAttributes()).containsEntry(fieldName, "W-07");

        metadataService.reactivate(attribute.getId(), orgId, null, "test", "Needed again");
        assertThat(exportService.read(orgId, meters.getId(), false, assetCode, false).rows())
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry(fieldName, "W-07"));
    }

    @Test
    @DisplayName("a rename is confirmed, then moves the stored values rather than stranding them")
    void renameRequiresConfirmationAndMovesStoredValues() throws Exception {
        UUID orgId = organizationId();
        Layer meters = layer("meters");
        String original = unique("old_ref");
        String renamed = unique("new_ref");

        LayerAttribute attribute = metadataService.create(orgId, null, "test",
                new AttributeCommands.Create(meters.getId(), original, "Old Reference", null,
                        AttributeDataType.TEXT, 40, null, null, null, "R-1",
                        false, false, false, true, true, true, null, null));

        String assetCode = unique("DM-RENAME").toUpperCase();
        UUID jobId = UUID.randomUUID();
        importService.runImport(jobId, orgId, null, "test", "meters.csv", "text/csv",
                ("Code,Ref,X,Y\n%s,R-42,78.14,11.66\n".formatted(assetCode))
                        .getBytes(StandardCharsets.UTF_8),
                AssetType.METER, layer("meters").getId(),
                Map.of("Code", "asset_code", "Ref", original, "X", "lon", "Y", "lat"));
        awaitImport(jobId);
        assertThat(importService.status(jobId).imported).isEqualTo(1);

        // Unconfirmed, the service refuses with a code the client can branch on and a sentence it
        // can show. A generic 409 would be indistinguishable from a duplicate name.
        assertThatThrownBy(() -> metadataService.update(attribute.getId(), orgId, null, "test",
                update(renamed, false)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ATTRIBUTE_CHANGE_REQUIRES_CONFIRMATION);

        metadataService.update(attribute.getId(), orgId, null, "test", update(renamed, true));

        Asset stored = assetRepository
                .findForTenant(orgId, AssetType.METER.name(), assetCode, PageRequest.of(0, 1))
                .getContent().getFirst();
        // The value moved with the field. Leaving it behind would make the renamed field read empty
        // for every row imported before the change, with the data still there and invisible.
        assertThat(stored.getAttributes()).containsEntry(renamed, "R-42");
        assertThat(stored.getAttributes()).doesNotContainKey(original);
    }

    // ---- Guard rails --------------------------------------------------------------------------

    @Test
    @DisplayName("system attributes cannot be renamed, retyped or retired")
    void systemAttributesAreProtected() {
        UUID orgId = organizationId();
        LayerAttribute assetCode = metadataService
                .search(orgId, new LayerMetadataService.AttributeQuery(
                                layer("meters").getId(), "asset_code", null, null, null, null, null),
                        PageRequest.of(0, 5))
                .getContent().stream()
                .filter(a -> a.getFieldName().equals("asset_code"))
                .findFirst().orElseThrow();

        assertThatThrownBy(() -> metadataService.update(assetCode.getId(), orgId, null, "test",
                update("asset_reference", true)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ATTRIBUTE_IS_SYSTEM);

        assertThatThrownBy(() -> metadataService.deactivate(assetCode.getId(), orgId, null, "test", "no"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ATTRIBUTE_IS_SYSTEM);

        // The label is still the tenant's to change — the lock is on identity, not on wording.
        LayerAttribute relabelled = metadataService.update(assetCode.getId(), orgId, null, "test",
                new AttributeCommands.Update(null, "Meter Number", null, null, null, null, null,
                        null, null, null, null, null, null, null, null, false, "Local wording"));
        assertThat(relabelled.getDisplayName()).isEqualTo("Meter Number");
    }

    @Test
    @DisplayName("duplicate and reserved field names are refused with an actionable message")
    void refusesDuplicateAndReservedNames() {
        UUID orgId = organizationId();
        Layer meters = layer("meters");

        assertThatThrownBy(() -> metadataService.create(orgId, null, "test", create(meters, "asset_code")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ATTRIBUTE_FIELD_NAME_TAKEN);

        assertThatThrownBy(() -> metadataService.create(orgId, null, "test", create(meters, "order")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reserved word");

        assertThatThrownBy(() -> metadataService.create(orgId, null, "test", create(meters, "Consumer No")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("underscore");
    }

    @Test
    @DisplayName("recreating a retired field is refused, and points at reactivation")
    void refusesToRecreateARetiredField() {
        UUID orgId = organizationId();
        Layer meters = layer("meters");
        String fieldName = unique("retired_field");

        LayerAttribute attribute = metadataService.create(orgId, null, "test", create(meters, fieldName));
        metadataService.deactivate(attribute.getId(), orgId, null, "test", "no longer collected");

        // Recreating would silently adopt every value already stored under the name. Reactivation
        // is the honest path and the message says so.
        assertThatThrownBy(() -> metadataService.create(orgId, null, "test", create(meters, fieldName)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Reactivate");
    }

    @Test
    @DisplayName("a mapping onto a retired field fails the job rather than every row in it")
    void mappingOntoARetiredFieldFailsFast() throws Exception {
        UUID orgId = organizationId();
        Layer meters = layer("meters");
        String fieldName = unique("dropped_field");

        LayerAttribute attribute = metadataService.create(orgId, null, "test", create(meters, fieldName));
        metadataService.deactivate(attribute.getId(), orgId, null, "test", "retired");

        UUID jobId = UUID.randomUUID();
        importService.runImport(jobId, orgId, null, "test", "meters.csv", "text/csv",
                "Code,Dropped,X,Y\nA-1,value,78.14,11.66\n".getBytes(StandardCharsets.UTF_8),
                AssetType.METER, layer("meters").getId(),
                Map.of("Code", "asset_code", "Dropped", fieldName, "X", "lon", "Y", "lat"));
        awaitImport(jobId);

        BulkImportService.JobStatus status = importService.status(jobId);
        // One refusal before anything is read, not fifty thousand identical row errors.
        assertThat(status.state).startsWith("FAILED");
        assertThat(status.state).contains("retired in Data Management");
        assertThat(status.total).isZero();
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private static AttributeDefinition byName(List<AttributeDefinition> definitions, String fieldName) {
        return definitions.stream()
                .filter(d -> d.fieldName().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No attribute named " + fieldName));
    }

    private static AttributeCommands.Create create(Layer layer, String fieldName) {
        return new AttributeCommands.Create(layer.getId(), fieldName, null, null,
                AttributeDataType.TEXT, 40, null, null, null, "sample",
                false, false, false, true, true, true, null, null);
    }

    private static AttributeCommands.Update update(String fieldName, boolean confirm) {
        return new AttributeCommands.Update(fieldName, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, confirm, "renamed by test");
    }

    /**
     * Waits for the asynchronous import to reach a terminal state.
     *
     * <p>{@code runImport} is {@code @Async}, so the call returns before a row is read. Polling the
     * job's own status is what the frontend does; asserting on a fixed sleep would either be flaky
     * on a loaded CI machine or slow on every run.
     */
    private void awaitImport(UUID jobId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            BulkImportService.JobStatus status = importService.status(jobId);
            if (status != null && !"RUNNING".equals(status.state)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Import job " + jobId + " did not finish within 10 seconds");
    }
}
