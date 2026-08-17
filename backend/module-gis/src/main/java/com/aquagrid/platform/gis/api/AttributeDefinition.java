package com.aquagrid.platform.gis.api;

import com.aquagrid.platform.gis.domain.enums.AttributeDataType;
import com.aquagrid.platform.gis.domain.enums.AttributeStorage;
import com.aquagrid.platform.gis.domain.model.AttributeValidationRule;
import com.aquagrid.platform.gis.domain.model.LayerAttribute;

import java.util.List;
import java.util.UUID;

/**
 * One attribute, as everything downstream of the catalogue sees it.
 *
 * <p>The published shape, separate from the {@link LayerAttribute} entity on purpose. Import,
 * export and the dynamic forms to come read field definitions constantly and must not be able to
 * mutate one by holding it: an entity handed out of a transaction is a lazy-loading, dirty-checking
 * object that will eventually be modified by a caller who did not mean to write to the database.
 * This is an immutable snapshot with the rules already resolved, safe to cache and safe to hand to
 * a module that is not this one.
 *
 * @param rules the active validation rules, pre-loaded. An importer evaluates rules per value per
 *              row; fetching them lazily inside that loop is the N+1 that turns a 50k-row file into
 *              hundreds of thousands of queries.
 */
public record AttributeDefinition(
        UUID id,
        UUID layerId,
        String fieldName,
        String displayName,
        String description,
        AttributeDataType dataType,
        Integer maxLength,
        Integer numericPrecision,
        Integer numericScale,
        String defaultValue,
        String sampleValue,
        boolean mandatory,
        boolean uniqueValue,
        boolean duplicateCheck,
        boolean editable,
        boolean visible,
        boolean system,
        AttributeStorage storage,
        String storageTable,
        String storageTarget,
        int sortOrder,
        List<ValidationRule> rules
) {

    public static AttributeDefinition from(LayerAttribute a, List<AttributeValidationRule> rules) {
        return new AttributeDefinition(
                a.getId(), a.getLayerId(), a.getFieldName(), a.getDisplayName(), a.getDescription(),
                a.getDataType(), a.getMaxLength(), a.getNumericPrecision(), a.getNumericScale(),
                a.getDefaultValue(), a.getSampleValue(), a.isMandatory(), a.isUniqueValue(),
                a.isDuplicateCheck(), a.isEditable(), a.isVisible(), a.isSystem(), a.getStorage(),
                a.getStorageTable(), a.getStorageTarget(), a.getSortOrder(),
                rules.stream().map(ValidationRule::from).toList());
    }

    /** The key this attribute's value is read and written under. See {@link LayerAttribute#resolvedTarget()}. */
    public String resolvedTarget() {
        return storageTarget != null ? storageTarget : fieldName;
    }

    /** Whether the importer can fill this attribute from a mapped source column. */
    public boolean importable() {
        return storage.isImportable();
    }

    /** One validation rule, flattened. */
    public record ValidationRule(
            com.aquagrid.platform.gis.domain.enums.ValidationRuleType type,
            String value,
            String errorMessage
    ) {
        static ValidationRule from(AttributeValidationRule r) {
            return new ValidationRule(r.getRuleType(), r.getRuleValue(), r.getErrorMessage());
        }
    }
}
