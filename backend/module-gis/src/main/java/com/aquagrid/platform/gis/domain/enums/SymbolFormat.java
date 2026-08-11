package com.aquagrid.platform.gis.domain.enums;

import java.util.Locale;
import java.util.Optional;

/**
 * The image formats a map symbol may be uploaded in.
 *
 * <p>Two, and the shortness of the list is the point. A symbol is drawn at every zoom and at several
 * sizes, so SVG is the right answer and is what the upload form nudges towards; PNG is accepted
 * because a utility's existing symbol set is often a folder of PNGs and telling them to redraw it is
 * not a migration path.
 *
 * <p>JPEG is deliberately absent. It has no alpha channel, so a JPEG marker is a photograph of a
 * marker on a white rectangle — which is what it will look like on the map, and the person who
 * uploaded it will file a bug about the white box rather than recognise the format as the cause.
 */
public enum SymbolFormat {

    SVG("image/svg+xml", ".svg"),
    PNG("image/png", ".png");

    private final String contentType;
    private final String extension;

    SymbolFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    /**
     * Resolves a format from the browser's declared content type and the file name.
     *
     * <p>Both, because neither is reliable alone: a browser reports {@code application/octet-stream}
     * for an SVG often enough to matter, and an extension is whatever the user typed. Agreement is
     * not required — either identifying the format is enough — because this is a convenience, not
     * the security control. {@link com.aquagrid.platform.gis.domain.style.SvgSanitizer} inspects the
     * bytes, which is the only opinion that counts.
     */
    public static Optional<SymbolFormat> resolve(String contentType, String fileName) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        for (SymbolFormat format : values()) {
            if (type.contains(format.contentType) || name.endsWith(format.extension)) {
                return Optional.of(format);
            }
        }
        // "image/svg" without the +xml suffix is emitted by enough tooling to be worth catching.
        if (type.contains("svg") || name.endsWith(".svg")) {
            return Optional.of(SVG);
        }
        return Optional.empty();
    }
}
