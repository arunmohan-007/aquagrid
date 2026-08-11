package com.aquagrid.platform.gis.domain.tile;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;

import java.util.Map;

/**
 * A validated {@code z/x/y} tile address.
 *
 * <p>Exists because {@code ST_TileEnvelope} validates its own arguments and does so by raising:
 * {@code z} above 31 or an {@code x}/{@code y} outside {@code 0 .. 2^z - 1} aborts the statement
 * with "Invalid tile x value" or "Zoom level exceeds max". Postgres does not distinguish that from
 * any other query failure, so an address a client simply mistyped arrived at the error handler as a
 * {@code DataAccessException} and left as a 500 — an operator-visible server fault for what is a
 * malformed request, and a database message one layer away from being echoed back to the caller.
 * Rejecting the address before it reaches SQL is what makes that a 400.
 *
 * <p>The ceiling is {@link #MAX_ZOOM} rather than PostGIS's 31. Nothing serves tiles that deep —
 * the composed source cuts at 20 and MapLibre overzooms past it by scaling the last tile — and
 * {@code 2^31} tile rows per axis is a range worth refusing rather than planning a query for.
 */
public record TileCoordinate(int z, int x, int y) {

    /**
     * Deepest zoom a tile may be requested at.
     *
     * <p>24 is the deepest a layer's own {@code maxZoom} can be set to in Layer Management, so this
     * is the widest the endpoint can be without accepting an address no layer could ever be
     * configured to want.
     */
    public static final int MAX_ZOOM = 24;

    /**
     * Validates an address, or refuses it.
     *
     * @throws BusinessException {@link ErrorCode#VALIDATION_FAILED} (400) when the address is not a
     *                           tile that exists at that zoom. The properties carry the offending
     *                           values so the client can see what it sent without the message having
     *                           to interpolate them.
     */
    public static TileCoordinate of(int z, int x, int y) {
        if (z < 0 || z > MAX_ZOOM) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Tile zoom must be between 0 and " + MAX_ZOOM,
                    Map.of("z", z, "x", x, "y", y));
        }
        /*
         * 1L << z, not 1 << z. At z=31 an int shift overflows to Integer.MIN_VALUE and the bound
         * check inverts — every x would pass at the one zoom where the range matters most. The
         * MAX_ZOOM ceiling above already prevents reaching it, but a bound that is only correct
         * because of a check somewhere else is the kind that survives the check being relaxed.
         */
        long side = 1L << z;
        if (x < 0 || x >= side || y < 0 || y >= side) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Tile x and y must be between 0 and " + (side - 1) + " at zoom " + z,
                    Map.of("z", z, "x", x, "y", y));
        }
        return new TileCoordinate(z, x, y);
    }

    /** For log lines, which is the only place the three appear as one string. */
    @Override
    public String toString() {
        return z + "/" + x + "/" + y;
    }
}
