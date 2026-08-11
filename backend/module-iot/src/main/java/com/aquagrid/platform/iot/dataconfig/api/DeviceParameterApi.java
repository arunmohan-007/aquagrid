package com.aquagrid.platform.iot.dataconfig.api;

import java.util.Map;
import java.util.UUID;

/**
 * What a device's readings mean — the module's published read side.
 *
 * <p>Every consumer of the parameter catalogue goes through this interface: the ingestion service
 * that stamps a unit and a quality on each reading, the receiver stage that notices undescribed
 * fields, the simulator deciding what a virtual device should emit, and the dashboards, alarms and
 * reports that follow. None of them touches the repositories or the entity, for the reason the
 * platform states for {@code LayerMetadataApi}: a consumer handed an entity gets a dirty-checked
 * object it can modify by accident, and this one is read from the reception path on more than one
 * thread.
 *
 * <p>The contract has one clause worth stating in the interface rather than the implementation:
 * <b>nothing returned here may be used to reject a packet.</b> A device sending a field nobody
 * catalogued is normal operation — the commonest cause is a firmware update that added a sensor —
 * and refusing the packet would discard measurements that cannot be re-requested in order to enforce
 * a table an administrator has not filled in yet. Configuration decides how data is used, never
 * whether it is allowed in.
 */
public interface DeviceParameterApi {

    /**
     * Every parameter a device is configured to send, keyed by the payload key it is matched by.
     *
     * <p>The device type's template first, then the device's own overrides replacing entries of the
     * same name — see {@code ParameterScope}. Keyed by payload key rather than by canonical name
     * because the caller has a payload in front of it and needs the lookup to go that way; the
     * canonical name is on the definition.
     *
     * <p>Empty is a normal, common answer. A tenant that has configured nothing gets every reading
     * stored with quality {@code UNKNOWN} and every field listed for discovery, which is the
     * intended starting state rather than a degraded one.
     */
    Map<String, ParameterDefinition> effectiveForDevice(UUID organizationId, UUID deviceId,
                                                        String deviceType);

    /**
     * A device type's template alone, keyed by payload key.
     *
     * <p>Used where there is no device — the configuration screen showing what a type declares, and
     * the simulator generating a fleet's worth of plausible payloads before any of them exists.
     */
    Map<String, ParameterDefinition> effectiveForDeviceType(UUID organizationId, String deviceType);

    /**
     * Discards any cached resolution for a tenant.
     *
     * <p>Called by the writer whenever a definition changes. The cache exists because the resolution
     * is read once per packet and a fleet is thousands of packets a minute; it is invalidated
     * eagerly because the alternative — a TTL — would leave an administrator who has just corrected
     * a unit watching readings arrive in the old one for however long the TTL was, with nothing on
     * screen to explain it.
     */
    void invalidate(UUID organizationId);
}
