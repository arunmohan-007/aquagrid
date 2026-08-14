package com.aquagrid.platform.gis.web.controller;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.common.web.PageResponse;
import com.aquagrid.platform.gis.application.command.AttributeCommands;
import com.aquagrid.platform.gis.application.service.ImportMappingService;
import com.aquagrid.platform.gis.application.service.LayerMetadataService;
import com.aquagrid.platform.gis.application.service.LayerMetadataService.AttributeQuery;
import com.aquagrid.platform.gis.domain.enums.AttributeDataType;
import com.aquagrid.platform.gis.domain.metadata.FieldNamePolicy;
import com.aquagrid.platform.gis.web.dto.MetadataDtos;
import com.aquagrid.platform.security.core.AuthenticatedPrincipal;
import com.aquagrid.platform.security.core.Permissions;
import com.aquagrid.platform.security.core.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Data Management — the master catalogue of layers and their attributes.
 *
 * <p>This is the API the platform's field definitions live behind. Import, export and the dynamic
 * forms to come read from it rather than from a table compiled into the application, so adding a
 * field to a layer is a call to {@code POST /attributes} and nothing else — no migration, no
 * release, no restart.
 *
 * <p>Two permissions, and the split is not ceremonial. {@code gis:metadata:read} is a field list.
 * {@code gis:metadata:manage} changes what every subsequent import writes and every subsequent
 * export contains, which is a schema-shaped change made at runtime, and it is granted to the
 * administrator roles only.
 */
@Tag(name = "Data Management",
        description = "The layer and attribute catalogue that drives import, export and dynamic forms")
