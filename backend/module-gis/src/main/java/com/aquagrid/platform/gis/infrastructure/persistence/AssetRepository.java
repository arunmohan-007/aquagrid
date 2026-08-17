package com.aquagrid.platform.gis.infrastructure.persistence;

import com.aquagrid.platform.gis.domain.model.Asset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Asset persistence.
 *
 * <p>Spatial queries use native SQL rather than JPQL — JPQL has no {@code ST_Intersects} or
 * {@code ST_AsMVT}. This is the explicit split called out in the technology justification: the ORM
 * serves the transactional model, native SQL serves the spatial and analytical model.
 */
@Repository
public interface AssetRepository extends JpaRepository<Asset, UUID> {

    /**
     * Tenant + bbox filtered asset list. The bbox is in EPSG:3857 (Web Mercator) because it comes
     * from the map viewport; transforming the bbox once is cheaper than transforming every geom.
     */
    @Query(value = """
            SELECT * FROM gis.assets a
            WHERE a.organization_id = :organizationId
              AND (cast(:assetType as text) IS NULL OR a.asset_type = :assetType)
              AND ST_Intersects(a.geom_3857, ST_MakeEnvelope(:minX, :minY, :maxX, :maxY, 3857))
            ORDER BY a.asset_code
            """, nativeQuery = true)
    List<Asset> findInBbox(@Param("organizationId") UUID organizationId,
                           @Param("assetType") String assetType,
                           @Param("minX") double minX, @Param("minY") double minY,
                           @Param("maxX") double maxX, @Param("maxY") double maxY);

    /**
     * Tenant-scoped, type-filtered, paginated list — for the asset register (no spatial filter).
     *
     * <p>{@code :search} is cast <em>inside</em> {@code concat}, not only in the {@code IS NULL}
     * guard. {@code concat} is variadic over {@code "any"}, so it gives Postgres nothing to infer
     * an argument's type from — and the datasource runs {@code stringtype=unspecified}, so the
     * driver sends the parameter untyped. The result is "could not determine data type of parameter
     * $5" for every non-empty search, while the unfiltered list works fine. The cast in the guard
     * does not help: that is a different parameter position.
     */
    @Query(value = """
            SELECT * FROM gis.assets a
            WHERE a.organization_id = :organizationId
              AND (cast(:assetType as text) IS NULL OR a.asset_type = :assetType)
              AND (cast(:search as text) IS NULL
                   OR a.name ILIKE concat('%', cast(:search as text), '%')
                   OR a.asset_code ILIKE concat('%', cast(:search as text), '%'))
            ORDER BY a.asset_code
            """,
            countQuery = """
            SELECT count(*) FROM gis.assets a
            WHERE a.organization_id = :organizationId
              AND (cast(:assetType as text) IS NULL OR a.asset_type = :assetType)
              AND (cast(:search as text) IS NULL
                   OR a.name ILIKE concat('%', cast(:search as text), '%')
                   OR a.asset_code ILIKE concat('%', cast(:search as text), '%'))
            """,
            nativeQuery = true)
    Page<Asset> findForTenant(@Param("organizationId") UUID organizationId,
                              @Param("assetType") String assetType,
                              @Param("search") String search,
                              Pageable pageable);

    /**
     * The dashboard's network summary: one row per panchayat, with pipe length and facility counts.
     *
     * <p>Returns {@code [panchayat, pipelineCount, pipelineLengthM, tanks, openWells, boreWells]}.
     *
     * <p>One query rather than four. Conditional aggregation (`FILTER`) computes every measure in a
     * single pass over the same index scan, so adding a facility type to the dashboard costs a
     * column here instead of another round trip — and the numbers are guaranteed to come from one
     * consistent read rather than four that could interleave with an import.
     *
     * <p>Length is {@code ST_Length(geom::geography)}, which is the ellipsoidal length in metres.
     * The map's inspection card computes a haversine length in Java from the same geometry; the two
     * agree to a few tenths of a percent, which is well inside what a dashboard rounds away.
     *
     * <p>The panchayat is read from the attribute bag, because that is where the shapefile import
     * puts it — there is no panchayat column on gis.assets and no join table yet. Assets whose file
     * carried no panchayat are grouped under 'Unassigned' rather than dropped: a total that
     * silently excludes rows is the kind of number that gets quoted in a review meeting and turns
     * out to be wrong.
     */
    @Query(value = """
            SELECT COALESCE(NULLIF(btrim(a.attributes->>'panchayat'), ''), 'Unassigned') AS panchayat,
                   COUNT(*) FILTER (WHERE a.asset_type = 'PIPELINE')  AS pipeline_count,
                   COALESCE(SUM(ST_Length(a.geom::geography))
                            FILTER (WHERE a.asset_type = 'PIPELINE'), 0) AS pipeline_length_m,
                   COUNT(*) FILTER (WHERE a.asset_type = 'TANK')      AS tanks,
                   COUNT(*) FILTER (WHERE a.asset_type = 'OPEN_WELL') AS open_wells,
                   COUNT(*) FILTER (WHERE a.asset_type = 'BORE_WELL') AS bore_wells
            FROM gis.assets a
            WHERE a.organization_id = :organizationId
              AND a.asset_type IN ('PIPELINE', 'TANK', 'OPEN_WELL', 'BORE_WELL')
            GROUP BY 1
            ORDER BY pipeline_length_m DESC, panchayat
            """, nativeQuery = true)
    List<Object[]> findNetworkSummaryByPanchayat(@Param("organizationId") UUID organizationId);

