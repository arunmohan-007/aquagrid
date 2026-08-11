package com.aquagrid.platform.gis.application.service;

import com.aquagrid.platform.common.audit.AuditCategory;
import com.aquagrid.platform.common.audit.AuditEvent;
import com.aquagrid.platform.common.audit.AuditEventTypes;
import com.aquagrid.platform.common.audit.AuditService;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.gis.api.AttributeDefinition;
import com.aquagrid.platform.gis.domain.model.ImportFieldMapping;
import com.aquagrid.platform.gis.infrastructure.persistence.ImportFieldMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Saved import mapping profiles.
 *
 * <p>A utility receives the same contractor's deliverable every quarter with the same twenty column
 * headings. Re-mapping them by hand each time is where mis-mappings come from, so the mapping is
 * saved once under a profile name and offered back on the next import of that layer.
 *
 * <p>Profiles are stored by attribute id, not by field name, so a later rename does not quietly
 * detach a profile from the field it maps to — the mapping follows the attribute, which is the
 * behaviour an operator expects and the one a name-keyed store cannot give.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportMappingService {

    /** Reserved mapping value meaning the operator chose to drop this column. */
    public static final String IGNORE = "ignore";

    private final ImportFieldMappingRepository mappingRepository;
    private final LayerMetadataService metadataService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<String> profileNames(UUID organizationId, UUID layerId) {
        return mappingRepository.findProfileNames(organizationId, layerId);
    }

    /**
     * A saved profile, resolved back to field names.
     *
     * <p>Attributes that have since been deactivated are dropped rather than returned: offering a
     * retired field in the mapper is how a field nobody meant to revive gets written again. The
     * column simply comes back unmapped, which is the state that asks the operator to decide.
     */
    @Transactional(readOnly = true)
    public Map<String, String> loadProfile(UUID organizationId, UUID layerId, String profileName) {
        Map<UUID, String> activeById = new LinkedHashMap<>();
        for (AttributeDefinition definition : metadataService.definitionsForLayer(organizationId, layerId)) {
            activeById.put(definition.id(), definition.fieldName());
        }
        Map<String, String> mapping = new LinkedHashMap<>();
        for (ImportFieldMapping row : mappingRepository
                .findByOrganizationIdAndLayerIdAndProfileName(organizationId, layerId, profileName)) {
            String fieldName = row.getAttributeId() == null ? IGNORE : activeById.get(row.getAttributeId());
            mapping.put(row.getSourceField(), fieldName == null ? IGNORE : fieldName);
        }
        return mapping;
    }

    /**
     * Saves a profile, replacing any previous version wholesale.
     *
     * <p>Replace rather than merge: a column dropped from this quarter's deliverable must disappear
     * from the profile. A merge would keep mapping a column that is no longer in the file, which
     * looks like a working mapping right up until someone asks why a field is always empty.
     */
    @Transactional
    public void saveProfile(UUID organizationId, UUID actorId, String actorName, UUID layerId,
                            String profileName, Map<String, String> mapping) {
        var layer = metadataService.requireLayer(layerId, organizationId);
        String name = profileName == null ? "" : profileName.trim();
        if (name.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A profile name is required.");
        }

        Map<String, UUID> idsByFieldName = new LinkedHashMap<>();
        metadataService.definitionsForLayer(organizationId, layerId)
                .forEach(definition -> idsByFieldName.put(definition.fieldName(), definition.id()));

        mappingRepository.deleteByOrganizationIdAndLayerIdAndProfileName(organizationId, layerId, name);

        int mapped = 0;
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String target = entry.getValue();
            UUID attributeId = target == null || target.isBlank() || IGNORE.equals(target)
                    ? null
                    : idsByFieldName.get(target);
            if (attributeId == null && target != null && !target.isBlank() && !IGNORE.equals(target)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "'" + target + "' is not an active attribute on " + layer.getTitle() + ".");
            }
            ImportFieldMapping row = new ImportFieldMapping();
            row.setOrganizationId(organizationId);
            row.setLayerId(layerId);
            row.setProfileName(name);
            row.setSourceField(entry.getKey());
            row.setAttributeId(attributeId);
            mappingRepository.save(row);
            if (attributeId != null) {
                mapped++;
            }
        }

        auditService.record(AuditEvent.builder()
                .organizationId(organizationId)
                .actorUserId(actorId)
                .actorUsername(actorName)
                .eventType(AuditEventTypes.IMPORT_MAPPING_PROFILE_SAVED)
                .category(AuditCategory.CONFIGURATION)
                .resourceType("gis.import_field_mapping")
                .resourceId(layerId.toString())
                .success(true)
                .message("Saved import mapping profile '" + name + "' for " + layer.getTitle())
                .metadata(Map.of("profileName", name, "columns", mapping.size(), "mapped", mapped))
                .build());
        log.info("Saved import mapping profile '{}' ({} of {} columns mapped) for layer {}",
                name, mapped, mapping.size(), layer.getCode());
    }
}
