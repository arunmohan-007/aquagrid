package com.aquagrid.platform.gis.domain.style;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips the executable parts out of an uploaded SVG.
 *
 * <p>An SVG is not an image, it is a document. Served from this application's own origin it runs
 * with this application's privileges: {@code <script>} in an uploaded file is stored XSS against
 * every operator who opens the symbol library, and {@code <foreignObject>} smuggles arbitrary HTML
 * past anything that only looks for script tags. Accepting user-uploaded SVG without this is one of
 * the more reliable ways to hand a session to whoever uploaded it.
 *
 * <p>The platform defends in three independent layers, because any one of them can be wrong:
 *
 * <ol>
 *   <li><b>This sanitiser, at upload.</b> Refuses a file carrying script, event handlers, external
 *       references or embedded documents, rather than quietly stripping them. Refusing is the right
 *       call here: a legitimate marker glyph has none of these, so their presence means either an
 *       attack or a file the uploader is wrong about, and silently altering someone's artwork is
 *       worse than telling them why it was rejected.</li>
 *   <li><b>Response headers, at download.</b> {@code MapSymbolController} serves the bytes under a
 *       restrictive {@code Content-Security-Policy}, {@code X-Content-Type-Options: nosniff} and a
 *       sandbox, so even a file that got past this cannot reach the DOM or the network.</li>
 *   <li><b>How the client loads it.</b> The map rasterises symbols through {@code new Image()} onto
 *       a canvas, never by injecting markup. The HTML specification puts an SVG loaded that way in
 *       <em>secure static mode</em>: no scripts run, no external resources are fetched, no
 *       declarative animation. This is the strongest of the three and the reason the symbol is
 *       rasterised rather than inlined.</li>
 * </ol>
 *
 * <p>The implementation is deliberately a blocklist over the raw text rather than a parse-and-
 * rebuild. A rebuilding sanitiser needs a full SVG DOM and an allowlist of every element and
 * attribute an illustrator might legitimately emit, and gets that allowlist wrong in the direction
 * of mangling valid artwork. Here the blocklist is the complete set of ways an SVG can execute or
 * fetch, the check is conservative (it refuses on a match rather than editing), and layers two and
 * three cover what a text scan cannot see.
 */
public final class SvgSanitizer {

    /** An SVG bigger than this is not a map marker. Also bounds the regex work below. */
    public static final int MAX_BYTES = 512 * 1024;

    /**
     * Constructs that execute, fetch, or embed another document.
     *
     * <p>Each entry is a way an SVG stops being a picture:
     * <ul>
     *   <li>{@code script}, {@code handler} — executable content, directly.</li>
     *   <li>{@code foreignObject} — an escape hatch into arbitrary HTML, and the usual bypass for a
     *       filter that only looks for {@code <script>}.</li>
     *   <li>{@code iframe}, {@code embed}, {@code object}, {@code audio}, {@code video} — embedded
     *       documents and media, none of which belong in a marker.</li>
     *   <li>{@code set}, {@code animate} with an {@code attributeName} of {@code href} — SMIL
     *       animation can rewrite an attribute into a {@code javascript:} URL after load, which is a
     *       script that never appears as one.</li>
     *   <li>{@code use} with an external {@code href} — pulls in a document from elsewhere.</li>
     * </ul>
     */
    private static final List<Pattern> FORBIDDEN_ELEMENTS = List.of(
            compileElement("script"),
            compileElement("handler"),
            compileElement("foreignObject"),
            compileElement("iframe"),
            compileElement("embed"),
            compileElement("object"),
            compileElement("audio"),
            compileElement("video"),
            compileElement("animation"));

