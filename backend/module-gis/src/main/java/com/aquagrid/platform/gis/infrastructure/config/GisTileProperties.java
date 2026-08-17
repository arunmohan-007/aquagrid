package com.aquagrid.platform.gis.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Vector tile generation and caching knobs.
 *
 * <p>These were constants inside the tile query and the controller. They are configuration now
 * because the right value for each depends on the deployment's data rather than on the code: a
 * utility whose network is mostly long transmission mains wants a wider buffer than one mapping
 * house connections, and a demo instance being edited live wants a shorter cache than a production
 * one whose assets change monthly.
 *
 * @param extent the tile's coordinate grid, passed to {@code ST_AsMVTGeom} and declared to
 *               {@code ST_AsMVT}. 4096 is the MVT specification's default and what every client
 *               assumes when a layer omits it; lowering it quantises geometry more coarsely for a
 *               modest byte saving. The two calls must be given the same number — a mismatch
 *               produces geometry scaled wrongly inside the tile, which draws as a layer offset
 *               from the base map by a fraction of a tile and looks like a projection bug.
 * @param buffer how far outside the tile envelope, in tile units, geometry is kept before clipping.
 *               A feature crossing a tile edge is cut by {@code ST_AsMVTGeom}; without a margin the
 *               renderer has nothing to join the two halves with and a pipe shows a hairline gap at
 *               every boundary, a polygon outline breaks, and a label anchored near the edge is
 *               dropped from both tiles because its anchor fell outside each. 8 at extent 4096 is
 *               roughly two screen pixels at typical zoom, which covers stroke width and casing;
 *               64 is the value to reach for if labels on wide polygons still drop out. It costs
 *               bytes — the margin's geometry is duplicated into every neighbouring tile — so it is
 *               a dial, not a "set it high" setting.
 * @param dynamicCacheMaxAge how long a tile from an <em>editable</em> layer may be reused. Short,
 *               because these are the operational layers whose features staff move and retire
 *               during a shift, and a tile cached for an hour is an hour of an operator seeing a
 *               valve they know they deleted.
 * @param staticCacheMaxAge how long a tile from a non-editable layer may be reused. Layer
 *               Management's {@code editable} flag is the existing declaration of exactly this
 *               distinction — a boundary set or an imported reference layer is marked not editable
 *               precisely because it does not change — so the cache reads that rather than
 *               introducing a second flag saying the same thing in different words.
 * @param queryTimeout how long a single tile query may run before Postgres cancels it. Import puts
 *               no ceiling on a feature's vertex count or validity — a self-intersecting ring or a
 *               shapefile-conversion artefact with tens of thousands of points reaches {@code
 *               ST_AsMVTGeom} exactly like any other geometry. Without this, one such feature does
 *               not error: it runs, ties up a database connection indefinitely, and every other tile
 *               request — for this tenant and any other sharing the pool — queues behind it. A
 *               bounded failure the operator can report is the difference between that and a map
 *               that silently never finishes loading for everyone.
 */
@Validated
@ConfigurationProperties(prefix = "aquagrid.gis.tile")
public record GisTileProperties(
        Integer extent,
        Integer buffer,
        Duration dynamicCacheMaxAge,
        Duration staticCacheMaxAge,
        Duration queryTimeout
) {

    /**
     * Defaults, applied per-field so a deployment can override one without restating the rest.
     *
     * <p>A record with a compact constructor rather than defaults in {@code application.yml}: the
     * yml is one profile's worth of overrides, and a value that only exists there is absent in
     * every test that builds the properties directly — which is how a test passes against
     * {@code buffer = null} and production runs against 8.
     */
    public GisTileProperties {
        extent = extent == null ? 4096 : extent;
        buffer = buffer == null ? 8 : buffer;
        dynamicCacheMaxAge = dynamicCacheMaxAge == null ? Duration.ofMinutes(5) : dynamicCacheMaxAge;
        staticCacheMaxAge = staticCacheMaxAge == null ? Duration.ofHours(6) : staticCacheMaxAge;
        queryTimeout = queryTimeout == null ? Duration.ofSeconds(8) : queryTimeout;

        /*
         * Refused at boot rather than clamped. A buffer wider than the tile itself, or a
         * non-positive extent, produces tiles that are silently wrong — geometry mis-scaled, or
         * every feature in the layer duplicated into every tile — and a deployment that starts and
         * draws a subtly broken map is harder to diagnose than one that will not start.
         */
        if (extent < 256 || extent > 16384) {
            throw new IllegalArgumentException(
                    "aquagrid.gis.tile.extent must be between 256 and 16384, was " + extent);
        }
        if (buffer < 0 || buffer > extent / 4) {
            throw new IllegalArgumentException(
                    "aquagrid.gis.tile.buffer must be between 0 and " + (extent / 4)
                            + " at extent " + extent + ", was " + buffer);
        }
        if (queryTimeout.isNegative() || queryTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "aquagrid.gis.tile.queryTimeout must be positive, was " + queryTimeout);
        }
    }

    /** The cache lifetime for a layer, in seconds, given whether Layer Management marks it editable. */
    public long cacheSecondsFor(boolean editable) {
        return (editable ? dynamicCacheMaxAge : staticCacheMaxAge).toSeconds();
    }
}
