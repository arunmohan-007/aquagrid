package com.aquagrid.platform.gis.web.dto;

import com.aquagrid.platform.gis.application.service.LayerMetadataService;
import com.aquagrid.platform.gis.domain.enums.AttributeDataType;
import com.aquagrid.platform.gis.domain.enums.AttributeStorage;
import com.aquagrid.platform.gis.domain.model.AttributeValidationRule;
import com.aquagrid.platform.gis.domain.model.LayerAttribute;
import com.aquagrid.platform.gis.domain.model.LayerAttributeHistory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request and response shapes for the Data Management API. */
public final class MetadataDtos {

    private MetadataDtos() {
    }

    // ---- Responses ----------------------------------------------------------------------------

    @Schema(name = "MetadataLayer", description = "A layer in the catalogue, with its field count")
    public record LayerResponse(
            UUID id,
            String code,
            String title,
            String description,
            String assetType,
            boolean visible,
            int sortOrder,
            @Schema(description = "Active attributes on this layer") long attributeCount
    ) {
        public static LayerResponse from(LayerMetadataService.LayerSummary summary) {
            return new LayerResponse(summary.id(), summary.code(), summary.title(),
                    summary.description(), summary.assetType().name(), summary.visible(),
                    summary.sortOrder(), summary.activeAttributeCount());
        }
    }

    /**
     * One attribute, as the grid renders it.
     *
     * <p>Carries the full audit quartet — created by/at, modified by/at — because the grid has
     * columns for them. It carries the actor as a raw id rather than a name: resolving user names
     * would mean the GIS module reading {@code identity.users}, which is the cross-module join the
     * architecture forbids. The client already holds the directory it needs to render a name.
     */
    @Schema(name = "LayerAttribute", description = "One field definition on a layer")
    public record AttributeResponse(
            UUID id,
            UUID layerId,
            String fieldName,
            String displayName,
            String description,
            String dataType,
            Integer maxLength,
            Integer numericPrecision,
            Integer numericScale,
            String defaultValue,
            String sampleValue,
            boolean mandatory,
            @Schema(name = "unique") boolean uniqueValue,
            @Schema(description = "True when the bulk importer uses this field (alongside Asset "
                    + "Code) to recognise a row it has already seen")
            boolean duplicateCheck,
            boolean editable,
            boolean visible,
            boolean active,
            @Schema(description = "True for attributes the platform's own code reads by name. "
                    + "Their name, type and mandatory flag are locked; labels are not.")
            boolean system,
            @Schema(description = "JSONB, COLUMN, GEOMETRY or TYPE_TABLE")
            String storage,
            @Schema(description = "False when the field cannot be filled from an imported column")
            boolean importable,
            int sortOrder,
            UUID createdBy,
            Instant createdAt,
            UUID modifiedBy,
            Instant modifiedAt
    ) {
        public static AttributeResponse from(LayerAttribute a) {
            return new AttributeResponse(a.getId(), a.getLayerId(), a.getFieldName(),
                    a.getDisplayName(), a.getDescription(), a.getDataType().name(), a.getMaxLength(),
                    a.getNumericPrecision(), a.getNumericScale(), a.getDefaultValue(),
                    a.getSampleValue(), a.isMandatory(), a.isUniqueValue(), a.isDuplicateCheck(),
                    a.isEditable(), a.isVisible(), a.isActive(), a.isSystem(), a.getStorage().name(),
                    a.getStorage().isImportable(), a.getSortOrder(),
                    a.getCreatedBy(), a.getCreatedAt(), a.getUpdatedBy(), a.getUpdatedAt());
        }
    }

    /**
     * A data type as the create form offers it.
     *
     * <p>Served from the enum the server validates against rather than duplicated in the client —
     * the same rule the device registration form follows for transports. A client with its own copy
     * of this list is a client that will one day offer a type the server rejects.
     */
    @Schema(name = "AttributeDataTypeInfo", description = "A selectable data type and what it configures")
    public record DataTypeResponse(
            String code,
            String label,
            String description,
            @Schema(description = "True when Length applies") boolean usesLength,
            @Schema(description = "True when Precision and Scale apply") boolean usesPrecisionScale,
            @Schema(description = "True when numeric range rules apply") boolean numeric,
            @Schema(description = "False for types only the platform may declare") boolean selectable,
            String sampleValue
    ) {
    }

    @Schema(name = "AttributeValidationRule")
    public record RuleResponse(
            UUID id,
            UUID attributeId,
            String ruleType,
            String ruleValue,
            String errorMessage,
            boolean active,
            int sortOrder
    ) {
        public static RuleResponse from(AttributeValidationRule r) {
            return new RuleResponse(r.getId(), r.getAttributeId(), r.getRuleType().name(),
                    r.getRuleValue(), r.getErrorMessage(), r.isActive(), r.getSortOrder());
        }
    }

