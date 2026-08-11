package com.aquagrid.platform.iot.dataconfig.application.service;

import com.aquagrid.platform.iot.dataconfig.api.DeviceParameterApi;
import com.aquagrid.platform.iot.dataconfig.api.ParameterDefinition;
import com.aquagrid.platform.iot.dataconfig.domain.model.DeviceDataParameter;
import com.aquagrid.platform.iot.dataconfig.infrastructure.persistence.DeviceDataParameterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a device's effective parameter configuration, and caches it.
 *
 * <p>Two jobs, and they belong together because the second only makes sense given the first.
 *
 * <h2>Resolution</h2>
 *
 * <p>The device type's template, then the device's own rows of the same name replacing them
 * <em>entire</em>. Not merged field by field: a partial merge would leave an operator unable to say
 * what a device's configuration actually is by reading either row, since the answer would be a
 * function of both and of a precedence rule written in Java. Replacing whole definitions means the
 * row you are looking at is the row that applies.
 *
 * <p>Keyed by payload key rather than canonical name because callers arrive holding a payload. A
 * parameter whose vendor spelling is {@code totalVolume} and whose canonical name is {@code volume}
 * is found under {@code totalVolume}, which is the string the packet contains.
 *
 * <h2>Caching</h2>
 *
 * <p>This is read once per packet. A thousand-meter fleet on a five-minute duty cycle is two hundred
 * resolutions a minute at rest and far more during a step, each of which would otherwise be two
 * indexed queries — so the resolution is held per tenant and invalidated by the writer.
 *
 * <p><b>Invalidated eagerly, never expired on a timer.</b> A TTL would leave an administrator who
 * has just corrected a unit watching readings arrive in the old one for however long the TTL was,
 * with nothing on screen to explain it. Every write path in {@code DeviceDataConfigService} calls
 * {@link #invalidate}, and the cache is per tenant rather than per device so that a change to a
 * device-type template — which affects devices the writer never named — cannot leave a stale entry
 * behind.
 *
 * <p>The map is a {@link ConcurrentHashMap} of immutable values. Contexts run concurrently on
 * per-connection virtual threads, and a resolution handed out while another thread invalidates is
 * simply the previous answer, which was correct a moment ago — the guarantee needed here is that no
 * reader sees a half-built map, not that no reader sees a slightly old one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParameterResolver implements DeviceParameterApi {

    private final DeviceDataParameterRepository repository;

    /** tenant → (device type or device id) → payload key → definition. */
    private final Map<UUID, Map<String, Map<String, ParameterDefinition>>> cache =
            new ConcurrentHashMap<>();

    @Override
    @Transactional(readOnly = true)
    public Map<String, ParameterDefinition> effectiveForDevice(UUID organizationId, UUID deviceId,
                                                               String deviceType) {
        if (organizationId == null || deviceId == null) {
            return Map.of();
        }
        return tenantCache(organizationId).computeIfAbsent("d:" + deviceId,
                key -> resolveForDevice(organizationId, deviceId, deviceType));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, ParameterDefinition> effectiveForDeviceType(UUID organizationId, String deviceType) {
        if (organizationId == null || deviceType == null || deviceType.isBlank()) {
            return Map.of();
        }
        return tenantCache(organizationId).computeIfAbsent("t:" + deviceType,
                key -> index(repository.findActiveTemplate(organizationId, deviceType)));
    }

    @Override
    public void invalidate(UUID organizationId) {
        if (organizationId == null) {
            cache.clear();
            return;
        }
        cache.remove(organizationId);
    }

    private Map<String, Map<String, ParameterDefinition>> tenantCache(UUID organizationId) {
        return cache.computeIfAbsent(organizationId, key -> new ConcurrentHashMap<>());
    }

    private Map<String, ParameterDefinition> resolveForDevice(UUID organizationId, UUID deviceId,
                                                              String deviceType) {
        Map<String, ParameterDefinition> effective = new LinkedHashMap<>();
        if (deviceType != null && !deviceType.isBlank()) {
            effective.putAll(index(repository.findActiveTemplate(organizationId, deviceType)));
        }
        /*
         * Overrides are keyed by canonical name, not by payload key, when deciding what they
         * replace. A device override that also renames the vendor spelling — the template matches
         * `totalVolume`, this one device's firmware sends `total_volume` — must replace the template
         * entry rather than sit beside it, or the device would carry two definitions of `volume`
         * and the payload would match whichever the map happened to be walked into first.
         */
        for (DeviceDataParameter override : repository.findActiveOverrides(organizationId, deviceId)) {
            ParameterDefinition definition = ParameterDefinition.from(override);
            effective.entrySet().removeIf(entry ->
                    entry.getValue().parameterName().equals(definition.parameterName()));
            effective.put(definition.payloadKey(), definition);
        }
        return Map.copyOf(effective);
    }

    /**
     * Indexes definitions by the key they are matched by in a payload.
     *
     * <p>Two parameters claiming the same payload key is a configuration mistake the unique index
     * cannot catch — the index is on {@code parameter_name}, and two differently-named parameters
     * may both declare {@code payload_key = 'temp'}. The later one wins and the collision is logged
     * rather than thrown: this runs on the reception path, and refusing to resolve a fleet's
     * configuration because one parameter is misconfigured would stop telemetry the other
     * parameters describe perfectly well.
     */
    private static Map<String, ParameterDefinition> index(List<DeviceDataParameter> parameters) {
        Map<String, ParameterDefinition> byKey = new LinkedHashMap<>();
        for (DeviceDataParameter parameter : parameters) {
            ParameterDefinition definition = ParameterDefinition.from(parameter);
            ParameterDefinition clash = byKey.put(definition.payloadKey(), definition);
            if (clash != null) {
                log.warn("Parameters '{}' and '{}' both claim payload key '{}'; '{}' wins. "
                                + "Give one of them a distinct source key.",
                        clash.parameterName(), definition.parameterName(), definition.payloadKey(),
                        definition.parameterName());
            }
        }
        return Map.copyOf(byKey);
    }
}
