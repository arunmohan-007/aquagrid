package com.aquagrid.platform.gis.application.command;

import com.aquagrid.platform.gis.domain.enums.AttributeDataType;

import java.util.UUID;

/**
 * Inbound commands for the attribute catalogue.
 *
 * <p>Separate from the web DTOs so the service is callable from anywhere — a future dynamic-form
 * designer, a tenant provisioning routine, a test — without going through Jackson and bean
 * validation. The controller maps its request records onto these.
 */
public final class AttributeCommands {

    private AttributeCommands() {
    }

    /**
     * Create one attribute.
     *
     * <p>There is no {@code storage} field. Everything created here is a key in the asset's JSONB
     * attribute bag; nothing an administrator does at runtime produces a column. Letting the caller
     * choose would be offering a setting whose only other value requires DDL rights the application
     * deliberately does not hold.
     */
    public record Create(
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
            boolean editable,
            boolean visible,
            boolean active,
            Integer sortOrder,
            String changeReason
    ) {
    }

    /**
     * Update one attribute.
     *
     * <p>Every field is nullable and null means "leave it alone", never "clear it". A form that
     * renders half the fields must not blank the half it did not show — the same convention the
     * device provisioning API uses for secrets, for the same reason.
     *
     * @param confirmBreakingChange the operator has seen and accepted what a rename or a retype
     *                              does to existing data. Without it the service answers
     *                              {@code ATTRIBUTE_CHANGE_REQUIRES_CONFIRMATION} and describes the
     *                              effect, so the client can raise a specific dialog rather than a
     *                              generic "Are you sure?".
     */
    public record Update(
            String fieldName,
            String displayName,
            String description,
            AttributeDataType dataType,
            Integer maxLength,
            Integer numericPrecision,
            Integer numericScale,
            String defaultValue,
            String sampleValue,
            Boolean mandatory,
            Boolean uniqueValue,
            Boolean editable,
            Boolean visible,
            Integer sortOrder,
            boolean confirmBreakingChange,
            String changeReason
    ) {
    }
}
