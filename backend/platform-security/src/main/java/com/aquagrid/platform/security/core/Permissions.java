package com.aquagrid.platform.security.core;

/**
 * The platform's permission vocabulary, as compile-time constants.
 *
 * <p>Authorisation is permission-based rather than role-based. {@code @PreAuthorize} never names a
 * role, because roles are customer-editable data — a utility that invents a "Zone Supervisor" role
 * must be able to grant it the right capabilities without a code change. Roles are bundles of
 * these permissions.
 *
 * <p>Format is {@code domain:resource:action}. Each module contributes its own block here and the
 * matching rows in {@code identity.permissions} via its migration.
 */
public final class Permissions {

    // ---- Identity (Modules 1-2) ----------------------------------------------------------------
    public static final String USER_READ = "identity:user:read";
    public static final String USER_CREATE = "identity:user:create";
    public static final String USER_UPDATE = "identity:user:update";
    public static final String USER_DELETE = "identity:user:delete";
    public static final String USER_IMPERSONATE = "identity:user:impersonate";
    public static final String USER_SESSION_REVOKE = "identity:user:session-revoke";
    public static final String ROLE_READ = "identity:role:read";
    public static final String ROLE_MANAGE = "identity:role:manage";

    // ---- Organization (Module 3) ---------------------------------------------------------------
    public static final String ORGANIZATION_READ = "org:organization:read";
    public static final String ORGANIZATION_MANAGE = "org:organization:manage";

    // ---- GIS & assets (Modules 4, 11, 12, 23) --------------------------------------------------
    public static final String MAP_VIEW = "gis:map:view";
    public static final String ASSET_READ = "gis:asset:read";
    public static final String ASSET_CREATE = "gis:asset:create";
    public static final String ASSET_UPDATE = "gis:asset:update";
    public static final String ASSET_DELETE = "gis:asset:delete";
    public static final String NETWORK_TRACE = "gis:network:trace";
    /**
     * The attribute catalogue (Data Management). Read is the field list; manage changes what
     * every import and export reads, which is a schema-shaped change made at runtime — hence the
     * split, and hence manage reaching only the administrator roles by default.
     */
    public static final String METADATA_READ = "gis:metadata:read";
    public static final String METADATA_MANAGE = "gis:metadata:manage";
    /**
     * The layer registry (Layer Management). Separate from the attribute catalogue above because
     * they answer different questions — which layers the product contains, versus what each layer
     * holds — and archiving a layer withdraws it from the map, the import hub and every export for
     * the whole tenant at once.
     */
    public static final String LAYER_READ = "gis:layer:read";
    public static final String LAYER_MANAGE = "gis:layer:manage";
    /**
     * Layer Style Management. Split from {@link #LAYER_MANAGE} because a cartographer who should be
     * able to recolour the network and label the tanks has no business retiring the pipeline layer.
     * {@link #STYLE_READ} is granted alongside {@link #MAP_VIEW} and must stay at least as wide: the
     * map fetches its MapLibre layer specifications from the style API, so a narrower grant would
     * give an operator correct geometry with every layer drawn grey.
     */
    public static final String STYLE_READ = "gis:style:read";
    public static final String STYLE_MANAGE = "gis:style:manage";

    // ---- IoT (Modules 6, 7, 17, 18) ------------------------------------------------------------
    public static final String DEVICE_READ = "iot:device:read";
    public static final String DEVICE_MANAGE = "iot:device:manage";
    public static final String DEVICE_COMMAND = "iot:device:command";
    public static final String DEVICE_FIRMWARE = "iot:device:firmware";
    public static final String SIMULATOR_RUN = "iot:simulator:run";
    public static final String RECEIVER_READ = "iot:receiver:read";
    public static final String RECEIVER_MANAGE = "iot:receiver:manage";
    /**
     * Re-injecting a stored packet writes telemetry that was never re-sent by the device, so it is
     * separated from {@link #RECEIVER_MANAGE}: an operator who may restart a listener is not
     * thereby entitled to author readings.
     */
    public static final String RECEIVER_REPLAY = "iot:receiver:replay";
    /**
     * Device Data Configuration — the parameter catalogue that says what a device's readings
     * <em>mean</em>. Separate from {@link #DEVICE_MANAGE} because registering hardware and defining
     * the semantics of everything it reports are different jobs: a commissioning engineer should
     * not thereby be able to redefine "volume" for the whole fleet, and a data steward has no
     * business provisioning radio credentials.
     */
    public static final String DATA_CONFIG_READ = "iot:data-config:read";
    public static final String DATA_CONFIG_MANAGE = "iot:data-config:manage";

    // ---- Operations (Modules 19-22) ------------------------------------------------------------
    public static final String ALARM_READ = "ops:alarm:read";
    public static final String ALARM_ACKNOWLEDGE = "ops:alarm:acknowledge";
    public static final String WORK_ORDER_READ = "ops:work-order:read";
    public static final String WORK_ORDER_MANAGE = "ops:work-order:manage";

    // ---- Analytics & reporting (Modules 24-29) -------------------------------------------------
    public static final String ANALYTICS_VIEW = "analytics:dashboard:view";
    public static final String NRW_VIEW = "analytics:nrw:view";
    public static final String REPORT_GENERATE = "report:report:generate";

    // ---- Administration (Modules 30-34) --------------------------------------------------------
    public static final String AUDIT_READ = "admin:audit:read";
    public static final String SYSTEM_MONITOR = "admin:system:monitor";
    public static final String BACKUP_MANAGE = "admin:backup:manage";
    public static final String SETTINGS_MANAGE = "admin:settings:manage";

    private Permissions() {
    }
}
