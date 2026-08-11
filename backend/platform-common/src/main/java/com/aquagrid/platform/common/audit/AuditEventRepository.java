package com.aquagrid.platform.common.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Write path for the audit trail. Query and export are added by Module 30; deliberately no delete
 * or update methods are exposed here.
 */
@Repository
public interface AuditEventRepository extends JpaRepository<AuditEventEntity, Long> {
}