    // ---- Layer-scoped reads (Layer Management, V1332) -------------------------------------------

    /*
     * A layer's features are the rows that claim it, plus the unclaimed rows of its asset type.
     *
     * The second half of that is not a hedge, it is the migration's contract. `layer_id` is nullable
     * because a tenant provisioned after the layer-seeding migrations has no layer rows at all, so
     * its assets can carry no layer id; the fallback keeps those rows visible on exactly the layer
     * they appear on today. Everything written since V1332 is claimed and matches the first half
     * precisely, which is what makes per-layer counts and extents exact for two layers over one
     * asset type — the case the old asset_type-only queries could not express at all.
     *
     * Both halves are indexed: ix_assets_org_layer for the first, ix_assets_org_type for the second.
     */

    /**
     * Feature count for one layer.
     *
     * <p>{@code COUNT(*)} against the composite index, never a fetch. The registry shows this figure
     * for every layer on screen at once, and a layer with two million service connections must cost
     * the same as one with four tanks — pulling rows into the JVM to size them is the O(network)
     * payload this module exists to avoid.
     */
    @Query(value = """
            SELECT count(*) FROM gis.assets a
            WHERE a.organization_id = :organizationId
              AND (a.layer_id = :layerId
                   OR (a.layer_id IS NULL AND a.asset_type = cast(:assetType as text)))
            """, nativeQuery = true)
    long countForLayer(@Param("organizationId") UUID organizationId,
                       @Param("layerId") UUID layerId,
                       @Param("assetType") String assetType);

    /**
     * Bounding box of one layer's features in EPSG:4326, as {@code [minLon, minLat, maxLon, maxLat]}.
     *
     * <p>{@code ST_Extent} is an aggregate over the GiST-indexed 4326 geometry: one box for the whole
     * layer in a single round trip, whatever its size. Empty when the layer holds nothing — the outer
     * {@code WHERE} drops the all-null row rather than handing back four nulls to unpick.
     */
    @Query(value = """
            SELECT ST_XMin(e.box), ST_YMin(e.box), ST_XMax(e.box), ST_YMax(e.box)
            FROM (
                SELECT ST_Extent(a.geom) AS box
                FROM gis.assets a
                WHERE a.organization_id = :organizationId
                  AND (a.layer_id = :layerId
                       OR (a.layer_id IS NULL AND a.asset_type = cast(:assetType as text)))
            ) AS e
            WHERE e.box IS NOT NULL
            """, nativeQuery = true)
    List<Object[]> findExtentForLayer(@Param("organizationId") UUID organizationId,
                                      @Param("layerId") UUID layerId,
                                      @Param("assetType") String assetType);

    /**
     * The distinct values a layer's features actually carry under an attribute-bag key.
     *
     * <p>Feeds the categorical style editor, so an administrator classifying on {@code status}
     * chooses from the values in their own data rather than typing them from memory. Typing them is
     * how a category ends up spelled {@code FAULTY} in the style and {@code FAULT} in the rows, which
     * renders as a class that matches nothing and looks like a broken renderer.
     *
     * <p>Bounded by {@code :limit}. A field with ten thousand distinct values is not a category axis,
     * and the editor says so rather than rendering ten thousand colour pickers.
     */
    @Query(value = """
            SELECT DISTINCT a.attributes ->> cast(:key as text) AS value
            FROM gis.assets a
            WHERE a.organization_id = :organizationId
              AND (a.layer_id = :layerId
                   OR (a.layer_id IS NULL AND a.asset_type = cast(:assetType as text)))
              AND a.attributes ->> cast(:key as text) IS NOT NULL
            ORDER BY value
            LIMIT :limit
            """, nativeQuery = true)
    List<String> findDistinctAttributeValues(@Param("organizationId") UUID organizationId,
                                             @Param("layerId") UUID layerId,
                                             @Param("assetType") String assetType,
                                             @Param("key") String key,
                                             @Param("limit") int limit);

    /**
     * Minimum and maximum of a numeric attribute across a layer, for the graduated style editor's
     * suggested bands.
     *
     * <p>The regex guard is what makes this safe on an attribute bag. A JSONB value is text until
     * something casts it, the catalogue's declared type is a contract about what <em>should</em> be
     * there rather than a constraint Postgres enforced, and one row imported before the field was
     * retyped is enough to make an unguarded {@code ::numeric} abort the whole statement. Rows that
     * cannot be read as a number are skipped, which is the right answer for a suggestion.
     */
    @Query(value = """
            SELECT min(v), max(v) FROM (
                SELECT (a.attributes ->> cast(:key as text))::double precision AS v
                FROM gis.assets a
                WHERE a.organization_id = :organizationId
                  AND (a.layer_id = :layerId
                       OR (a.layer_id IS NULL AND a.asset_type = cast(:assetType as text)))
                  AND a.attributes ->> cast(:key as text) ~ '^-?[0-9]+(\\.[0-9]+)?$'
            ) AS n
            WHERE v IS NOT NULL
            """, nativeQuery = true)
    List<Object[]> findAttributeNumericRange(@Param("organizationId") UUID organizationId,
                                             @Param("layerId") UUID layerId,
                                             @Param("assetType") String assetType,
                                             @Param("key") String key);

