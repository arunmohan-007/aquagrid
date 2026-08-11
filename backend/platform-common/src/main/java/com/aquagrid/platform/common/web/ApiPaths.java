package com.aquagrid.platform.common.web;

/** Central registry of API path prefixes, so versioning is changed in exactly one place. */
public final class ApiPaths {

    public static final String API_V1 = "/api/v1";

    public static final String AUTH = API_V1 + "/auth";
    public static final String USERS = API_V1 + "/users";
    public static final String ROLES = API_V1 + "/roles";
    public static final String ORGANIZATIONS = API_V1 + "/organizations";
    public static final String GIS = API_V1 + "/gis";
    /** Data Management — the layer and attribute catalogue every import and export reads. */
    public static final String DATA_MANAGEMENT = API_V1 + "/data-management";
    /**
     * Layer Management — the GIS layer registry.
     *
     * <p>Its own prefix rather than a branch of {@code /gis}: {@code /gis} is the map's read API,
     * cached hard and gated on {@code gis:map:view}, while this is administrative configuration
     * gated on {@code gis:layer:manage}. Nesting them would suggest that whoever may open the map
     * may also archive a layer out of it.
     */
    public static final String LAYERS = API_V1 + "/gis-layers";
    /** Layer Style Management — how each layer is drawn. */
    public static final String LAYER_STYLES = API_V1 + "/layer-styles";
    /**
     * The uploaded symbol library.
     *
     * <p>Its own prefix rather than a branch of {@code /layer-styles}: it serves image bytes under a
     * different content type and a much stricter set of response headers than any JSON endpoint, and
     * mixing the two under one path invites a future filter to be applied to the wrong half.
     */
    public static final String MAP_SYMBOLS = API_V1 + "/map-symbols";
    public static final String DEVICES = API_V1 + "/devices";
    /**
     * Device Data Configuration — the parameter catalogue that says what a device's readings mean,
     * the discovery queue for the ones nobody has described, and the raw payload archive.
     *
     * <p>Its own prefix rather than a branch of {@code /devices}: it is a different resource with a
     * different permission, and nesting it under the register would suggest that whoever may
     * register a device may also redefine what its readings mean.
     */
    public static final String DEVICE_DATA_CONFIG = API_V1 + "/device-data-config";
    public static final String ASSETS = API_V1 + "/assets";
    public static final String ALARMS = API_V1 + "/alarms";

    public static final String WELL_KNOWN = "/.well-known";

    private ApiPaths() {
    }
}
