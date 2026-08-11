package com.aquagrid.platform.iot.dataconfig.application.service;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.iot.dataconfig.domain.model.MeasurementUnit;
import com.aquagrid.platform.iot.dataconfig.infrastructure.persistence.MeasurementUnitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The unit lookup, served to the client rather than duplicated in it.
 *
 * <p>The list is data because the platform has already learned this lesson twice: metric units lived
 * in a {@code switch} in the ingest service and their labels in a TypeScript map in the browser, and
 * the GIS module removed a {@code TARGET_FIELDS} array from the client for the same reason — two
 * copies of one list eventually disagree, and the disagreement surfaces as a validation error the
 * operator cannot act on because the form offered the value the server refuses.
 *
 * <p>A tenant may add its own units. It may not edit or remove the platform's: a shipped unit is a
 * code already written onto readings across every tenant, and letting one organisation redefine what
 * {@code m3} means would make the same string mean two things in one column.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnitCatalogService {

    private final MeasurementUnitRepository repository;

    /** Platform-supplied units and the tenant's own, in display order. */
    @Transactional(readOnly = true)
    public List<MeasurementUnit> list(UUID organizationId, boolean activeOnly) {
        return repository.findForTenant(organizationId, activeOnly);
    }

    /**
     * Adds a unit for this tenant.
     *
     * <p>The code is checked against the shipped list as well as the tenant's own, so a tenant
     * cannot shadow {@code bar} with its own row and end up with two entries in the picker that look
     * identical and behave differently.
     */
    @Transactional
    public MeasurementUnit create(UUID organizationId, String code, String label, String quantity,
                                  String description) {
        String normalised = require(code, "Unit code");
        if (normalised.length() > 20) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'" + normalised + "' is " + normalised.length() + " characters; a reading's unit "
                            + "column holds at most 20.");
        }
        repository.findByCodeForTenant(organizationId, normalised).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT,
                    existing.getOrganizationId() == null
                            ? "'" + normalised + "' is a standard unit and is already available."
                            : "'" + normalised + "' is already defined for this organisation.");
        });

        MeasurementUnit unit = new MeasurementUnit();
        unit.setOrganizationId(organizationId);
        unit.setCode(normalised);
        unit.setLabel(require(label, "Unit label"));
        unit.setQuantity(require(quantity, "Quantity").toUpperCase());
        unit.setDescription(description == null || description.isBlank() ? null : description.trim());
        // Behind every shipped unit, so a tenant's additions do not push the standard ones down the
        // picker. The seeded rows stop at 200.
        unit.setSortOrder(500);
        MeasurementUnit saved = repository.save(unit);
        log.info("Unit {} added for org {}", normalised, organizationId);
        return saved;
    }

    private static String require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, what + " is required.");
        }
        return value.trim();
    }
}
