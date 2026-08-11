package com.aquagrid.platform.gis.domain.metadata;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What may be used as a layer's code.
 *
 * <p>A layer code is not a label. It is the {@code source-layer} name inside every vector tile, the
 * MapLibre source id every render layer references, and a path segment of the tile URL
 * ({@code /gis/tiles/{code}/{z}/{x}/{y}}). It therefore has to satisfy all three, and it has to be
 * settled at creation: renaming it would invalidate every cached tile and every MapLibre source
 * referencing it, and the map would stop drawing the layer with no error anywhere.
 *
 * <p>The grammar is deliberately narrower than {@link FieldNamePolicy}'s and different from it:
 * hyphens rather than underscores, because these codes appear in URLs and the existing catalogue
 * already spells them {@code open-wells} and {@code bore-wells}. Sharing one policy between the two
 * would mean either breaking those codes or admitting underscores into URLs; a second small class is
 * the cheaper answer, and the two rules are genuinely about different things.
 *
 * <p>The same grammar is a CHECK constraint on {@code gis.layers} (V1332). This class produces the
 * error a human reads; the constraint stops a bad row arriving by a route that bypasses this class.
 */
public final class LayerCodePolicy {

    /** Comfortably inside the column's 60, and long enough for the longest sensible code. */
    public static final int MAX_LENGTH = 60;

    private static final Pattern VALID = Pattern.compile("^[a-z][a-z0-9-]*$");

    private LayerCodePolicy() {
    }

    /**
     * Normalises and validates a proposed layer code.
     *
     * <p>Unlike a field name, a layer code <em>is</em> derived when the operator does not supply one
     * — see {@link #deriveFrom(String)} — but a code they typed is validated as typed rather than
     * repaired. The distinction matters: a derived code is the platform's suggestion and the
     * operator sees it before saving, while silently rewriting one they entered would produce a
     * layer whose tile URL is not what they were told it would be.
     */
    public static String normaliseAndValidate(String proposed) {
        if (proposed == null || proposed.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Layer name is required.");
        }
        String code = proposed.trim().toLowerCase(Locale.ROOT);

        if (code.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Layer name is " + code.length() + " characters; the limit is " + MAX_LENGTH + ".");
        }
        if (!VALID.matcher(code).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Layer name '" + proposed.trim() + "' is not valid. Use lower-case letters, "
                            + "numbers and hyphens only, starting with a letter — for example "
                            + "'street-lights'. The layer name appears in tile URLs, which is why it "
                            + "is narrower than the display name.");
        }
        return code;
    }

    /**
     * A code derived from a display name — {@code "Street Lights"} to {@code "street-lights"}.
     *
     * <p>Offered as the default when an administrator types a display name and leaves the code
     * blank. Anything that is not a letter or digit collapses to a single hyphen, leading digits are
     * prefixed (a code must start with a letter), and the result is truncated rather than rejected,
     * because this runs before the operator has been shown anything to correct.
     *
     * @return a valid code, or empty when the display name contains nothing usable — a name of only
     *         punctuation gives no basis for a guess, and the operator is asked for a code instead
     */
    public static String deriveFrom(String displayName) {
        if (displayName == null) {
            return "";
        }
        String code = displayName.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (code.isEmpty()) {
            return "";
        }
        if (!Character.isLetter(code.charAt(0))) {
            code = "layer-" + code;
        }
        return code.length() > MAX_LENGTH ? code.substring(0, MAX_LENGTH).replaceAll("-+$", "") : code;
    }
}
