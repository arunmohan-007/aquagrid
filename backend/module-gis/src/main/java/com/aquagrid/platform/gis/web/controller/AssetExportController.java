package com.aquagrid.platform.gis.web.controller;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.gis.application.export.AssetExportWriter;
import com.aquagrid.platform.gis.application.service.AssetExportService;
import com.aquagrid.platform.security.core.AuthenticatedPrincipal;
import com.aquagrid.platform.security.core.Permissions;
import com.aquagrid.platform.security.core.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Asset export, with the columns the Data Management catalogue declares.
 *
 * <p>The counterpart of the metadata-driven import. Adding a field in Data Management changes what
 * comes out of here with no release, and — because both sides read the same catalogue — a file
 * exported from a layer is a file that layer will import.
 *
 * <p>Gated on {@code gis:asset:read}, not on the metadata permission. Someone entitled to see the
 * asset register is entitled to a copy of it; the catalogue permission governs changing what a
 * field <em>is</em>, which is a different act.
 *
 * <p>The response streams. Handing the writer the servlet's own output stream means a 100,000-row
 * workbook is never assembled in memory to be measured for a Content-Length header — which is also
 * why no length is set.
 */
@Slf4j
@Tag(name = "Assets", description = "Export the asset register using the attribute catalogue")
@RestController
@RequestMapping(ApiPaths.ASSETS + "/export")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class AssetExportController {

    private final AssetExportService exportService;
    private final AssetExportWriter writer;

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_READ + "')")
    @Operation(summary = "Export a layer's assets",
            description = """
                    One row per asset, with one column per catalogue attribute.

                    **Active and visible fields only, by default.** Active is the catalogue's soft
                    delete — a retired field is never exported, because a column that is empty for
                    every row written since it was retired is worse than an absent one. Visible is
                    presentation, and it is why longitude and latitude do not appear: they are how a
                    CSV describes a geometry it cannot otherwise carry, and emitting them beside the
                    geometry they were folded into would be two derived columns pretending to be
                    data. `includeHidden` overrides the second, never the first.

                    Three formats from the same query. CSV and Excel carry the geometry as WKT;
                    GeoJSON makes it the feature's own geometry member and keys the properties by
                    field name, so a file exported from a layer is a file that layer will import.

                    Capped at 100,000 rows. Beyond that the honest answer is a database extract, not
                    a browser download — and hitting the cap is reported in the
                    `X-Export-Truncated` header rather than silently trimming the file.""")
    @ApiResponse(responseCode = "200", description = "The export, streamed")
    @ApiResponse(responseCode = "400", description = "The layer has no visible attributes to export")
    public ResponseEntity<StreamingResponseBody> export(
            @Parameter(description = "Layer to export, from GET /data-management/layers")
            @RequestParam UUID layerId,
            @Parameter(description = "CSV, XLSX or GEOJSON")
            @RequestParam(defaultValue = "CSV") AssetExportWriter.Format format,
            @Parameter(description = "Include attributes that are active but not visible")
            @RequestParam(defaultValue = "false") boolean includeHidden,
            @Parameter(description = "Free text over asset code and name")
            @RequestParam(required = false) String search) {

        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        /*
         * Read inside the request thread, not inside the streaming callback. The callback runs after
         * the response has been committed, where a BusinessException can no longer become a 400 —
         * the client would receive 200 followed by a truncated body. Reading first means "this layer
         * has no visible attributes" is still an error the caller can act on.
         */
        AssetExportService.ExportData data = exportService.read(principal.organizationId(), layerId,
                includeHidden, search, format.wantsGeoJsonGeometry());
        exportService.audit(principal.organizationId(), principal.userId(), principal.username(),
                data, format.name());

        String filename = "%s-%s.%s".formatted(
                slug(data.layer().getCode()), LocalDate.now(), format.extension());

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(format.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8).build().toString())
                .header("X-Export-Truncated", Boolean.toString(data.truncated()))
                .header("X-Export-Rows", Integer.toString(data.rows().size()))
                .body(out -> writer.write(out, data, format));
    }

    /** A layer code as a filename component. Codes are already slug-like; this is for the exceptions. */
    private static String slug(String code) {
        String cleaned = code.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return cleaned.isEmpty() ? "assets" : cleaned;
    }
}
