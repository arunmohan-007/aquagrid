package com.aquagrid.platform.gis.web.controller;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.gis.api.AttributeDefinition;
import com.aquagrid.platform.gis.application.command.StyleCommands;
import com.aquagrid.platform.gis.application.service.LayerManagementService;
import com.aquagrid.platform.gis.application.service.LayerStyleService;
import com.aquagrid.platform.gis.domain.enums.StyleOperator;
import com.aquagrid.platform.gis.domain.enums.StyleType;
import com.aquagrid.platform.gis.domain.style.StyleTemplates;
import com.aquagrid.platform.gis.domain.style.SymbolLibrary;
import com.aquagrid.platform.gis.web.dto.StyleDtos;
import com.aquagrid.platform.security.core.AuthenticatedPrincipal;
import com.aquagrid.platform.security.core.Permissions;
import com.aquagrid.platform.security.core.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Layer Style Management — how each layer is drawn.
 *
 * <p>Every field a style names comes from Data Management's catalogue and is validated against it
 * here; this module keeps no attribute list of its own. {@code GET /fields} exists only to spare the
 * client a second call — it returns exactly what {@code /data-management/attributes?layerId=…}
 * returns, from the same service.
 *
 * <p>The composed MapLibre specifications are served from {@code /gis/map-style}, on the map's own
 * permission, rather than from here: the map is a reader of styles, not an administrator of them.
 */