@RestController
@RequestMapping(value = ApiPaths.DATA_MANAGEMENT, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class DataManagementController {

    /**
     * Sortable columns, as an allow-list.
     *
     * <p>A {@code Pageable} bound straight from the query string will happily sort by any property
     * name the client sends, including one that is not a persistent field — which surfaces as a
     * 500 from deep inside Hibernate rather than as a 400 the caller can act on. Naming the sortable
     * columns costs one set and turns that into a clear rejection.
     */
    private static final Set<String> SORTABLE = Set.of(
            "fieldName", "displayName", "dataType", "maxLength", "mandatory", "uniqueValue",
            "editable", "visible", "active", "sortOrder", "createdAt", "updatedAt");

    private static final int MAX_PAGE_SIZE = 200;

    private final LayerMetadataService metadataService;
    private final ImportMappingService mappingService;

    // ---- Layers -------------------------------------------------------------------------------

    @GetMapping("/layers")
    @PreAuthorize("hasAuthority('" + Permissions.METADATA_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List every layer in the catalogue",
            description = """
                    Every GIS layer the tenant has, with the number of active attributes on each.
                    The layer catalogue is `gis.layers` itself rather than a parallel table — a
                    second list of the same layers would drift from the one the map and the tile
                    endpoint already read, and the two would then describe different products.""")
    public List<MetadataDtos.LayerResponse> layers() {
        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        return metadataService.listLayers(orgId).stream()
                .map(MetadataDtos.LayerResponse::from)
                .toList();
    }

    // ---- Attributes ---------------------------------------------------------------------------

    @GetMapping("/attributes")
    @PreAuthorize("hasAuthority('" + Permissions.METADATA_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Search the attribute catalogue",
            description = """
                    Paginated, sortable and filterable by layer, free text, data type, and the
                    Mandatory / Editable / Visible / Active flags. Each flag is tri-state: omit it
                    to not filter on it.

                    Unlike the definitions the importer and exporter read, this returns inactive
                    attributes too — the screen has to show a retired field in order to offer
                    reviving it.""")
    public PageResponse<MetadataDtos.AttributeResponse> attributes(
            @RequestParam(required = false) UUID layerId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) AttributeDataType dataType,
            @RequestParam(required = false) Boolean mandatory,
            @RequestParam(required = false) Boolean editable,
            @RequestParam(required = false) Boolean visible,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @Parameter(description = "Sortable: " + "fieldName, displayName, dataType, maxLength, "
                    + "mandatory, uniqueValue, editable, visible, active, sortOrder, createdAt, updatedAt")
            @RequestParam(defaultValue = "sortOrder") String sort,
            @RequestParam(defaultValue = "ASC") Sort.Direction direction) {

        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        Page<MetadataDtos.AttributeResponse> result = metadataService
                .search(orgId, new AttributeQuery(layerId, search, dataType, mandatory, editable, visible, active),
                        pageRequest(page, size, sort, direction))
                .map(MetadataDtos.AttributeResponse::from);
        return PageResponse.of(result);
    }

    @GetMapping("/attributes/{attributeId}")
    @PreAuthorize("hasAuthority('" + Permissions.METADATA_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Fetch one attribute")
    public MetadataDtos.AttributeResponse attribute(@PathVariable UUID attributeId) {
        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        return MetadataDtos.AttributeResponse.from(metadataService.require(attributeId, orgId));
    }

    @PostMapping("/attributes")
    @PreAuthorize("hasAuthority('" + Permissions.METADATA_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an attribute",
            description = """
                    Creates a field on a layer, immediately available to the import mapper, the
                    exporter and any form that renders the layer — with no code change and no
                    migration.

                    The value lands as a key in the asset's JSONB attribute bag rather than as a new
                    column. That is the design: generating `ALTER TABLE` at runtime would mean the
                    web tier holding DDL rights on its own schema and taking an exclusive lock on the
                    tenant's largest table on a button press.

                    Field names are lower-cased and validated: letters, digits and underscore only,
                    starting with a letter, at most 63 characters, and not a reserved SQL word.""")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "409", description = "A field with that name already exists on the layer")
    public MetadataDtos.AttributeResponse create(@Valid @RequestBody MetadataDtos.CreateRequest request) {
        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        return MetadataDtos.AttributeResponse.from(metadataService.create(
                principal.organizationId(), principal.userId(), principal.username(),
                new AttributeCommands.Create(
                        request.layerId(), request.fieldName(), request.displayName(),
                        request.description(), request.dataType(), request.maxLength(),
                        request.numericPrecision(), request.numericScale(), request.defaultValue(),
                        request.sampleValue(), request.mandatory(), request.unique(),
                        request.editable() == null || request.editable(),
                        request.visible() == null || request.visible(),
                        request.active() == null || request.active(),
                        request.sortOrder(), request.changeReason())));
    }

    @PutMapping("/attributes/{attributeId}")
    @PreAuthorize("hasAuthority('" + Permissions.METADATA_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Update an attribute",
            description = """
                    Every field is optional; omitting one leaves it unchanged rather than clearing
                    it, so a form that renders half the fields cannot blank the other half.

                    Changing `fieldName` or `dataType` affects data that already exists and is
                    refused on the first attempt with `ATTRIBUTE_CHANGE_REQUIRES_CONFIRMATION` and a
                    description of the effect. Resubmit with `confirmBreakingChange: true` to
                    proceed. A rename then rewrites the stored key on every asset of the layer; a
                    retype leaves stored values as they are and records the previous type in the
                    attribute's history, so rows written under the old definition stay
                    interpretable.

                    System attributes — the ones the platform's own code reads by name — accept
                    label, description, default, sample and visibility changes only.""")
    @ApiResponse(responseCode = "409",
            description = "Rename or retype not confirmed, or the new field name is taken")
    @ApiResponse(responseCode = "403", description = "A system attribute's name, type or flags")
    public MetadataDtos.AttributeResponse update(@PathVariable UUID attributeId,
                                                 @Valid @RequestBody MetadataDtos.UpdateRequest request) {
        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        return MetadataDtos.AttributeResponse.from(metadataService.update(
                attributeId, principal.organizationId(), principal.userId(), principal.username(),
                new AttributeCommands.Update(
                        request.fieldName(), request.displayName(), request.description(),
                        request.dataType(), request.maxLength(), request.numericPrecision(),
                        request.numericScale(), request.defaultValue(), request.sampleValue(),
                        request.mandatory(), request.unique(), request.editable(), request.visible(),
                        request.sortOrder(), request.confirmBreakingChange(), request.changeReason())));
    }

    @PostMapping("/attributes/{attributeId}/deactivate")
    @PreAuthorize("hasAuthority('" + Permissions.METADATA_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Retire an attribute (soft delete)",
            description = """
                    The only delete this module has, and it removes nothing. Values already written
                    stay in the attribute bag exactly as they were; what changes is that the field
                    stops being offered for import mapping, stops appearing in exports and stops
                    being rendered on forms. Reactivating restores all three and the historical
                    values come back with it.

                    System attributes cannot be retired — the platform's own code reads them, so a
                    deactivated `asset_code` would be a field the catalogue says is gone and the
                    importer still writes. Clear `visible` instead to keep it out of exports.""")
    public MetadataDtos.AttributeResponse deactivate(
            @PathVariable UUID attributeId,
            @RequestBody(required = false) MetadataDtos.StateChangeRequest request) {
        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        return MetadataDtos.AttributeResponse.from(metadataService.deactivate(
                attributeId, principal.organizationId(), principal.userId(), principal.username(),
                request == null ? null : request.reason()));
    }

    @PostMapping("/attributes/{attributeId}/reactivate")
    @PreAuthorize("hasAuthority('" + Permissions.METADATA_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Return a retired attribute to service",
            description = "Every value stored under the field before it was retired becomes readable "
                    + "again — which is why retiring is the supported path and recreating is not.")
    public MetadataDtos.AttributeResponse reactivate(
            @PathVariable UUID attributeId,
            @RequestBody(required = false) MetadataDtos.StateChangeRequest request) {
        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        return MetadataDtos.AttributeResponse.from(metadataService.reactivate(
                attributeId, principal.organizationId(), principal.userId(), principal.username(),
                request == null ? null : request.reason()));
    }

    @GetMapping("/attributes/{attributeId}/history")
    @PreAuthorize("hasAuthority('" + Permissions.METADATA_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "An attribute's definition history",
            description = """
                    What the definition was, in full, at every change. Distinct from the audit trail,
                    which answers who and when: this answers what the field meant at the time a given
                    value was written — the question you have when a field was widened or retyped and
                    there is data on both sides of the change.""")
    public PageResponse<MetadataDtos.HistoryResponse> history(
            @PathVariable UUID attributeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        return PageResponse.of(metadataService
                .history(attributeId, orgId, PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)))
                .map(MetadataDtos.HistoryResponse::from));
    }

    @GetMapping("/attributes/{attributeId}/rules")
    @PreAuthorize("hasAuthority('" + Permissions.METADATA_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "An attribute's validation rules")
    public List<MetadataDtos.RuleResponse> rules(@PathVariable UUID attributeId) {
        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        return metadataService.rulesFor(attributeId, orgId).stream()
                .map(MetadataDtos.RuleResponse::from)
                .toList();
    }

    // ---- Catalogue export ---------------------------------------------------------------------

    @GetMapping(value = "/attributes/export", produces = "text/csv")
    @PreAuthorize("hasAuthority('" + Permissions.METADATA_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Export the attribute catalogue as CSV",
            description = """
                    The data dictionary, as a file. Honours the same filters as the grid, so what is
                    exported is what is on screen. This is the document a utility hands to a survey
                    contractor before the next deliverable, which is why it carries the description,
                    the sample value and the constraints rather than only the names.""")
    public ResponseEntity<byte[]> exportCatalogue(
            @RequestParam(required = false) UUID layerId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) AttributeDataType dataType,
            @RequestParam(required = false) Boolean mandatory,
            @RequestParam(required = false) Boolean editable,
            @RequestParam(required = false) Boolean visible,
            @RequestParam(required = false) Boolean active) {

        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        /*
         * One large page rather than a stream. The catalogue is bounded by how many fields a utility
         * has defined — hundreds, not millions — so the whole result fits comfortably in memory and
         * streaming would add machinery for a size this data cannot reach.
         */
        var rows = metadataService.search(orgId,
                new AttributeQuery(layerId, search, dataType, mandatory, editable, visible, active),
                PageRequest.of(0, 10_000, Sort.by("sortOrder", "fieldName")));

        Map<UUID, String> layerTitles = metadataService.listLayers(orgId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        LayerMetadataService.LayerSummary::id,
                        LayerMetadataService.LayerSummary::title));

        StringBuilder csv = new StringBuilder();
        csv.append("Layer,Field Name,Display Name,Description,Data Type,Length,Precision,Scale,")
                .append("Default Value,Sample Value,Mandatory,Unique,Editable,Visible,Active,")
                .append("Created By,Created Date,Modified By,Modified Date\n");
        rows.forEach(a -> csv
                .append(csvCell(layerTitles.getOrDefault(a.getLayerId(), ""))).append(',')
                .append(csvCell(a.getFieldName())).append(',')
                .append(csvCell(a.getDisplayName())).append(',')
                .append(csvCell(a.getDescription())).append(',')
                .append(csvCell(a.getDataType().name())).append(',')
                .append(csvCell(a.getMaxLength())).append(',')
                .append(csvCell(a.getNumericPrecision())).append(',')
                .append(csvCell(a.getNumericScale())).append(',')
                .append(csvCell(a.getDefaultValue())).append(',')
                .append(csvCell(a.getSampleValue())).append(',')
                .append(yesNo(a.isMandatory())).append(',')
                .append(yesNo(a.isUniqueValue())).append(',')
                .append(yesNo(a.isEditable())).append(',')
                .append(yesNo(a.isVisible())).append(',')
                .append(yesNo(a.isActive())).append(',')
                .append(csvCell(a.getCreatedBy())).append(',')
                .append(csvCell(a.getCreatedAt())).append(',')
                .append(csvCell(a.getUpdatedBy())).append(',')
                .append(csvCell(a.getUpdatedAt())).append('\n'));

        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"attribute-catalogue.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    // ---- Reference data -----------------------------------------------------------------------

    @GetMapping("/data-types")
    @PreAuthorize("hasAuthority('" + Permissions.METADATA_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "The data types an attribute can declare",
            description = """
                    Served from the enum the server validates against, so the create form cannot
                    offer a type the server would reject — the same rule the device registration
                    form follows for transports. `usesLength` and `usesPrecisionScale` tell the form
                    which numeric configuration fields to show, so that too is server-driven rather
                    than a second table in the client.""")
    public List<MetadataDtos.DataTypeResponse> dataTypes() {
        return List.of(
                type(AttributeDataType.TEXT, "Text", "Variable-length text with a configurable maximum length.", true, "Kadambur"),
                type(AttributeDataType.SHORT_INTEGER, "Short Integer", "Whole number, -32,768 to 32,767.", true, "12"),
                type(AttributeDataType.INTEGER, "Integer", "Whole number, roughly ±2.1 billion.", true, "1500"),
                type(AttributeDataType.LONG_INTEGER, "Long Integer", "Whole number, 64-bit.", true, "9007199254740991"),
                type(AttributeDataType.DECIMAL, "Decimal", "Exact decimal with configurable precision and scale. Use this for volumes, readings and money.", true, "150.75"),
                type(AttributeDataType.DOUBLE, "Double", "Floating point. Faster, but not exact — prefer Decimal where the value is counted rather than measured.", true, "12.345678"),
                type(AttributeDataType.BOOLEAN, "Boolean", "Yes or no. Accepts Y/N, true/false and 1/0 on import.", true, "Yes"),
                type(AttributeDataType.DATE, "Date", "Calendar date, dd-MMM-yyyy.", true, "02-Jan-2025"),
                type(AttributeDataType.DATE_TIME, "Date Time", "Date and time of day.", true, "2024-03-01T09:30:00"),
                type(AttributeDataType.TIME, "Time", "Time of day.", true, "09:30:00"),
                type(AttributeDataType.UUID, "UUID", "A 128-bit identifier, for references to rows in other systems.", true, "3f2504e0-4f89-11d3-9a0c-0305e82c3301"),
                /*
                 * Geometry is described but not selectable. An asset has exactly one geometry, which
                 * every layer already declares; a second geometry field would have nowhere to be
                 * drawn from. Listing it greyed out is more honest than omitting it, because the
                 * catalogue does contain geometry attributes and an administrator will look for it.
                 */
                type(AttributeDataType.GEOMETRY, "Geometry", "The asset's location. Declared once per layer by the platform and read from the source file.", false, "POINT (78.1420 11.6643)"));
    }

    @GetMapping("/reserved-words")
    @PreAuthorize("hasAuthority('" + Permissions.METADATA_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Field names that cannot be used",
            description = "So the create form can warn as the operator types rather than after a "
                    + "round trip. The server enforces the same list regardless.")
    public List<String> reservedWords() {
        return FieldNamePolicy.reservedWords().stream().sorted().toList();
    }

    // ---- Import mapping profiles ---------------------------------------------------------------

    @GetMapping("/import-mappings/profiles")
    @PreAuthorize("hasAuthority('" + Permissions.METADATA_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Saved mapping profile names for a layer")
    public List<String> mappingProfiles(@RequestParam UUID layerId) {
        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        return mappingService.profileNames(orgId, layerId);
    }

    @GetMapping("/import-mappings")
    @PreAuthorize("hasAuthority('" + Permissions.METADATA_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Load a saved mapping profile",
            description = "Attributes retired since the profile was saved are returned unmapped "
                    + "rather than restored — offering a retired field is how one gets written again "
                    + "by accident.")
    public MetadataDtos.MappingProfileResponse mappingProfile(@RequestParam UUID layerId,
                                                              @RequestParam String profileName) {
        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        return new MetadataDtos.MappingProfileResponse(profileName, layerId,
                mappingService.loadProfile(orgId, layerId, profileName));
    }

    @PutMapping("/import-mappings")
    @PreAuthorize("hasAuthority('" + Permissions.METADATA_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Save a mapping profile",
            description = "Replaces any previous version of the profile wholesale. A column dropped "
                    + "from this quarter's deliverable must disappear from the profile; a merge "
                    + "would keep mapping a column that is no longer in the file.")
    public MetadataDtos.MappingProfileResponse saveMapping(
            @Valid @RequestBody MetadataDtos.SaveMappingRequest request) {
        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        mappingService.saveProfile(principal.organizationId(), principal.userId(),
                principal.username(), request.layerId(), request.profileName(), request.mapping());
        return new MetadataDtos.MappingProfileResponse(request.profileName().trim(), request.layerId(),
                mappingService.loadProfile(principal.organizationId(), request.layerId(),
                        request.profileName().trim()));
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private static MetadataDtos.DataTypeResponse type(AttributeDataType t, String label,
                                                      String description, boolean selectable,
                                                      String sample) {
        return new MetadataDtos.DataTypeResponse(t.name(), label, description, t.usesLength(),
                t.usesPrecisionScale(), t.isNumeric(), selectable, sample);
    }

    private static PageRequest pageRequest(int page, int size, String sort, Sort.Direction direction) {
        if (!SORTABLE.contains(sort)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'" + sort + "' is not a sortable column. Sortable: "
                            + String.join(", ", SORTABLE.stream().sorted().toList()) + ".");
        }
        /*
         * Field name is appended as a tiebreak on every sort. Without it, two rows with the same
         * data type land in whatever order the plan produced, which differs between pages — so
         * paging through a sorted grid can show the same row twice and miss another entirely.
         */
        Sort ordering = Sort.by(direction, sort);
        if (!"fieldName".equals(sort)) {
            ordering = ordering.and(Sort.by(Sort.Direction.ASC, "fieldName"));
        }
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), ordering);
    }

    private static String yesNo(boolean value) {
        return value ? "Yes" : "No";
    }

    /**
     * RFC 4180 quoting.
     *
     * <p>Descriptions contain commas and, occasionally, quotation marks. An unquoted cell shifts
     * every column after it on the row, which in a data dictionary means the sample value of one
     * field appearing as the default of the next.
     */
    private static String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (text.indexOf(',') < 0 && text.indexOf('"') < 0 && text.indexOf('\n') < 0) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
