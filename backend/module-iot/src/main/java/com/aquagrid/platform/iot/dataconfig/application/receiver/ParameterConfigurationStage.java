package com.aquagrid.platform.iot.dataconfig.application.receiver;

import com.aquagrid.platform.iot.dataconfig.api.DeviceParameterApi;
import com.aquagrid.platform.iot.dataconfig.api.ParameterDefinition;
import com.aquagrid.platform.iot.dataconfig.application.service.ParameterDiscoveryService;
import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.receiver.domain.model.ParsedTelemetry;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.spi.ReceiverStage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Notices the parameters a packet carried that the catalogue does not describe.
 *
 * <p><b>This stage never halts.</b> It has no rejection path at all, and that is the module's
 * central rule expressed as code: a device sending a field nobody catalogued is normal operation —
 * the commonest cause is a firmware update that added a sensor — and refusing the packet would
 * discard measurements that cannot be re-requested in order to enforce a table an administrator has
 * simply not filled in yet. Configuration decides how data is used, never whether it is allowed in.
 *
 * <h2>Why it reads the payload again rather than the parsed metrics</h2>
 *
 * <p>{@code ParsedTelemetry.metrics} is a {@code Map<String, Double>} of <em>canonicalised</em>
 * names, and both halves of that are lossy for this purpose. The parsers collect only numbers and
 * booleans, so a string status, a nested object or an array produces no entry — and those are
 * exactly the fields a pump or a controller sends that a water meter does not.
 * {@code MetricVocabulary} then rewrites the vendor's spelling, so {@code totalVolume} is already
 * {@code volume} by the time a stage sees it, and an operator looking for {@code totalVolume} in
 * their vendor's documentation would find nothing on the discovery screen.
 *
 * <p>So the payload is walked again here, in its original form. It is one parse of a body already in
 * memory, on a path that is about to do a database write regardless — a cost worth paying to keep
 * the discovery list in the vendor's own words.
 *
 * <p>Validation itself is deliberately <em>not</em> here. Quality is stamped in
 * {@code TelemetryIngestService}, which is where the reading row is written and which also serves
 * the older adapter path that does not go through this pipeline. Judging twice would risk the two
 * verdicts disagreeing; judging where the row is written cannot.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParameterConfigurationStage implements ReceiverStage {

    /** Where vendors nest readings — the same places {@code JsonTelemetryParser} looks. */
    private static final String[] CONTAINERS = {"data", "object", "measurements", "payload",
            "readings", "values"};

    private final DeviceParameterApi parameterApi;
    private final ParameterDiscoveryService discoveryService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "PARAMETER_CONFIG";
    }

    @Override
    public Decision execute(ReceptionContext context) {
        Device device = context.getDevice();
        ParsedTelemetry telemetry = context.getTelemetry();

        Map<String, ParameterDefinition> configured = parameterApi.effectiveForDevice(
                device.getOrganizationId(), device.getId(), device.getDeviceType());

        Map<String, Object> observed = observedFields(context, telemetry);
        Set<String> configuredKeys = configured.keySet();

        discoveryService.record(device.getOrganizationId(), device.getId(), device.getDeviceCode(),
                device.getDeviceType(), configuredKeys, observed,
                telemetry == null ? context.getPacket().receivedAt() : telemetry.observedAt());

        // Recorded on the packet log and the telemetry event's metadata, so an operator can see from
        // one packet that a device is sending more than the platform is configured to interpret —
        // without having to go and compare two screens.
        long undescribed = observed.keySet().stream()
                .filter(key -> !configuredKeys.contains(key))
                .count();
        context.note("configuredParameters", configuredKeys.size());
        context.note("undescribedFields", undescribed);

        return Decision.CONTINUE;
    }

    /**
     * Every scalar field the payload carried, by its verbatim key.
     *
     * <p>Falls back to the canonicalised metric names for payloads that are not JSON — a LoRaWAN
     * frame or a raw meter binary has no keys of its own, and what the codec decoded is the only
     * account of its contents there is. The bytes themselves are preserved either way, by
     * {@code RawPayloadRetentionObserver}.
     */
    private Map<String, Object> observedFields(ReceptionContext context, ParsedTelemetry telemetry) {
        Map<String, Object> fields = new LinkedHashMap<>();
        JsonNode root = readTree(context);
        if (root != null) {
            collect(root, fields);
            for (String container : CONTAINERS) {
                JsonNode nested = root.path(container);
                if (nested.isObject()) {
                    collect(nested, fields);
                }
            }
        }
        if (fields.isEmpty() && telemetry != null) {
            fields.putAll(telemetry.metrics());
        }
        return fields;
    }

    /**
     * Pulls one object level's fields out.
     *
     * <p>Unlike the parser's equivalent, objects and arrays are kept rather than skipped: the parser
     * skips them because it can only produce numbers, and this exists precisely to notice the fields
     * that are not numbers. They are recorded by name with their type detected as {@code JSON} or
     * {@code ARRAY}, which is enough for an administrator to decide whether they want it configured.
     *
     * <p>Nested container objects are excluded, because the caller descends into them separately and
     * a payload with a {@code data} wrapper would otherwise report {@code data} itself as an
     * undescribed parameter on every single uplink.
     */
    private static void collect(JsonNode node, Map<String, Object> fields) {
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isNull()) {
                return;
            }
            if (value.isNumber()) {
                fields.put(entry.getKey(), value.numberValue());
            } else if (value.isBoolean()) {
                fields.put(entry.getKey(), value.booleanValue());
            } else if (value.isTextual()) {
                fields.put(entry.getKey(), value.textValue());
            } else if (value.isArray()) {
                fields.put(entry.getKey(), value);
            } else if (value.isObject() && !isContainer(entry.getKey())) {
                fields.put(entry.getKey(), value);
            }
        });
    }

    private static boolean isContainer(String key) {
        for (String container : CONTAINERS) {
            if (container.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private JsonNode readTree(ReceptionContext context) {
        try {
            JsonNode node = objectMapper.readTree(context.getPacket().payload());
            return node != null && node.isObject() ? node : null;
        } catch (Exception notJson) {
            // Expected for every binary transport; the caller falls back to the decoded metrics.
            return null;
        }
    }

    @Override
    public int getOrder() {
        return Stages.PARAMETER_CONFIGURATION;
    }
}
