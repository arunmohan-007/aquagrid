package com.aquagrid.platform.gis.application.service;

import com.aquagrid.platform.common.audit.AuditCategory;
import com.aquagrid.platform.common.audit.AuditEvent;
import com.aquagrid.platform.common.audit.AuditService;
import com.aquagrid.platform.common.audit.AuditSeverity;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.gis.domain.enums.SymbolFormat;
import com.aquagrid.platform.gis.domain.model.LayerStyle;
import com.aquagrid.platform.gis.domain.model.MapSymbol;
import com.aquagrid.platform.gis.domain.style.SvgSanitizer;
import com.aquagrid.platform.gis.domain.style.SymbolKeys;
import com.aquagrid.platform.gis.infrastructure.persistence.LayerStyleRepository;
import com.aquagrid.platform.gis.infrastructure.persistence.LayerStyleRuleRepository;
import com.aquagrid.platform.gis.infrastructure.persistence.MapSymbolRepository;
import com.aquagrid.platform.gis.storage.ObjectStoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The uploaded symbol library — its only writer, and its read side.
 *
 * <p>The built-in shapes cover the common cases and none of the specific ones. A utility that has
 * drawn the same gate-valve glyph on its plans for twenty years wants that glyph, and "circle,
 * square or diamond" is not a reply to that.
 *
 * <p>Two things here carry more weight than the rest.
 *
 * <p><b>An uploaded SVG is a document, not an image.</b> Served from this origin it runs with this
 * application's privileges, so {@link SvgSanitizer} inspects the bytes before anything is stored,
 * the controller serves them under a restrictive CSP, and the client rasterises them through an
 * {@code Image} rather than by injecting markup. Three independent layers, because any one of them
 * can be wrong.
 *
 * <p><b>A symbol in use cannot be deleted.</b> Styles reference symbols by name inside a JSONB
 * document, which no foreign key can see, so the check lives here — and it answers with the styles
 * that would break rather than with a constraint violation, because the administrator's next
 * question is always "which ones".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MapSymbolService {

    /** Bounds the bytes held in memory per upload and matches the CHECK constraint in V1334. */
    public static final long MAX_BYTES = 1024L * 1024L;

    /**
     * Pulls {@code width}/{@code height} off the root {@code <svg>} element.
     *
     * <p>Best effort, and nothing depends on it: a viewBox with no width or height is legal and
     * common, in which case the client rasterises at its own default and {@code icon-size} does the
     * rest. It is read only so the library can show an administrator what they uploaded.
     */
    private static final Pattern SVG_DIMENSION = Pattern.compile(
            "(?is)<svg[^>]*\\b(width|height)\\s*=\\s*[\"']?\\s*([0-9]+(?:\\.[0-9]+)?)");

    private final MapSymbolRepository symbolRepository;
    private final LayerStyleRepository styleRepository;
    private final LayerStyleRuleRepository ruleRepository;
    private final ObjectStoragePort storage;
    private final LayerRenderCache renderCache;
    private final AuditService auditService;

    // ---- Reads ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<MapSymbol> list(UUID organizationId) {
        return symbolRepository.findByOrganizationIdOrderByNameAsc(organizationId);
    }

    @Transactional(readOnly = true)
    public MapSymbol require(UUID organizationId, UUID symbolId) {
        return symbolRepository.findByIdAndOrganizationId(symbolId, organizationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No symbol " + symbolId + " in this organisation."));
    }

    /** The bytes, for the download endpoint. The caller streams and closes. */
    @Transactional(readOnly = true)
    public ObjectStoragePort.StoredObject content(UUID organizationId, UUID symbolId) {
        return storage.get(require(organizationId, symbolId).getStorageKey());
    }

    // ---- Upload --------------------------------------------------------------------------------

    /**
     * Stores an uploaded symbol.
     *
     * @param sdf whether the image is a tintable silhouette. Asked of the administrator as a
     *            question about their file rather than inferred: a single-colour PNG and a
     *            full-colour one are indistinguishable without decoding every pixel, and guessing
     *            wrong produces either a marker that ignores the layer's colour or one rendered as a
     *            flat silhouette of itself.
     */
    @Transactional
    public MapSymbol upload(UUID organizationId, UUID actorId, String actorName,
                            String name, String description, String fileName, String contentType,
                            byte[] content, boolean sdf) {

        String trimmedName = name == null || name.isBlank()
                ? stripExtension(fileName)
                : name.trim();
        if (trimmedName.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The symbol needs a name.");
        }
        symbolRepository.findByNameIgnoreCase(organizationId, trimmedName).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT,
                    "A symbol called '" + existing.getName() + "' already exists. Symbol names are "
                            + "what you pick from in the style editor, so two that differ only in "
                            + "capitalisation would be a library nobody can use.");
        });

        if (content == null || content.length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The uploaded file is empty.");
        }
        if (content.length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The file is " + (content.length / 1024) + " KB; the limit is "
                            + (MAX_BYTES / 1024) + " KB. Every symbol is loaded into the map's image "
                            + "atlas on every style load, so a large one is paid for on every page.");
        }

        SymbolFormat format = SymbolFormat.resolve(contentType, fileName)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                        "'" + fileName + "' is neither an SVG nor a PNG. SVG is preferred — a symbol "
                                + "is drawn at every zoom and at several sizes, and a raster one is "
                                + "either soft when scaled up or wasteful when scaled down."));

        Integer width = null;
        Integer height = null;
        if (format == SymbolFormat.SVG) {
            // Refuses rather than strips: a legitimate marker glyph carries none of what this looks
            // for, so a match means either an attack or a file the uploader is wrong about.
            SvgSanitizer.validate(content);
            int[] dimensions = readSvgDimensions(content);
            width = dimensions[0] > 0 ? dimensions[0] : null;
            height = dimensions[1] > 0 ? dimensions[1] : null;
        } else {
            requirePng(content);
        }

        MapSymbol symbol = new MapSymbol();
        symbol.setOrganizationId(organizationId);
        symbol.setName(trimmedName);
        symbol.setDescription(description == null || description.isBlank() ? null : description.trim());
        symbol.setFormat(format);
        symbol.setContentType(format.contentType());
        symbol.setSizeBytes(content.length);
        symbol.setSdf(sdf);
        symbol.setWidthPx(width);
        symbol.setHeightPx(height);
        /*
         * The storage key is generated, never derived from the uploaded file name. A key built from
         * user input is a path-traversal bug waiting for someone to upload "../../etc/passwd" —
         * FilesystemObjectStorage normalises defensively, but the fix belongs at the point the key
         * is made, not at the point it is used.
         */
        symbol.setStorageKey("map-symbols/" + organizationId + "/" + UUID.randomUUID()
                + format.extension());
        MapSymbol saved = symbolRepository.save(symbol);

        storage.put(saved.getStorageKey(), saved.getContentType(), new ByteArrayInputStream(content));

        audit(organizationId, actorId, actorName, "GIS_MAP_SYMBOL_UPLOADED", saved,
                "Uploaded map symbol '" + saved.getName() + "'",
                Map.of("format", format.name(),
                        // Audit metadata goes into a JSONB column on an append-only table; a numeric
                        // value there has produced a failed UPDATE before, so this is text.
                        "sizeBytes", String.valueOf(content.length),
                        "tintable", String.valueOf(sdf)));
        log.info("Map symbol '{}' ({}, {} bytes) uploaded for org {}",
                saved.getName(), format, content.length, organizationId);
        return saved;
    }

    // ---- Delete --------------------------------------------------------------------------------

    /**
     * Removes a symbol, unless a style is drawing with it.
     *
     * <p>Unlike layers and attributes, this really does delete: a symbol is an uploaded file, not
     * surveyed data, and re-uploading one is a drag-and-drop. What must not happen is deleting one a
     * style still names, because the style would keep referencing an image the map can no longer
     * register — MapLibre draws nothing for a missing icon and reports no error, so the layer would
     * quietly empty with nothing to explain it.
     */
    @Transactional
    public void delete(UUID organizationId, UUID actorId, String actorName, UUID symbolId) {
        MapSymbol symbol = require(organizationId, symbolId);

        List<String> inUse = stylesUsing(organizationId, symbol.iconName());
        if (!inUse.isEmpty()) {
            throw new BusinessException(ErrorCode.OPERATION_NOT_PERMITTED,
                    "'" + symbol.getName() + "' is drawn by " + inUse.size() + " style"
                            + (inUse.size() == 1 ? "" : "s") + ": " + String.join(", ", inUse)
                            + ". Point " + (inUse.size() == 1 ? "it" : "them") + " at another symbol "
                            + "first — deleting it would leave the map with an icon it cannot load, "
                            + "which draws nothing and reports no error.");
        }

        storage.delete(symbol.getStorageKey());
        symbolRepository.delete(symbol);
        renderCache.evict(organizationId);

        audit(organizationId, actorId, actorName, "GIS_MAP_SYMBOL_DELETED", symbol,
                "Deleted map symbol '" + symbol.getName() + "'", Map.of());
    }

    /**
     * The styles whose base symbol or any rule draws with this icon.
     *
     * <p>Read in Java rather than as a JSONB query. The set is small — styles are tens per tenant —
     * and expressing "this key equals this value in either of two documents, one of them on a child
     * table" in JPQL against a JSONB column means native SQL for a check that runs when somebody
     * clicks delete. The straightforward version is the right one at this size.
     */
    @Transactional(readOnly = true)
    public List<String> stylesUsing(UUID organizationId, String iconName) {
        List<LayerStyle> styles = styleRepository.findAll().stream()
                .filter(style -> organizationId.equals(style.getOrganizationId()))
                .toList();
        if (styles.isEmpty()) {
            return List.of();
        }
        Map<UUID, Boolean> ruleUses = new LinkedHashMap<>();
        ruleRepository.findActiveForStyles(styles.stream().map(LayerStyle::getId).toList())
                .forEach(rule -> {
                    if (iconName.equals(value(rule.getSymbol()))) {
                        ruleUses.put(rule.getStyleId(), true);
                    }
                });

        return styles.stream()
                .filter(style -> iconName.equals(value(style.getSymbol()))
                        || ruleUses.containsKey(style.getId()))
                .map(LayerStyle::getName)
                .distinct()
                .toList();
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static String value(Map<String, Object> symbol) {
        Object icon = symbol == null ? null : symbol.get(SymbolKeys.ICON);
        return icon == null ? null : icon.toString();
    }

    /**
     * Confirms the bytes really are a PNG.
     *
     * <p>The eight-byte signature, checked because the declared content type is whatever the browser
     * felt like sending and the extension is whatever the user typed. A file that is not what it
     * claims will fail to decode in the browser as an image that never appears, which is a much
     * harder thing to diagnose from the map than a refusal at the upload.
     */
    private static void requirePng(byte[] content) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (content.length < signature.length) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The file is too short to be a PNG.");
        }
        for (int i = 0; i < signature.length; i++) {
            if (content[i] != signature[i]) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "That file is named like a PNG but does not begin with the PNG signature, so "
                                + "it is something else with the wrong extension.");
            }
        }
    }

    /** {@code [width, height]}, zero where the attribute is absent or not a plain number. */
    private static int[] readSvgDimensions(byte[] content) {
        String svg = new String(content, StandardCharsets.UTF_8);
        int width = 0;
        int height = 0;
        Matcher matcher = SVG_DIMENSION.matcher(svg);
        while (matcher.find()) {
            int value = (int) Math.round(Double.parseDouble(matcher.group(2)));
            if (value < 1 || value > 4096) {
                continue;
            }
            if ("width".equalsIgnoreCase(matcher.group(1))) {
                width = value;
            } else {
                height = value;
            }
        }
        return new int[]{width, height};
    }

    private static String stripExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.trim();
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).replace('_', ' ').trim();
    }

    private void audit(UUID organizationId, UUID actorId, String actorName, String eventType,
                       MapSymbol symbol, String message, Map<String, Object> metadata) {
        Map<String, Object> full = new LinkedHashMap<>(metadata);
        full.put("symbolName", symbol.getName());
        auditService.record(AuditEvent.builder()
                .organizationId(organizationId)
                .actorUserId(actorId)
                .actorUsername(actorName)
                .eventType(eventType)
                .category(AuditCategory.CONFIGURATION)
                .severity(AuditSeverity.INFO)
                .resourceType("gis.map_symbol")
                .resourceId(symbol.getId() == null ? "" : symbol.getId().toString())
                .success(true)
                .message(message)
                .metadata(full)
                .build());
    }

    /** Normalises a name for comparison, mirroring the unique index. */
    static String normalise(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