    @Schema(name = "AttributeHistoryEntry",
            description = "What a definition was, so values written under it stay interpretable")
    public record HistoryResponse(
            Long id,
            UUID attributeId,
            String fieldName,
            String changeType,
            Map<String, Object> previousState,
            Map<String, Object> newState,
            String changeReason,
            UUID changedBy,
            Instant changedAt
    ) {
        public static HistoryResponse from(LayerAttributeHistory h) {
            return new HistoryResponse(h.getId(), h.getAttributeId(), h.getFieldName(),
                    h.getChangeType().name(), h.getPreviousState(), h.getNewState(),
                    h.getChangeReason(), h.getChangedBy(), h.getChangedAt());
        }
    }

    // ---- Requests -----------------------------------------------------------------------------

    @Schema(name = "CreateAttributeRequest")
    public record CreateRequest(
            @NotNull UUID layerId,
            @NotBlank
            @Size(max = 63)
            @Schema(description = "Lower-case letters, digits and underscore, starting with a letter. "
                    + "Reserved SQL words are refused.", example = "consumer_no")
            String fieldName,
            @Size(max = 120) String displayName,
            @Size(max = 500) String description,
            @NotNull AttributeDataType dataType,
            @Min(1) @Max(10_485_760) Integer maxLength,
            @Min(1) @Max(1000) Integer numericPrecision,
            @Min(0) @Max(1000) Integer numericScale,
            @Size(max = 255) String defaultValue,
            @Size(max = 255) String sampleValue,
            boolean mandatory,
            boolean unique,
            @Schema(description = "Use this field (alongside Asset Code) to recognise a row a bulk "
                    + "import has already seen")
            boolean duplicateCheck,
            @Schema(defaultValue = "true") Boolean editable,
            @Schema(defaultValue = "true") Boolean visible,
            @Schema(defaultValue = "true") Boolean active,
            Integer sortOrder,
            @Size(max = 500) String changeReason
    ) {
    }

    /**
     * Update an attribute. Null means "leave it alone", never "clear it".
     *
     * @param confirmBreakingChange required when {@code fieldName} or {@code dataType} differs from
     *                              the stored definition. Both reshape data that already exists, so
     *                              the first attempt is answered with
     *                              {@code ATTRIBUTE_CHANGE_REQUIRES_CONFIRMATION} and a description
     *                              of the effect for the client to show.
     */
    @Schema(name = "UpdateAttributeRequest")
    public record UpdateRequest(
            @Size(max = 63) String fieldName,
            @Size(max = 120) String displayName,
            @Size(max = 500) String description,
            AttributeDataType dataType,
            @Min(1) @Max(10_485_760) Integer maxLength,
            @Min(1) @Max(1000) Integer numericPrecision,
            @Min(0) @Max(1000) Integer numericScale,
            @Size(max = 255) String defaultValue,
            @Size(max = 255) String sampleValue,
            Boolean mandatory,
            Boolean unique,
            Boolean duplicateCheck,
            Boolean editable,
            Boolean visible,
            Integer sortOrder,
            boolean confirmBreakingChange,
            @Size(max = 500) String changeReason
    ) {
    }

    @Schema(name = "DeactivateAttributeRequest")
    public record StateChangeRequest(
            @Schema(description = "Why. Stored on the history entry, which is the only durable record "
                    + "of why a field stopped being imported.")
            @Size(max = 500) String reason
    ) {
    }

    @Schema(name = "SaveImportMappingRequest")
    public record SaveMappingRequest(
            @NotNull UUID layerId,
            @NotBlank @Size(max = 120) String profileName,
            @Schema(description = "Source column name → attribute field name. A null or 'ignore' "
                    + "value stores the operator's decision to drop the column.")
            @NotNull Map<String, String> mapping
    ) {
    }

    @Schema(name = "ImportMappingProfile")
    public record MappingProfileResponse(
            String profileName,
            UUID layerId,
            Map<String, String> mapping
    ) {
    }

    /** The mappable field list the import wizard renders, with the geometry filter already applied. */
    @Schema(name = "ImportTargetField")
    public record TargetFieldResponse(
            String fieldName,
            String displayName,
            String description,
            String dataType,
            boolean mandatory,
            String sampleValue,
            @Schema(description = "Alternative spellings the wizard auto-matches source columns against")
            List<String> aliases
    ) {
    }
}
