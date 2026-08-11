package com.aquagrid.platform.iot.dataconfig.application.command;

import com.aquagrid.platform.iot.dataconfig.domain.model.ParameterDataType;
import com.aquagrid.platform.iot.dataconfig.domain.model.ParameterScope;

import java.util.UUID;

/**
 * What the configuration screen asks the service to do.
 *
 * <p>Commands rather than the entity, so the web layer never hands the service a managed object and
 * the service never has to work out which of an entity's fields the caller meant to change.
 */
public final class ParameterCommands {

    private ParameterCommands() {
    }

    /**
     * Create a parameter.
     *
     * <p>{@code scope} decides which of {@code deviceType} and {@code deviceId} is required; the
     * service checks it rather than trusting the client, because the CHECK constraint in V1405
     * would otherwise produce a constraint-violation stack trace instead of a sentence.
     *
     * @param discoveredParameterId the discovery row this was raised from, if any. Set when the
     *                              administrator clicked Configure on the Discovered Parameters
     *                              screen, so the queue can close that row and everything else
     *                              waiting on the same name
     */
    public record Create(
            ParameterScope scope,
            String deviceType,
            UUID deviceId,
            String parameterName,
            String displayName,
            String description,
            ParameterDataType dataType,
            String unit,
            String category,
            String payloadKey,
            boolean mandatory,
            boolean dashboardVisible,
            boolean useForAlarm,
            boolean useForReports,
            Double minValue,
            Double maxValue,
            Integer decimalPrecision,
            String sampleValue,
            String defaultValue,
            boolean active,
            Integer sortOrder,
            String changeReason,
            UUID discoveredParameterId
    ) {
    }

    /**
     * Update a parameter.
     *
     * <p>Every field is nullable and <b>null means "leave it alone", never "clear it"</b> — the same
     * convention the device provisioning API uses for secrets and the attribute catalogue uses for
     * its labels, and for the same reason: a partial update sent by a form that only rendered half
     * the fields must not blank the half it did not show.
     *
     * <p>The one consequence worth knowing is that a range cannot be removed by omission. Clearing a
     * bound is done by sending {@code Double.NaN}, which the service reads as "unbounded" — an
     * explicit signal, because "I did not send a maximum" and "this parameter has no maximum" are
     * different statements and a nullable field cannot carry both.
     *
     * @param confirmBreakingChange required to retype a parameter or change the key it matches,
     *                              since both reach readings that already exist
     */
    public record Update(
            String displayName,
            String description,
            ParameterDataType dataType,
            String unit,
            String category,
            String payloadKey,
            Boolean mandatory,
            Boolean dashboardVisible,
            Boolean useForAlarm,
            Boolean useForReports,
            Double minValue,
            Double maxValue,
            Integer decimalPrecision,
            String sampleValue,
            String defaultValue,
            Integer sortOrder,
            String changeReason,
            boolean confirmBreakingChange
    ) {
    }
}
