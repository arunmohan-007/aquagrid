package com.aquagrid.platform.gis.domain.model;

import com.aquagrid.platform.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One persisted bulk import run: who ran it, into what, and what it did to each row that was not
 * a plain new-row import.
 *
 * <p>{@link #rowDetails} carries only the rows worth explaining — replaced, skipped or failed. A
 * plain successful insert is fully described by {@link #importedRows} alone.
 */
@Getter
@Setter
@Entity
@Table(name = "import_run", schema = "gis")
public class ImportRun extends TenantAwareEntity {

    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_username", length = 150)
    private String actorUsername;

    @Column(name = "layer_id")
    private UUID layerId;

    @Column(name = "asset_type", nullable = false, length = 60)
    private String assetType;

    @Column(name = "file_name", length = 300)
    private String fileName;

    @Column(name = "format", nullable = false, length = 20)
    private String format;

    @Column(name = "state", nullable = false, length = 20)
    private String state = "RUNNING";

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "imported_rows", nullable = false)
    private int importedRows;

    @Column(name = "replaced_rows", nullable = false)
    private int replacedRows;

    @Column(name = "skipped_rows", nullable = false)
    private int skippedRows;

    @Column(name = "failed_rows", nullable = false)
    private int failedRows;

    @Column(name = "error_message")
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "row_details", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> rowDetails = new ArrayList<>();

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