    /**
     * Claims every unclaimed feature of an asset type for a layer.
     *
     * <p>Run when a layer is created over an asset type that already has rows — the import path that
     * lands features before anyone registers a layer for them. One statement, restricted to rows
     * that belong to no layer, so it can never move a feature off a layer it was deliberately put
     * on.
     *
     * @return the number of features claimed
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE gis.assets
            SET layer_id = :layerId
            WHERE organization_id = :organizationId
              AND asset_type = cast(:assetType as text)
              AND layer_id IS NULL
            """, nativeQuery = true)
    int claimUnassignedFeatures(@Param("organizationId") UUID organizationId,
                                @Param("layerId") UUID layerId,
                                @Param("assetType") String assetType);

    boolean existsByOrganizationIdAndAssetCode(UUID organizationId, String assetCode);

    /**
     * The asset an import row's {@code asset_code} column already belongs to, if any.
     *
     * <p>Backs the bulk importer's replace path: when a mapped {@code asset_code} matches an
     * existing asset, that row updates it instead of colliding with {@code uq_assets_org_code}.
     */
    java.util.Optional<Asset> findByOrganizationIdAndAssetCode(UUID organizationId, String assetCode);

    /**
     * Whether any asset of a type already carries this value under an attribute-bag key.
     *
     * <p>Backs the {@code unique} flag on catalogue attributes that live in the JSONB bag, where no
     * unique index can exist without the DDL the Data Management module deliberately avoids. The
     * GIN index on {@code attributes} makes the containment test cheap; the comparison is on the
     * text projection so that 150 and "150" match, which is what an operator means by "the same
     * consumer number appears twice".
     */
    @Query(value = """
            SELECT EXISTS(
                SELECT 1 FROM gis.assets a
                WHERE a.organization_id = :organizationId
                  AND a.asset_type = :assetType
                  AND a.attributes ->> cast(:key as text) = cast(:value as text)
            )
            """, nativeQuery = true)
    boolean existsByAttributeValue(@Param("organizationId") UUID organizationId,
                                   @Param("assetType") String assetType,
                                   @Param("key") String key,
                                   @Param("value") String value);

    /**
     * The asset that already carries this value under an attribute-bag key, if any.
     *
     * <p>The read counterpart of {@link #existsByAttributeValue}, used by the bulk importer to
     * find the row to update when a {@code unique} field matches something already stored, instead
     * of only being able to say that a collision exists.
     */
    @Query(value = """
            SELECT * FROM gis.assets a
            WHERE a.organization_id = :organizationId
              AND a.asset_type = :assetType
              AND a.attributes ->> cast(:key as text) = cast(:value as text)
            LIMIT 1
            """, nativeQuery = true)
    java.util.Optional<Asset> findFirstByAttributeValue(@Param("organizationId") UUID organizationId,
                                                        @Param("assetType") String assetType,
                                                        @Param("key") String key,
                                                        @Param("value") String value);

    /**
     * Moves every stored value of one attribute-bag key to a new key.
     *
     * <p>Run when Data Management renames an attribute. Leaving the values behind under the old key
     * would be the worse outcome by a distance: the field would read empty for every row imported
     * before the rename, and the data would still be there, invisible, until someone queried the
     * bag directly.
     *
     * <p>One statement rather than a read-modify-write loop. The operation is a key rename inside a
     * JSONB document and Postgres expresses it directly, so pulling a tenant's whole asset base
     * into the JVM to do it would be both slower and a far larger window for a concurrent write to
     * be lost. {@code attributes ? :oldName} restricts the update to rows that actually carry the
     * key, which keeps the cost proportional to the data rather than to the layer.
     *
     * <p>The {@code ?} operator is JSONB containment, not a JDBC placeholder — Hibernate's native
     * query parser would read it as one, so it is written as {@code jsonb_exists} instead. This is
     * the standard workaround and the reason it is not spelt the way the Postgres docs spell it.
     *
     * @return the number of assets whose bag was rewritten
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE gis.assets
            SET attributes = (attributes - cast(:oldName as text))
                             || jsonb_build_object(cast(:newName as text),
                                                   attributes -> cast(:oldName as text))
            WHERE organization_id = :organizationId
              AND asset_type = :assetType
              AND jsonb_exists(attributes, cast(:oldName as text))
            """, nativeQuery = true)
    int renameAttributeKey(@Param("organizationId") UUID organizationId,
                           @Param("assetType") String assetType,
                           @Param("oldName") String oldName,
                           @Param("newName") String newName);
}
