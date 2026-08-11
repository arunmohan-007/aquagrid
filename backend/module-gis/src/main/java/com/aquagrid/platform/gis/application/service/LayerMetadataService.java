package com.aquagrid.platform.gis.application.service;

import com.aquagrid.platform.common.audit.AuditCategory;
import com.aquagrid.platform.common.audit.AuditEvent;
import com.aquagrid.platform.common.audit.AuditEventTypes;
import com.aquagrid.platform.common.audit.AuditService;
import com.aquagrid.platform.common.audit.AuditSeverity;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.gis.api.AttributeDefinition;
import com.aquagrid.platform.gis.api.LayerMetadataApi;
import com.aquagrid.platform.gis.application.command.AttributeCommands;
import com.aquagrid.platform.gis.domain.enums.AssetType;
import com.aquagrid.platform.gis.domain.enums.AttributeChangeType;
import com.aquagrid.platform.gis.domain.enums.AttributeDataType;
import com.aquagrid.platform.gis.domain.enums.AttributeStorage;
import com.aquagrid.platform.gis.domain.metadata.FieldNamePolicy;
import com.aquagrid.platform.gis.domain.model.AttributeValidationRule;
import com.aquagrid.platform.gis.domain.model.Layer;
import com.aquagrid.platform.gis.domain.model.LayerAttribute;
import com.aquagrid.platform.gis.domain.model.LayerAttributeHistory;
import com.aquagrid.platform.gis.infrastructure.persistence.AssetRepository;
import com.aquagrid.platform.gis.infrastructure.persistence.AttributeValidationRuleRepository;
import com.aquagrid.platform.gis.infrastructure.persistence.LayerAttributeHistoryRepository;
import com.aquagrid.platform.gis.infrastructure.persistence.LayerAttributeRepository;
import com.aquagrid.platform.gis.infrastructure.persistence.LayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Data Management module's service: the attribute catalogue's only writer, and its read side.
 *
 * <p>Everything the platform knows about a layer's fields is a row in
 * {@code gis.layer_attribute_master}, and this class owns the rules for what a row may say and how
 * it may change. The rules exist because a field definition is not configuration in the ordinary
 * sense — it is the contract every import already written and every export already scheduled was
 * built against. Changing one is closer to a migration than to a preference, and this service is
 * where that is made explicit rather than discovered later:
 *
 * <ul>
 *   <li><b>There is no delete.</b> Deactivation retires a field from imports, exports and forms
 *       while every value written under it stays exactly where it was, readable again the moment
 *       it is reactivated. Removing the definition would orphan data nothing else describes.</li>
 *   <li><b>System attributes are partly frozen.</b> The label, description, sample and visibility
 *       of {@code asset_code} belong to the tenant; its name, type and mandatory-ness do not,
 *       because the platform's own Java reads that field by name and the column it lives in is
 *       NOT NULL. This is enforced here rather than hoped for in the UI.</li>
 *   <li><b>Renames and retypes are allowed, confirmed, and recorded.</b> Both reshape data that
 *       already exists. A rename rewrites the JSONB key on every affected asset in one statement,
 *       because the alternative — leaving the values behind under the old key — is a field that
 *       reads empty for every row imported before the change. A retype leaves stored values as
 *       they are and records the previous type in the history table, because re-coercing a
 *       tenant's whole asset base on a dropdown change is a decision no dialog should be able to
 *       make.</li>
 * </ul>
 *
 * <p>Reads are served through {@link LayerMetadataApi} as immutable snapshots. Handing a consumer
 * the entity would give it a dirty-checked object it can modify by accident; handing it a record
 * cannot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LayerMetadataService implements LayerMetadataApi {

    private final LayerRepository layerRepository;
    private final LayerAttributeRepository attributeRepository;
    private final AttributeValidationRuleRepository ruleRepository;
    private final LayerAttributeHistoryRepository historyRepository;
    private final AssetRepository assetRepository;
    private final AuditService auditService;

    // ---- Layers -------------------------------------------------------------------------------

    /**
     * Every layer the tenant has, with its attribute counts.
     *
     * <p>The counts are what make the screen usable rather than decorative: "Valves — 17 fields, 15
     * active" tells an administrator at a glance which layers have been curated and which are still
     * running on the seeded defaults.
     */
    @Transactional(readOnly = true)
    public List<LayerSummary> listLayers(UUID organizationId) {
        return layerRepository.findByOrganizationIdOrderBySortOrderAsc(organizationId).stream()
                .map(layer -> new LayerSummary(
                        layer.getId(),
                        layer.getCode(),
                        layer.getTitle(),
                        layer.getDescription(),
                        layer.getAssetType(),
                        layer.isVisible(),
                        layer.getSortOrder(),
                        attributeRepository.countByOrganizationIdAndLayerIdAndActiveTrue(
                                organizationId, layer.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public Layer requireLayer(UUID layerId, UUID organizationId) {
        return layerRepository.findByIdAndOrganizationId(layerId, organizationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No layer " + layerId + " in this organisation."));
    }

    // ---- Attribute reads ----------------------------------------------------------------------

    /**
     * The Data Management grid's query.
     *
     * <p>Unlike the {@link LayerMetadataApi} reads, this one returns inactive attributes too: the
     * screen has to show a retired field in order to offer reviving it, and a catalogue that hides
     * its own soft deletes gives an administrator no way to tell "never existed" from "retired last
     * March".
     */
    @Transactional(readOnly = true)
    public Page<LayerAttribute> search(UUID organizationId, AttributeQuery query, Pageable pageable) {
        return attributeRepository.search(organizationId, query.layerId(), blankToNull(query.search()),
                query.dataType() == null ? null : query.dataType().name(),
                query.mandatory(), query.editable(), query.visible(), query.active(), pageable);
    }

    @Transactional(readOnly = true)
    public LayerAttribute require(UUID attributeId, UUID organizationId) {
        return attributeRepository.findByIdAndOrganizationId(attributeId, organizationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No attribute " + attributeId + " in this organisation."));
    }

    @Transactional(readOnly = true)
    public List<AttributeValidationRule> rulesFor(UUID attributeId, UUID organizationId) {
        return ruleRepository.findByOrganizationIdAndAttributeIdOrderBySortOrderAsc(
                organizationId, attributeId);
    }

    @Transactional(readOnly = true)
    public Page<LayerAttributeHistory> history(UUID attributeId, UUID organizationId, Pageable pageable) {
        require(attributeId, organizationId);
        return historyRepository.findByOrganizationIdAndAttributeIdOrderByChangedAtDesc(
                organizationId, attributeId, pageable);
    }

    @Transactional(readOnly = true)
    public List<String> usedDataTypes(UUID organizationId) {
        return attributeRepository.findUsedDataTypes(organizationId);
    }

    // ---- LayerMetadataApi ---------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<AttributeDefinition> definitionsForLayer(UUID organizationId, UUID layerId) {
        List<LayerAttribute> attributes = attributeRepository
                .findByOrganizationIdAndLayerIdOrderBySortOrderAscFieldNameAsc(organizationId, layerId)
                .stream().filter(LayerAttribute::isActive).toList();
        if (attributes.isEmpty()) {
            return List.of();
        }
        /*
         * Rules for the whole layer in one query, then grouped in memory. An import evaluates rules
         * per value per row; resolving them per attribute inside that loop is the N+1 that turns a
         * 50k-row file into hundreds of thousands of round trips.
         */
        Map<UUID, List<AttributeValidationRule>> rulesByAttribute = ruleRepository
                .findActiveForAttributes(organizationId, attributes.stream().map(LayerAttribute::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(AttributeValidationRule::getAttributeId));

        return attributes.stream()
                .map(a -> AttributeDefinition.from(a,
                        rulesByAttribute.getOrDefault(a.getId(), List.of())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttributeDefinition> definitionsForAssetType(UUID organizationId, AssetType assetType) {
        return layerIdForAssetType(organizationId, assetType)
                .map(layerId -> definitionsForLayer(organizationId, layerId))
                .orElseGet(List::of);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AttributeDefinition> findByFieldName(UUID organizationId, UUID layerId, String fieldName) {
        return definitionsForLayer(organizationId, layerId).stream()
                .filter(d -> d.fieldName().equals(fieldName))
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> layerIdForAssetType(UUID organizationId, AssetType assetType) {
        return layerRepository.findByOrganizationIdAndAssetTypeOrderBySortOrderAsc(organizationId, assetType)
                .stream().findFirst().map(Layer::getId);
    }

    // ---- Create -------------------------------------------------------------------------------

    /**
     * Creates an attribute.
     *
     * <p>Always {@link AttributeStorage#JSONB}, never a real column, and that is the design rather
     * than a limitation. A new field lands as a key in {@code gis.assets.attributes} — the JSONB bag
     * with a GIN index that has existed since V1300 for exactly this purpose — so creating one is an
     * INSERT into a catalogue table, not DDL. The alternative, generating {@code ALTER TABLE} at
     * runtime, would mean the web tier holding DDL rights on its own schema, taking an ACCESS
     * EXCLUSIVE lock on the tenant's largest table on an administrator's click, and diverging from
     * Flyway's account of what the schema is. None of that is worth a column.
     */
    @Transactional
    public LayerAttribute create(UUID organizationId, UUID actorId, String actorName,
                                 AttributeCommands.Create command) {
        Layer layer = requireLayer(command.layerId(), organizationId);
        String fieldName = FieldNamePolicy.normaliseAndValidate(command.fieldName());

        attributeRepository.findByLayerIdAndFieldName(layer.getId(), fieldName).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.ATTRIBUTE_FIELD_NAME_TAKEN,
                    existing.isActive()
                            ? "'" + fieldName + "' already exists on " + layer.getTitle() + "."
                            : "'" + fieldName + "' exists on " + layer.getTitle() + " but is inactive. "
                              + "Reactivate it rather than creating a second field with the same name — "
                              + "recreating it would silently adopt every value already stored under it.");
        });

        if (command.dataType() == AttributeDataType.GEOMETRY) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "An asset has exactly one geometry, which every layer already declares. "
                            + "A second geometry field would have nowhere to be drawn from.");
        }

        LayerAttribute attribute = new LayerAttribute();
        attribute.setOrganizationId(organizationId);
        attribute.setLayerId(layer.getId());
        attribute.setFieldName(fieldName);
        attribute.setSystem(false);
        attribute.setStorage(AttributeStorage.JSONB);
        apply(attribute, command.displayName(), command.description(), command.dataType(),
                command.maxLength(), command.numericPrecision(), command.numericScale(),
                command.defaultValue(), command.sampleValue());
        attribute.setMandatory(command.mandatory());
        attribute.setUniqueValue(command.uniqueValue());
        attribute.setEditable(command.editable());
        attribute.setVisible(command.visible());
        attribute.setActive(command.active());
        attribute.setSortOrder(command.sortOrder() != null ? command.sortOrder() : nextSortOrder(organizationId, layer.getId()));

        LayerAttribute saved = attributeRepository.save(attribute);
        recordHistory(saved, AttributeChangeType.CREATED, null, snapshot(saved), command.changeReason(), actorId);
        audit(organizationId, actorId, actorName, AuditEventTypes.LAYER_ATTRIBUTE_CREATED, saved, layer,
                "Created attribute '" + fieldName + "' on " + layer.getTitle(), Map.of(
                        "dataType", saved.getDataType().name(),
                        "mandatory", saved.isMandatory()));
        log.info("Attribute {} created on layer {} for org {}", fieldName, layer.getCode(), organizationId);
        return saved;
    }

    // ---- Update -------------------------------------------------------------------------------

    /**
     * Updates an attribute.
     *
     * <p>Field name and data type are the two fields that reach existing data, and neither is
     * refused outright — refusing would leave an administrator whose contractor renamed a column
     * with no path but a support ticket. Both instead require {@code confirmBreakingChange}, and
     * the service answers an unconfirmed attempt with {@code ATTRIBUTE_CHANGE_REQUIRES_CONFIRMATION}
     * and a description of what will happen, so the client can raise a dialog that says something
     * specific rather than "Are you sure?".
     */
    @Transactional
    public LayerAttribute update(UUID attributeId, UUID organizationId, UUID actorId, String actorName,
                                 AttributeCommands.Update command) {
        LayerAttribute attribute = require(attributeId, organizationId);
        Layer layer = requireLayer(attribute.getLayerId(), organizationId);
        Map<String, Object> before = snapshot(attribute);

        String newFieldName = command.fieldName() == null
                ? attribute.getFieldName()
                : FieldNamePolicy.normaliseAndValidate(command.fieldName());
        AttributeDataType newDataType = command.dataType() == null
                ? attribute.getDataType()
                : command.dataType();

        boolean renaming = !newFieldName.equals(attribute.getFieldName());
        boolean retyping = newDataType != attribute.getDataType();

        if ((renaming || retyping) && attribute.isSystem()) {
            throw new BusinessException(ErrorCode.ATTRIBUTE_IS_SYSTEM,
                    "'" + attribute.getFieldName() + "' is a system attribute: the platform's own code "
                            + "reads it by name and it is backed by a real column. Its label, description, "
                            + "sample value and visibility can be changed; its name and type cannot.");
        }
        if ((renaming || retyping) && !command.confirmBreakingChange()) {
            throw new BusinessException(ErrorCode.ATTRIBUTE_CHANGE_REQUIRES_CONFIRMATION,
                    breakingChangeWarning(attribute, newFieldName, newDataType, renaming, retyping));
        }
        if (renaming) {
            attributeRepository.findByLayerIdAndFieldName(layer.getId(), newFieldName)
                    .filter(other -> !other.getId().equals(attributeId))
                    .ifPresent(other -> {
                        throw new BusinessException(ErrorCode.ATTRIBUTE_FIELD_NAME_TAKEN,
                                "'" + newFieldName + "' already exists on " + layer.getTitle() + ".");
                    });
        }

        applyEditableFields(attribute, command, layer);

        if (renaming) {
            renameStoredValues(organizationId, layer.getAssetType(), attribute.getFieldName(), newFieldName);
            attribute.setFieldName(newFieldName);
        }
        if (retyping) {
            attribute.setDataType(newDataType);
        }
        // Re-derive the type-dependent facets after any retype, so a field moved from DECIMAL to
        // TEXT does not keep a precision that now means nothing.
        normaliseTypeFacets(attribute);
        validateDefaultAndSample(attribute);

        LayerAttribute saved = attributeRepository.save(attribute);
        recordHistory(saved, AttributeChangeType.UPDATED, before, snapshot(saved),
                command.changeReason(), actorId);

        String eventType = renaming ? AuditEventTypes.LAYER_ATTRIBUTE_RENAMED
                : retyping ? AuditEventTypes.LAYER_ATTRIBUTE_RETYPED
                : AuditEventTypes.LAYER_ATTRIBUTE_UPDATED;
        audit(organizationId, actorId, actorName, eventType, saved, layer,
                "Updated attribute '" + saved.getFieldName() + "' on " + layer.getTitle(),
                Map.of("renamed", renaming, "retyped", retyping,
                        "previousFieldName", before.get("fieldName"),
                        "previousDataType", before.get("dataType")));
        return saved;
    }

    /**
     * The fields an update may always touch.
     *
     * <p>A null means "leave it alone", not "clear it" — the same convention the device provisioning
     * API uses for secrets, and for the same reason: a partial update sent by a form that only
     * rendered half the fields must not blank the half it did not show.
     *
     * <p>Mandatory and unique are frozen on system attributes. {@code name} and {@code asset_code}
     * are NOT NULL columns with a unique index behind them; letting a dropdown say otherwise would
     * produce a catalogue that disagrees with the database, and the database would win at the worst
     * possible moment — halfway through an import.
     */
    private void applyEditableFields(LayerAttribute attribute, AttributeCommands.Update command, Layer layer) {
        if (command.displayName() != null) attribute.setDisplayName(command.displayName().trim());
        if (command.description() != null) attribute.setDescription(blankToNull(command.description()));
        if (command.maxLength() != null) attribute.setMaxLength(command.maxLength());
        if (command.numericPrecision() != null) attribute.setNumericPrecision(command.numericPrecision());
        if (command.numericScale() != null) attribute.setNumericScale(command.numericScale());
        if (command.defaultValue() != null) attribute.setDefaultValue(blankToNull(command.defaultValue()));
        if (command.sampleValue() != null) attribute.setSampleValue(blankToNull(command.sampleValue()));
        if (command.editable() != null) attribute.setEditable(command.editable());
        if (command.visible() != null) attribute.setVisible(command.visible());
        if (command.sortOrder() != null) attribute.setSortOrder(command.sortOrder());

        if (command.mandatory() != null || command.uniqueValue() != null) {
            if (attribute.isSystem()) {
                throw new BusinessException(ErrorCode.ATTRIBUTE_IS_SYSTEM,
                        "'" + attribute.getFieldName() + "' on " + layer.getTitle() + " is backed by a "
                                + "database column whose NOT NULL and uniqueness are already decided. "
                                + "Changing them here would let the catalogue disagree with the table.");
            }
            if (command.mandatory() != null) attribute.setMandatory(command.mandatory());
            if (command.uniqueValue() != null) attribute.setUniqueValue(command.uniqueValue());
        }
    }

    /** The sentence the client turns into its confirmation dialog. */
    private static String breakingChangeWarning(LayerAttribute attribute, String newFieldName,
                                                AttributeDataType newDataType,
                                                boolean renaming, boolean retyping) {
        List<String> effects = new ArrayList<>();
        if (renaming) {
            effects.add("Renaming '" + attribute.getFieldName() + "' to '" + newFieldName
                    + "' rewrites the stored key on every asset of this layer, and breaks any saved "
                    + "import mapping or external report that refers to the old name.");
        }
        if (retyping) {
            effects.add("Changing the type from " + attribute.getDataType() + " to " + newDataType
                    + " does not convert values that are already stored. Rows written before the change "
                    + "keep their existing form; only new imports and edits are read as " + newDataType + ".");
        }
        return String.join(" ", effects) + " Resubmit with confirmBreakingChange to proceed.";
    }

    /**
     * Moves stored values to the new key.
     *
     * <p>One statement over the affected assets rather than a read-modify-write loop: the operation
     * is a key rename inside a JSONB document, Postgres expresses it directly, and pulling a
     * tenant's whole asset base into the JVM to do it would be both slower and a much larger window
     * for a concurrent write to be lost.
     *
     * <p>Assets that never carried the field are untouched — the {@code ?} containment test is what
     * keeps this proportional to the rows that actually have the key rather than to the layer.
     */
    private void renameStoredValues(UUID organizationId, AssetType assetType, String oldName, String newName) {
        int affected = assetRepository.renameAttributeKey(organizationId, assetType.name(), oldName, newName);
        log.info("Renamed attribute key '{}' to '{}' on {} {} assets for org {}",
                oldName, newName, affected, assetType, organizationId);
    }

    // ---- Soft delete --------------------------------------------------------------------------

    /**
     * Retires an attribute. The module's only delete, and it removes nothing.
     *
     * <p>Values already written stay in the attribute bag exactly as they were. What changes is
     * that the field stops being offered for import mapping, stops appearing in exports and stops
     * being rendered on forms. Reactivation restores all three and the historical values come back
     * with it — which is the whole reason the definition is retired rather than dropped.
     *
     * <p>System attributes cannot be retired: the platform's own code reads them, so a deactivated
     * {@code asset_code} would be a field the catalogue says is gone and the importer still writes.
     */
    @Transactional
    public LayerAttribute deactivate(UUID attributeId, UUID organizationId, UUID actorId, String actorName,
                                     String reason) {
        LayerAttribute attribute = require(attributeId, organizationId);
        if (attribute.isSystem()) {
            throw new BusinessException(ErrorCode.ATTRIBUTE_IS_SYSTEM,
                    "'" + attribute.getFieldName() + "' is a system attribute the platform reads by name. "
                            + "It can be hidden from exports and forms by clearing Visible, but it cannot "
                            + "be deactivated.");
        }
        if (!attribute.isActive()) {
            return attribute;
        }
        Layer layer = requireLayer(attribute.getLayerId(), organizationId);
        Map<String, Object> before = snapshot(attribute);
        attribute.setActive(false);
        LayerAttribute saved = attributeRepository.save(attribute);

        recordHistory(saved, AttributeChangeType.DEACTIVATED, before, snapshot(saved), reason, actorId);
        audit(organizationId, actorId, actorName, AuditEventTypes.LAYER_ATTRIBUTE_DEACTIVATED, saved, layer,
                "Deactivated attribute '" + saved.getFieldName() + "' on " + layer.getTitle()
                        + "; it will no longer be imported, exported or shown on forms",
                Map.of("reason", reason == null ? "" : reason));
        return saved;
    }

    @Transactional
    public LayerAttribute reactivate(UUID attributeId, UUID organizationId, UUID actorId, String actorName,
                                     String reason) {
        LayerAttribute attribute = require(attributeId, organizationId);
        if (attribute.isActive()) {
            return attribute;
        }
        Layer layer = requireLayer(attribute.getLayerId(), organizationId);
        Map<String, Object> before = snapshot(attribute);
        attribute.setActive(true);
        LayerAttribute saved = attributeRepository.save(attribute);

        recordHistory(saved, AttributeChangeType.REACTIVATED, before, snapshot(saved), reason, actorId);
        audit(organizationId, actorId, actorName, AuditEventTypes.LAYER_ATTRIBUTE_REACTIVATED, saved, layer,
                "Reactivated attribute '" + saved.getFieldName() + "' on " + layer.getTitle(),
                Map.of("reason", reason == null ? "" : reason));
        return saved;
    }

    // ---- Shared -------------------------------------------------------------------------------

    private void apply(LayerAttribute attribute, String displayName, String description,
                       AttributeDataType dataType, Integer maxLength, Integer precision, Integer scale,
                       String defaultValue, String sampleValue) {
        attribute.setDisplayName(displayName == null || displayName.isBlank()
                ? humanise(attribute.getFieldName())
                : displayName.trim());
        attribute.setDescription(blankToNull(description));
        attribute.setDataType(dataType);
        attribute.setMaxLength(maxLength);
        attribute.setNumericPrecision(precision);
        attribute.setNumericScale(scale);
        attribute.setDefaultValue(blankToNull(defaultValue));
        attribute.setSampleValue(blankToNull(sampleValue));
        normaliseTypeFacets(attribute);
        validateDefaultAndSample(attribute);
    }

    /**
     * Clears the facets that do not apply to the type, and supplies the defaults that do.
     *
     * <p>A precision left on a field that has become TEXT is not harmless: it is displayed in the
     * grid, exported in the field list, and read by whatever consumes the catalogue next. Storing
     * only the facets the type actually has keeps "what does this field allow" answerable from the
     * row rather than from the row plus a rule about which columns to ignore.
     *
     * <p>TEXT with no length gets 255 rather than unbounded. A text field with no declared maximum
     * accepts a pasted document, and the first anyone knows is an export that will not open.
     */
    private static void normaliseTypeFacets(LayerAttribute attribute) {
        AttributeDataType type = attribute.getDataType();
        if (type.usesLength()) {
            if (attribute.getMaxLength() == null || attribute.getMaxLength() < 1) {
                attribute.setMaxLength(255);
            }
        } else {
            attribute.setMaxLength(null);
        }
        if (type.usesPrecisionScale()) {
            if (attribute.getNumericPrecision() == null || attribute.getNumericPrecision() < 1) {
                attribute.setNumericPrecision(12);
            }
            if (attribute.getNumericScale() == null || attribute.getNumericScale() < 0) {
                attribute.setNumericScale(2);
            }
            if (attribute.getNumericScale() > attribute.getNumericPrecision()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Scale (" + attribute.getNumericScale() + ") cannot exceed precision ("
                                + attribute.getNumericPrecision() + "): NUMERIC("
                                + attribute.getNumericPrecision() + "," + attribute.getNumericScale()
                                + ") is not a narrower number, it is a type Postgres rejects.");
            }
        } else {
            attribute.setNumericPrecision(null);
            attribute.setNumericScale(null);
        }
    }

    /**
     * Proves the default and sample can survive the type they are declared under.
     *
     * <p>A default of "N/A" on a DECIMAL field is not caught until the first import that omits the
     * column, at which point a whole file fails on a value nobody typed. Checking it at definition
     * time turns that into an error message on the form that created it.
     */
    private static void validateDefaultAndSample(LayerAttribute attribute) {
        if (attribute.getDataType() == AttributeDataType.GEOMETRY) {
            return;
        }
        attribute.getDataType().coerce(attribute.getDefaultValue(), "Default value",
                attribute.getMaxLength(), attribute.getNumericPrecision(), attribute.getNumericScale());
        attribute.getDataType().coerce(attribute.getSampleValue(), "Sample value",
                attribute.getMaxLength(), attribute.getNumericPrecision(), attribute.getNumericScale());
    }

    private int nextSortOrder(UUID organizationId, UUID layerId) {
        return attributeRepository
                .findByOrganizationIdAndLayerIdOrderBySortOrderAscFieldNameAsc(organizationId, layerId)
                .stream().mapToInt(LayerAttribute::getSortOrder).max().orElse(100) + 10;
    }

    /** {@code consumer_no} → "Consumer No", so a display name is never left blank. */
    private static String humanise(String fieldName) {
        String[] parts = fieldName.split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private void recordHistory(LayerAttribute attribute, AttributeChangeType type,
                               Map<String, Object> before, Map<String, Object> after,
                               String reason, UUID actorId) {
        LayerAttributeHistory entry = new LayerAttributeHistory();
        entry.setOrganizationId(attribute.getOrganizationId());
        entry.setAttributeId(attribute.getId());
        entry.setLayerId(attribute.getLayerId());
        entry.setFieldName(attribute.getFieldName());
        entry.setChangeType(type);
        entry.setPreviousState(before);
        entry.setNewState(after);
        entry.setChangeReason(blankToNull(reason));
        entry.setChangedBy(actorId);
        historyRepository.save(entry);
    }

    /**
     * The whole definition as a map, for the history table.
     *
     * <p>{@code LinkedHashMap} rather than {@code Map.of}: the values are nullable, which
     * {@code Map.of} rejects, and the insertion order makes the stored JSON readable to a human
     * reading the history straight out of the database.
     */
    private static Map<String, Object> snapshot(LayerAttribute a) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("fieldName", a.getFieldName());
        state.put("displayName", a.getDisplayName());
        state.put("description", a.getDescription());
        state.put("dataType", a.getDataType() == null ? null : a.getDataType().name());
        state.put("maxLength", a.getMaxLength());
        state.put("numericPrecision", a.getNumericPrecision());
        state.put("numericScale", a.getNumericScale());
        state.put("defaultValue", a.getDefaultValue());
        state.put("sampleValue", a.getSampleValue());
        state.put("mandatory", a.isMandatory());
        state.put("unique", a.isUniqueValue());
        state.put("editable", a.isEditable());
        state.put("visible", a.isVisible());
        state.put("active", a.isActive());
        state.put("sortOrder", a.getSortOrder());
        return state;
    }

    private void audit(UUID organizationId, UUID actorId, String actorName, String eventType,
                       LayerAttribute attribute, Layer layer, String message,
                       Map<String, Object> metadata) {
        Map<String, Object> full = new LinkedHashMap<>(metadata);
        full.put("layerCode", layer.getCode());
        full.put("fieldName", attribute.getFieldName());
        auditService.record(AuditEvent.builder()
                .organizationId(organizationId)
                .actorUserId(actorId)
                .actorUsername(actorName)
                .eventType(eventType)
                .category(AuditCategory.CONFIGURATION)
                .severity(AuditSeverity.INFO)
                .resourceType("gis.layer_attribute_master")
                .resourceId(attribute.getId().toString())
                .success(true)
                .message(message)
                .metadata(full)
                .build());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ---- Query and view types -----------------------------------------------------------------

    /** The Data Management grid's filter set. Every field is optional; null means "do not filter". */
    public record AttributeQuery(
            UUID layerId,
            String search,
            AttributeDataType dataType,
            Boolean mandatory,
            Boolean editable,
            Boolean visible,
            Boolean active
    ) {
    }

    /** A layer as the Data Management screen lists it. */
    public record LayerSummary(
            UUID id,
            String code,
            String title,
            String description,
            AssetType assetType,
            boolean visible,
            int sortOrder,
            long activeAttributeCount
    ) {
    }

    /** Convenience for callers that want definitions keyed by field name. */
    public static Map<String, AttributeDefinition> byFieldName(List<AttributeDefinition> definitions) {
        return definitions.stream().collect(Collectors.toMap(
                AttributeDefinition::fieldName, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }
}
