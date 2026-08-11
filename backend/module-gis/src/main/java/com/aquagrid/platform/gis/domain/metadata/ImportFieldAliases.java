package com.aquagrid.platform.gis.domain.metadata;

import com.aquagrid.platform.gis.api.AttributeDefinition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Column headings that should auto-match a field in the import mapper.
 *
 * <p>This used to be a dictionary in the client, alongside a client-side copy of the field list.
 * Both moved here when the catalogue became the source of truth, and the move is what makes
 * auto-mapping work for fields the client has never heard of: an attribute created this morning
 * gets its own field name and display name as aliases automatically, so a source column called
 * "Consumer No" matches {@code consumer_no} without anyone teaching the client about it.
 *
 * <p>The hand-written entries are only for the seeded system fields, where the platform's name and
 * the industry's name genuinely differ — a survey deliverable says {@code Shape_Length}, not
 * {@code digital_length}, and no amount of deriving gets from one to the other.
 *
 * <p>Auto-mapping suggests; it never decides. Every match stays visible and changeable in the
 * mapper, which is the difference between a helpful default and the silent mis-mapping the
 * two-phase import exists to prevent.
 */
public final class ImportFieldAliases {

    private static final Map<String, List<String>> CURATED = Map.ofEntries(
            Map.entry("asset_code", List.of("code", "id", "assetid", "asset_id", "meter_no",
                    "meterno", "number")),
            Map.entry("name", List.of("title", "label", "description")),
            Map.entry("status", List.of("state", "condition")),
            Map.entry("install_date", List.of("installdate", "commissioned", "date_commissioned",
                    "installed")),
            Map.entry("decommission_date", List.of("decommissioned", "removal_date", "date_removed")),
            Map.entry("lon", List.of("lng", "long", "longitude", "x", "easting")),
            Map.entry("lat", List.of("latitude", "y", "northing")),
            Map.entry("slno", List.of("sl_no", "sl", "sno", "s_no", "serial", "serial_no", "serialno")),
            /*
             * "Asset no" must not fall through to asset_code: that column is the platform's unique
             * key, and a register's repeating numbering would collide across panchayats on import.
             */
            Map.entry("asset_number", List.of("assetnumber", "asset_no", "assetno", "asset_num",
                    "structure_no", "well_no", "tank_no")),
            Map.entry("asset_type", List.of("assettype", "type", "structure_type", "category",
                    "well_type")),
            Map.entry("diameter", List.of("dia", "dia_mm", "diameter_mm", "pipe_dia", "size")),
            /*
             * Shape_Length is what ArcGIS writes into a digitised line layer, so it is the likeliest
             * spelling of "digital length" a contractor's shapefile actually carries.
             */
            Map.entry("digital_length", List.of("digitallength", "dig_length", "shape_length",
                    "shape_leng", "length", "len", "length_m")),
            Map.entry("panchayat", List.of("gram_panchayat", "grampanchayat", "gp", "gp_name",
                    "local_body")),
            Map.entry("start_date", List.of("startdate", "date_start", "work_start", "commencement")));

    private ImportFieldAliases() {
    }

    /**
     * Every heading that should match this attribute, most specific first.
     *
     * <p>The field name and the display name are always included, so an administrator who names a
     * field well gets auto-mapping for free.
     */
    public static List<String> forAttribute(AttributeDefinition attribute) {
        Set<String> aliases = new LinkedHashSet<>();
        aliases.add(attribute.fieldName());
        if (attribute.displayName() != null) {
            aliases.add(normalise(attribute.displayName()));
        }
        aliases.addAll(CURATED.getOrDefault(attribute.fieldName(), List.of()));
        return new ArrayList<>(aliases);
    }

    /** Reduces a heading to the form aliases are compared in: letters and digits, lower case. */
    public static String normalise(String heading) {
        return heading == null ? "" : heading.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