@Tag(name = "Layer Styles", description = "How each GIS layer is drawn, and the rules that classify it")
@RestController
@RequestMapping(value = ApiPaths.LAYER_STYLES, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class LayerStyleController {

    /**
     * The tile URL the composed preview points at.
     *
     * <p>Relative, with {@code {layer}} substituted by the composer and {@code {z}/{x}/{y}} left for
     * MapLibre. Relative because the client is served from the same origin through nginx and the
     * proxy in development — an absolute URL baked here would be wrong in one of the two, and the
     * bearer token is attached per request by the map's {@code transformRequest} hook, which only
     * matches same-origin tile URLs.
     */
    private static final String TILE_URL = ApiPaths.GIS + "/tiles/{layer}/{z}/{x}/{y}";

    private final LayerStyleService styleService;
    private final LayerManagementService layerService;
    private final SymbolLibrary symbolLibrary;

    // ---- Styles --------------------------------------------------------------------------------

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.STYLE_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Every style configured on a layer")
    public List<StyleDtos.StyleResponse> list(@RequestParam UUID layerId) {
        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        return styleService.listForLayer(orgId, layerId).stream()
                .map(StyleDtos.StyleResponse::from).toList();
    }

    @GetMapping("/{styleId}")
    @PreAuthorize("hasAuthority('" + Permissions.STYLE_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "One style, with its rules in evaluation order")
    public StyleDtos.StyleResponse get(@PathVariable UUID styleId) {
        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        return StyleDtos.StyleResponse.from(styleService.get(orgId, styleId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.STYLE_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a style",
            description = """
                    The whole style in one call, rules included. A classified style is only
                    meaningful complete — three of four bands is a style that renders, looks wrong,
                    and gives no clue the fourth is still being typed — so it is saved as one
                    transaction and the map never sees a half-edited classification.

                    Every field named by the label, the classification or a rule must be an active
                    field on the layer in Data Management. So must the operator suit the field's
                    declared type: an ordered comparison on a TEXT field, or a numeric band on a
                    field holding words, is refused rather than composed into an expression that
                    silently never matches.""")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "A field is not in the layer's catalogue, or an "
            + "operator does not suit its type")
    @ApiResponse(responseCode = "409", description = "A style with that name exists on the layer")
    public StyleDtos.StyleResponse create(@Valid @RequestBody StyleDtos.SaveRequest request) {
        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        return StyleDtos.StyleResponse.from(styleService.save(principal.organizationId(),
                principal.userId(), principal.username(), null, toCommand(request)));
    }

    @PutMapping("/{styleId}")
    @PreAuthorize("hasAuthority('" + Permissions.STYLE_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Replace a style",
            description = """
                    Replaces the style and its rules entire. Rules are not merged: one dropped from
                    the editor must disappear, and merging would keep drawing a class the
                    administrator deleted.

                    A style belongs to the layer it was created on and cannot be moved — its rules
                    are validated against that layer's fields, which another layer may not have.""")
    public StyleDtos.StyleResponse update(@PathVariable UUID styleId,
                                          @Valid @RequestBody StyleDtos.SaveRequest request) {
        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        return StyleDtos.StyleResponse.from(styleService.save(principal.organizationId(),
                principal.userId(), principal.username(), styleId, toCommand(request)));
    }

    @PostMapping("/{styleId}/activate")
    @PreAuthorize("hasAuthority('" + Permissions.STYLE_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Activate a style")
    public StyleDtos.StyleResponse activate(@PathVariable UUID styleId,
                                            @RequestBody(required = false) StyleDtos.StateChangeRequest request) {
        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        return StyleDtos.StyleResponse.from(styleService.setActive(principal.organizationId(),
                principal.userId(), principal.username(), styleId, true,
                request == null ? null : request.reason()));
    }

    @PostMapping("/{styleId}/deactivate")
    @PreAuthorize("hasAuthority('" + Permissions.STYLE_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Deactivate a style",
            description = """
                    Deactivating the layer's default is allowed and leaves it with none, at which
                    point the map draws it with the platform's built-in symbology. That is the point:
                    refusing would make deactivation impossible, and blanking the layer would make it
                    destructive, and it is neither.""")
    public StyleDtos.StyleResponse deactivate(@PathVariable UUID styleId,
                                              @RequestBody(required = false) StyleDtos.StateChangeRequest request) {
        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        return StyleDtos.StyleResponse.from(styleService.setActive(principal.organizationId(),
                principal.userId(), principal.username(), styleId, false,
                request == null ? null : request.reason()));
    }

    @PostMapping("/{styleId}/make-default")
    @PreAuthorize("hasAuthority('" + Permissions.STYLE_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Make this the style the map draws",
            description = "Activates it if it was not — a hidden default is not one. Exactly one "
                    + "active default per layer is guaranteed by a partial unique index, not by this "
                    + "call clearing the previous one first, because clear-then-set is correct only "
                    + "until two administrators do it in the same second.")
    public StyleDtos.StyleResponse makeDefault(@PathVariable UUID styleId) {
        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        return StyleDtos.StyleResponse.from(styleService.makeDefault(principal.organizationId(),
                principal.userId(), principal.username(), styleId));
    }

    // ---- Preview -------------------------------------------------------------------------------

    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('" + Permissions.STYLE_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Compose a style without saving it",
            description = """
                    Returns the MapLibre source and layer specifications the map would apply, plus
                    the legend, for a style that exists only in the editor.

                    It runs the same validation as the save, on the same code path with the write
                    removed, so the preview cannot show something the save would reject — which is
                    what makes 'preview then save' a guarantee rather than a hope. It is also the
                    same composer the map uses, so the preview and the map cannot disagree about what
                    a rule means.""")
    public StyleDtos.ComposedLayerResponse preview(@Valid @RequestBody StyleDtos.SaveRequest request) {
        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        return StyleDtos.ComposedLayerResponse.from(
                styleService.preview(orgId, toCommand(request), TILE_URL));
    }

    // ---- Reference data ------------------------------------------------------------------------

    @GetMapping("/fields")
    @PreAuthorize("hasAuthority('" + Permissions.STYLE_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "The fields a style may reference on a layer",
            description = """
                    Data Management's catalogue for the layer, unmodified — the active attributes and
                    nothing else. This module keeps no attribute list of its own; the endpoint exists
                    only so the style editor does not need a second permission and a second call to
                    populate a field picker.

                    A field retired in Data Management disappears from here immediately, and a style
                    that names one is refused on save.""")
    public List<FieldOption> fields(@RequestParam UUID layerId) {
        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        return styleService.catalogue(orgId, layerId).values().stream()
                .map(FieldOption::from)
                .toList();
    }

    @GetMapping("/templates")
    @PreAuthorize("hasAuthority('" + Permissions.STYLE_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Starting points for a new style",
            description = """
                    Named, complete symbols an administrator can adjust rather than assemble. A blank
                    editor asks for a fill colour, a stroke colour, a width, an opacity and a zoom
                    range before it shows anything, for a decision usually described as "like the
                    mains, but red".

                    Filtered to the layer's geometry when `layerId` is given — a dashed-boundary
                    template is no use on a point layer. A mixed-geometry layer gets all of them,
                    because it may hold all of them.

                    Classified templates carry rule *seeds* rather than finished rules: the field is
                    the administrator's to choose, since a condition field may be spelt `status`,
                    `condition` or `asset_state` depending on whose survey it came from. The client
                    pre-selects `suggestedField` only when the layer's Data Management catalogue
                    actually has it — this endpoint never invents a field.""")
    public List<StyleDtos.TemplateResponse> templates(@RequestParam(required = false) UUID layerId) {
        List<StyleTemplates.Template> templates;
        if (layerId == null) {
            templates = StyleTemplates.all();
        } else {
            UUID orgId = SecurityUtils.requirePrincipal().organizationId();
            templates = StyleTemplates.forGeometry(layerService.require(orgId, layerId).getGeometryType());
        }
        return templates.stream().map(StyleDtos.TemplateResponse::from).toList();
    }

    @GetMapping("/vocabulary")
    @PreAuthorize("hasAuthority('" + Permissions.STYLE_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "The style editor's vocabulary",
            description = "Style types, operators with their operand arity, the symbol keys that "
                    + "apply to each geometry family, and the icon shapes. Served from the same "
                    + "definitions the server validates against, so the editor cannot offer a value "
                    + "the server would reject.")
    public StyleDtos.VocabularyResponse vocabulary() {
        return StyleDtos.VocabularyResponse.build(symbolLibrary.all());
    }

    @GetMapping(value = "/library-icons/{iconId}/content",
            produces = "image/svg+xml")
    @PreAuthorize("hasAuthority('" + Permissions.STYLE_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "One built-in icon's SVG bytes",
            description = """
                    Vendored into this build, not fetched from a CDN. A field-facing console must
                    render on a deployment with no route to the internet, and an icon that 404s draws
                    nothing with no error to explain it. The vendored sets are Mapbox Maki (CC0) and
                    Google Material Symbols (Apache-2.0), served under the same restrictive headers as
                    an uploaded symbol — the map loads every icon the same way, through an `Image`
                    rather than by injecting markup, so there is no reason to treat them differently.
                    Cached for a year: an icon id's bytes never change, since replacing one means
                    picking a different id.""")
    public ResponseEntity<byte[]> libraryIconContent(@PathVariable String iconId) {
        byte[] content = symbolLibrary.content(iconId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("image/svg+xml"))
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .body(content);
    }

    /**
     * One field the style editor may reference.
     *
     * <p>Carries the data type and a {@code numeric} flag because the editor needs both: the type to
     * label the picker, the flag to decide which operators to offer. Deriving the flag in the client
     * would be a second opinion about which types are numeric, and the server's is the one that
     * governs.
     */
    public record FieldOption(String fieldName, String displayName, String description,
                              String dataType, boolean numeric) {
        static FieldOption from(AttributeDefinition d) {
            return new FieldOption(d.fieldName(), d.displayName(), d.description(),
                    d.dataType().name(), d.dataType().isNumeric());
        }
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static StyleCommands.Save toCommand(StyleDtos.SaveRequest request) {
        return new StyleCommands.Save(
                request.layerId(), request.name(), request.description(),
                parseStyleType(request.styleType()), request.classifyField(),
                request.active(), request.defaultStyle(), request.minZoom(), request.maxZoom(),
                request.symbol(), request.label(),
                request.rules() == null ? List.of() : request.rules().stream()
                        .map(r -> new StyleCommands.Rule(r.fieldName(), parseOperator(r.operator()),
                                r.value1(), r.value2(), r.valueList(), r.label(), r.symbol(),
                                r.sortOrder()))
                        .toList());
    }

    private static StyleType parseStyleType(String raw) {
        if (raw == null || raw.isBlank()) {
            return StyleType.SIMPLE;
        }
        try {
            return StyleType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'" + raw + "' is not a style type. One of: "
                            + Arrays.stream(StyleType.values()).map(Enum::name)
                            .reduce((a, b) -> a + ", " + b).orElse("") + ".");
        }
    }

    private static StyleOperator parseOperator(String raw) {
        if (raw == null || raw.isBlank()) {
            return StyleOperator.EQ;
        }
        try {
            return StyleOperator.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'" + raw + "' is not an operator. One of: "
                            + Arrays.stream(StyleOperator.values()).map(Enum::name)
                            .reduce((a, b) -> a + ", " + b).orElse("") + ".");
        }
    }
}