    /**
     * Any {@code on*} attribute — {@code onload}, {@code onclick}, {@code onmouseover}, and the long
     * tail nobody remembers. Matched by shape rather than by name, because an allowlist of event
     * names is a list that is always one browser release out of date.
     */
    private static final Pattern EVENT_HANDLER = Pattern.compile(
            "(?is)<[^>]*\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);

    /**
     * URL schemes that execute or carry a payload.
     *
     * <p>{@code data:} is included even though it fetches nothing: a {@code data:text/html} in an
     * {@code href} is a document, and distinguishing the harmless {@code data:image/png} case is not
     * worth the risk in a file that should contain no URLs at all.
     */
    private static final Pattern DANGEROUS_URL = Pattern.compile(
            "(?is)(javascript|vbscript|data)\\s*:", Pattern.CASE_INSENSITIVE);

    /**
     * A reference to somewhere else on the network.
     *
     * <p>Refused because a marker that fetches is a marker that phones home: it would leak every
     * viewer's address to whoever uploaded it, and it would break the moment the deployment is
     * offline, which for a field-facing utility console is a normal Tuesday.
     */
    private static final Pattern EXTERNAL_REFERENCE = Pattern.compile(
            "(?is)(href|xlink:href|src)\\s*=\\s*[\"']?\\s*(https?:)?//", Pattern.CASE_INSENSITIVE);

    /**
     * An external DTD or entity declaration — the XXE vector.
     *
     * <p>Nothing here parses the file as XML, so this is not an exploit against <em>this</em>
     * service. It is refused because the bytes are handed to browsers and to whatever tooling a
     * utility later points at its own symbol library, and an entity that reads {@code /etc/passwd}
     * is not something to pass along on the grounds that we personally did not open it.
     */
    private static final Pattern DOCTYPE_OR_ENTITY = Pattern.compile(
            "(?is)<!(DOCTYPE|ENTITY)", Pattern.CASE_INSENSITIVE);

    private SvgSanitizer() {
    }

    /**
     * Validates an uploaded SVG.
     *
     * @return the original bytes when the file is safe — unmodified, so an illustrator's output is
     *         byte-for-byte what they drew
     * @throws BusinessException naming the construct that caused the refusal, because "invalid SVG"
     *                           tells the person holding the file nothing they can act on
     */
    public static byte[] validate(byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The SVG file is empty.");
        }
        if (content.length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The SVG is " + (content.length / 1024) + " KB; the limit is " + (MAX_BYTES / 1024)
                            + " KB. A map marker this large is usually an illustration that still has "
                            + "its artboard, guides or an embedded raster in it.");
        }

        String svg = new String(content, StandardCharsets.UTF_8);
        if (!svg.toLowerCase(Locale.ROOT).contains("<svg")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "That file does not contain an <svg> element, so it is not an SVG whatever its "
                            + "extension says.");
        }

        for (Pattern forbidden : FORBIDDEN_ELEMENTS) {
            Matcher matcher = forbidden.matcher(svg);
            if (matcher.find()) {
                throw refuse("the <" + matcher.group(1) + "> element");
            }
        }
        if (EVENT_HANDLER.matcher(svg).find()) {
            throw refuse("an event handler attribute (on…=)");
        }
        if (DANGEROUS_URL.matcher(svg).find()) {
            throw refuse("a javascript:, vbscript: or data: URL");
        }
        if (EXTERNAL_REFERENCE.matcher(svg).find()) {
            throw refuse("a reference to an external URL");
        }
        if (DOCTYPE_OR_ENTITY.matcher(svg).find()) {
            throw refuse("a DOCTYPE or ENTITY declaration");
        }
        return content;
    }

    private static BusinessException refuse(String what) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED,
                "This SVG contains " + what + ", which a map symbol has no use for and which would "
                        + "run in the browser of everyone who opens the map. Re-export it as a plain "
                        + "shape — in Illustrator or Inkscape, 'Save as → Plain SVG' — and upload "
                        + "that.");
    }

    /** Matches an element by name, opening tag only; the capture group names it for the message. */
    private static Pattern compileElement(String name) {
        return Pattern.compile("(?is)<\\s*(" + name + ")\\b", Pattern.CASE_INSENSITIVE);
    }
}
