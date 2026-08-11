package com.aquagrid.platform.gis.web.controller;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.gis.application.service.MapSymbolService;
import com.aquagrid.platform.gis.domain.model.MapSymbol;
import com.aquagrid.platform.gis.storage.ObjectStoragePort;
import com.aquagrid.platform.security.core.AuthenticatedPrincipal;
import com.aquagrid.platform.security.core.Permissions;
import com.aquagrid.platform.security.core.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The uploaded symbol library.
 *
 * <p>Gated on the style permissions rather than on a new pair: uploading a marker is choosing how
 * the map looks, which is exactly what {@code gis:style:manage} already means. {@code :read} is as
 * wide as the map itself, because the map has to fetch the symbols it draws with.
 */
@Tag(name = "Map Symbols", description = "A tenant's uploaded icon library for layer styling")
@RestController
@RequestMapping(value = ApiPaths.MAP_SYMBOLS)
@RequiredArgsConstructor
public class MapSymbolController {

    /**
     * The Content-Security-Policy the symbol bytes are served under.
     *
     * <p>The second of the three layers guarding uploaded SVG — see {@code SvgSanitizer} for the
     * other two. An SVG served from this origin is a document with this application's privileges, so
     * this response says: no scripts of any origin, no network of any kind, no plugins, and a
     * sandbox with no permissions granted back. Even an SVG that somehow got past the upload check
     * can then neither run nor phone home.
     *
     * <p>{@code sandbox} with an empty value is the strong form — it withholds every capability,
     * including same-origin, so the document cannot reach anything belonging to the session that
     * fetched it.
     */
    private static final String SYMBOL_CSP =
            "default-src 'none'; style-src 'unsafe-inline'; img-src data:; sandbox";

    private final MapSymbolService symbolService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.STYLE_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "The tenant's symbol library",
            description = "Every uploaded symbol, with the icon name a style refers to it by and "
                    + "whether it is tintable. The style editor merges these with the built-in shapes "
                    + "to fill its icon picker.")
    public List<SymbolResponse> list() {
        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        return symbolService.list(orgId).stream().map(SymbolResponse::from).toList();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.STYLE_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a symbol",
            description = """
                    Accepts SVG and PNG. SVG is preferred: a symbol is drawn at every zoom and at
                    several sizes, and a raster one is either soft when scaled up or wasteful when
                    scaled down.

                    `tintable` is the one choice that changes what styling can do with the file. A
                    tintable symbol is a single-colour silhouette the map paints in the style's
                    colour — including a colour computed by an attribute rule, so the same glyph can
                    be green for in-service and red for faulty. A non-tintable symbol is drawn
                    exactly as uploaded and a classified style cannot recolour it.

                    Uploaded SVG is inspected before it is stored. Script, event handlers,
                    `foreignObject`, embedded documents, `javascript:`/`data:` URLs, external
                    references and DOCTYPE declarations are refused rather than stripped — a marker
                    glyph has none of them, so their presence means either an attack or a file the
                    uploader is wrong about, and silently altering someone's artwork is worse than
                    saying why it was rejected.""")
    @ApiResponse(responseCode = "201", description = "Stored")
    @ApiResponse(responseCode = "400", description = "Not an SVG or PNG, too large, or the SVG "
            + "carries executable or external content")
    @ApiResponse(responseCode = "409", description = "A symbol with that name already exists")
    public SymbolResponse upload(@RequestParam("file") MultipartFile file,
                                 @RequestParam(required = false) String name,
                                 @RequestParam(required = false) String description,
                                 @RequestParam(defaultValue = "true") boolean tintable)
            throws IOException {

        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        MapSymbol saved = symbolService.upload(principal.organizationId(), principal.userId(),
                principal.username(), name, description, file.getOriginalFilename(),
                file.getContentType(), file.getBytes(), tintable);
        return SymbolResponse.from(saved);
    }

    @GetMapping("/{symbolId}/content")
    @PreAuthorize("hasAuthority('" + Permissions.STYLE_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "The symbol's bytes",
            description = """
                    Served under a restrictive Content-Security-Policy and a sandbox, with
                    `nosniff` and an attachment-safe disposition. The map fetches this and rasterises
                    it through an `Image` onto a canvas rather than injecting it as markup — which
                    the HTML specification puts in *secure static mode*, where no script runs and no
                    external resource is fetched regardless of what the file contains.

                    Cached hard: the bytes under an id never change. Replacing a symbol means
                    uploading a new one, precisely so that every reference to an id keeps meaning
                    what it meant.""")
    public ResponseEntity<InputStreamResource> content(@PathVariable UUID symbolId) {
        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        MapSymbol symbol = symbolService.require(orgId, symbolId);
        ObjectStoragePort.StoredObject stored = symbolService.content(orgId, symbolId);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(symbol.getContentType()))
                .header("Content-Security-Policy", SYMBOL_CSP)
                // Without nosniff a browser may decide an SVG is HTML on the strength of its bytes,
                // which puts the whole CSP argument back on the wrong side of the question.
                .header("X-Content-Type-Options", "nosniff")
                /*
                 * `inline` with an explicit filename. The map loads this as an image, so it is never
                 * navigated to as a top-level document — and if someone pastes the URL into an
                 * address bar, the CSP and sandbox above are what stop it doing anything.
                 */
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + symbol.getId() + symbol.getFormat().extension() + "\"")
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePrivate().immutable())
                .body(new InputStreamResource(stored.content()));
    }

    @DeleteMapping("/{symbolId}")
    @PreAuthorize("hasAuthority('" + Permissions.STYLE_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a symbol",
            description = """
                    Refused while a style still draws with it, with the styles named. Unlike a layer
                    or an attribute this really does delete — a symbol is an uploaded file, not
                    surveyed data — but removing one a style references would leave the map with an
                    icon it cannot load, and MapLibre draws nothing for a missing icon and reports no
                    error, so the layer would quietly empty with nothing to explain it.""")
    @ApiResponse(responseCode = "403", description = "A style is still drawing with this symbol")
    public void delete(@PathVariable UUID symbolId) {
        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        symbolService.delete(principal.organizationId(), principal.userId(), principal.username(),
                symbolId);
    }

    /** One symbol, as the library and the icon picker show it. */
    @io.swagger.v3.oas.annotations.media.Schema(name = "MapSymbol")
    public record SymbolResponse(
            UUID id,
            String name,
            String description,
            String format,
            long sizeBytes,
            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "True when the map paints it in the style's colour rather than its own")
            boolean tintable,
            Integer widthPx,
            Integer heightPx,
            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "What a style stores in its `icon` property to draw with this symbol")
            String iconName,
            @io.swagger.v3.oas.annotations.media.Schema(description = "Where to fetch the bytes")
            String contentUrl,
            Instant createdDate,
            UUID createdBy
    ) {
        static SymbolResponse from(MapSymbol symbol) {
            return new SymbolResponse(symbol.getId(), symbol.getName(), symbol.getDescription(),
                    symbol.getFormat().name(), symbol.getSizeBytes(), symbol.isSdf(),
                    symbol.getWidthPx(), symbol.getHeightPx(), symbol.iconName(),
                    ApiPaths.MAP_SYMBOLS + "/" + symbol.getId() + "/content",
                    symbol.getCreatedAt(), symbol.getCreatedBy());
        }
    }
}
