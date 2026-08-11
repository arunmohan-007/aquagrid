package com.aquagrid.platform.gis.web.controller;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.gis.application.service.AttachmentService;
import com.aquagrid.platform.gis.storage.ObjectStoragePort.StoredObject;
import com.aquagrid.platform.gis.web.dto.AssetDto.AttachmentSummary;
import com.aquagrid.platform.security.core.Permissions;
import com.aquagrid.platform.security.core.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
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
import java.util.List;
import java.util.UUID;

/**
 * Asset attachment upload/download.
 *
 * <p>Upload is multipart (a file input); download streams the bytes straight from object storage to
 * the response without buffering, so a large as-built PDF does not sit in heap.
 */
@Tag(name = "Attachments", description = "Asset file attachments")
@RestController
@RequestMapping(value = ApiPaths.ASSETS, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    @GetMapping("/{assetId}/attachments")
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List an asset's attachments")
    public List<AttachmentSummary> list(@PathVariable UUID assetId) {
        return attachmentService.list(assetId, SecurityUtils.requirePrincipal().organizationId());
    }

    @PostMapping(value = "/{assetId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_UPDATE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Upload an attachment")
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentSummary upload(@PathVariable UUID assetId,
                                    @RequestParam("file") MultipartFile file) throws IOException {
        var principal = SecurityUtils.requirePrincipal();
        return attachmentService.upload(assetId, principal.organizationId(), principal.userId(),
                file.getOriginalFilename(), file.getContentType(), file.getSize(), file.getInputStream());
    }

    @GetMapping(value = "/attachments/{attachmentId}")
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Download an attachment")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID attachmentId) {
        StoredObject stored = attachmentService.download(attachmentId,
                SecurityUtils.requirePrincipal().organizationId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, stored.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment")
                .body(new InputStreamResource(stored.content()));
    }

    @DeleteMapping("/attachments/{attachmentId}")
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_UPDATE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Delete an attachment")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID attachmentId) {
        var principal = SecurityUtils.requirePrincipal();
        attachmentService.delete(attachmentId, principal.organizationId(), principal.userId());
    }
}
