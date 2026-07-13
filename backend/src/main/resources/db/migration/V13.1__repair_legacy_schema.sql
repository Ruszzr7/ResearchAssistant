-- Mission 13.1: repair databases that were baselined before the complete
-- Mission 12 schema was present. This migration is additive and keeps data.

CREATE TABLE IF NOT EXISTS ai_quality_event (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id               BIGINT       NULL,
    run_id                 VARCHAR(36)  NOT NULL,
    parent_event_id        BIGINT       NULL,
    task_type              VARCHAR(64)  NOT NULL,
    stage                  VARCHAR(32)  NOT NULL,
    prompt_version         VARCHAR(64)  NOT NULL,
    model_name             VARCHAR(128) NULL,
    status                 VARCHAR(20)  NOT NULL,
    final_status           VARCHAR(20)  NULL,
    repaired               TINYINT(1)   NOT NULL DEFAULT 0,
    retry_count            INT          NOT NULL DEFAULT 0,
    validation_errors_json TEXT         NULL,
    error_message          TEXT         NULL,
    prompt_tokens          INT          NOT NULL DEFAULT 0,
    completion_tokens      INT          NOT NULL DEFAULT 0,
    total_tokens           INT          NOT NULL DEFAULT 0,
    latency_ms             BIGINT       NOT NULL DEFAULT 0,
    created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ai_quality_created_at (created_at),
    INDEX idx_ai_quality_run (run_id, created_at),
    INDEX idx_ai_quality_paper (paper_id),
    INDEX idx_ai_quality_stage_created (stage, created_at),
    INDEX idx_ai_quality_final_status_created (final_status, created_at),
    INDEX idx_ai_quality_status_created (status, created_at),
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rag_index_state (
    paper_id      BIGINT      NOT NULL PRIMARY KEY,
    active_version INT         DEFAULT NULL,
    next_version  INT         NOT NULL DEFAULT 0,
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rag_index_version (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id      BIGINT       NOT NULL,
    version_no    INT          NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    chunk_count   INT          NOT NULL DEFAULT 0,
    error         VARCHAR(1000) DEFAULT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at  DATETIME     DEFAULT NULL,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rag_paper_version (paper_id, version_no),
    KEY idx_rag_active (paper_id, status),
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workflow_step (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id       VARCHAR(64)  NOT NULL,
    step_index    INT          NOT NULL,
    step_name     VARCHAR(128) NOT NULL,
    skill_name    VARCHAR(64)  NOT NULL,
    input_json    TEXT,
    output_json   TEXT,
    status        VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    error         TEXT,
    started_at    DATETIME     DEFAULT NULL,
    completed_at  DATETIME     DEFAULT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_step (task_id, step_index),
    KEY idx_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- MySQL 8.0.28 does not support ADD COLUMN IF NOT EXISTS. Use metadata
-- checks so this migration also works for a database where some columns exist.
DROP PROCEDURE IF EXISTS ra_m13_add_column;
DELIMITER $$
CREATE PROCEDURE ra_m13_add_column(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_definition VARCHAR(512)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND column_name = p_column
    ) THEN
        SET @ra_m13_ddl = CONCAT(
            'ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition
        );
        PREPARE ra_m13_stmt FROM @ra_m13_ddl;
        EXECUTE ra_m13_stmt;
        DEALLOCATE PREPARE ra_m13_stmt;
    END IF;
END$$
DELIMITER ;

CALL ra_m13_add_column('async_task', 'workflow_type', 'VARCHAR(64) NULL');
CALL ra_m13_add_column('async_task', 'context_json', 'MEDIUMTEXT NULL');
CALL ra_m13_add_column('async_task', 'title', 'VARCHAR(255) NULL');
CALL ra_m13_add_column('async_task', 'task_type', 'VARCHAR(64) NULL');
CALL ra_m13_add_column('async_task', 'failure_code', 'VARCHAR(64) NULL');
CALL ra_m13_add_column('async_task', 'attempt_count', 'INT NOT NULL DEFAULT 0');
CALL ra_m13_add_column('async_task', 'max_attempts', 'INT NOT NULL DEFAULT 3');
CALL ra_m13_add_column('async_task', 'next_run_at', 'DATETIME NULL');
CALL ra_m13_add_column('async_task', 'lease_owner', 'VARCHAR(128) NULL');
CALL ra_m13_add_column('async_task', 'lease_until', 'DATETIME NULL');
CALL ra_m13_add_column('async_task', 'last_heartbeat_at', 'DATETIME NULL');
CALL ra_m13_add_column('async_task', 'idempotency_key', 'VARCHAR(128) NULL');
CALL ra_m13_add_column('async_task', 'request_hash', 'CHAR(64) NULL');

CALL ra_m13_add_column('paper_analysis', 'reproducible_artifacts_json', 'MEDIUMTEXT NULL');
CALL ra_m13_add_column('paper_analysis', 'experiment_setup_json', 'TEXT NULL');
CALL ra_m13_add_column('paper_analysis', 'benchmark_results_json', 'MEDIUMTEXT NULL');
CALL ra_m13_add_column('paper_analysis', 'formulas_json', 'MEDIUMTEXT NULL');
CALL ra_m13_add_column('paper_analysis', 'figures_json', 'MEDIUMTEXT NULL');
CALL ra_m13_add_column('paper_analysis', 'relevance_score', 'INT DEFAULT NULL');
CALL ra_m13_add_column('paper_analysis', 'relevance_reason', 'TEXT NULL');

-- Add new chunk metadata as nullable first, backfill existing rows, then make
-- the identity fields required for newly indexed chunks.
CALL ra_m13_add_column('paper_chunk', 'index_version', 'INT NULL');
CALL ra_m13_add_column('paper_chunk', 'chunk_key', 'VARCHAR(160) NULL');
CALL ra_m13_add_column('paper_chunk', 'source_type', 'VARCHAR(32) NULL');
CALL ra_m13_add_column('paper_chunk', 'chunk_order', 'INT NULL');
CALL ra_m13_add_column('paper_chunk', 'page_start', 'INT NULL');
CALL ra_m13_add_column('paper_chunk', 'page_end', 'INT NULL');
CALL ra_m13_add_column('paper_chunk', 'char_start', 'INT NULL');
CALL ra_m13_add_column('paper_chunk', 'char_end', 'INT NULL');
CALL ra_m13_add_column('paper_chunk', 'content_hash', 'CHAR(64) NULL');

UPDATE paper_chunk
SET index_version = 1
WHERE index_version IS NULL;

UPDATE paper_chunk
SET chunk_key = CONCAT('legacy-', id)
WHERE chunk_key IS NULL;

UPDATE paper_chunk
SET source_type = 'PDF_TEXT'
WHERE source_type IS NULL;

UPDATE paper_chunk
SET chunk_order = id
WHERE chunk_order IS NULL;

UPDATE paper_chunk
SET content_hash = SHA2(COALESCE(content, ''), 256)
WHERE content_hash IS NULL;

ALTER TABLE paper_chunk
    MODIFY COLUMN index_version INT NOT NULL DEFAULT 1,
    MODIFY COLUMN chunk_key VARCHAR(160) NOT NULL,
    MODIFY COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'PDF_TEXT',
    MODIFY COLUMN chunk_order INT NOT NULL DEFAULT 0,
    MODIFY COLUMN content_hash CHAR(64) NOT NULL;

ALTER TABLE async_task
    MODIFY COLUMN status VARCHAR(24) NOT NULL;

DROP PROCEDURE IF EXISTS ra_m13_add_index;
DELIMITER $$
CREATE PROCEDURE ra_m13_add_index(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_definition VARCHAR(512)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND index_name = p_index
    ) THEN
        SET @ra_m13_ddl = CONCAT('ALTER TABLE `', p_table, '` ADD ', p_definition);
        PREPARE ra_m13_stmt FROM @ra_m13_ddl;
        EXECUTE ra_m13_stmt;
        DEALLOCATE PREPARE ra_m13_stmt;
    END IF;
END$$
DELIMITER ;

CALL ra_m13_add_index('async_task', 'idx_status_next_run',
                      'INDEX `idx_status_next_run` (`status`, `next_run_at`)');
CALL ra_m13_add_index('async_task', 'idx_lease_until',
                      'INDEX `idx_lease_until` (`status`, `lease_until`)');
CALL ra_m13_add_index('async_task', 'uk_async_idempotency',
                      'UNIQUE INDEX `uk_async_idempotency` (`idempotency_key`)');
CALL ra_m13_add_index('paper_chunk', 'uk_paper_chunk_key',
                      'UNIQUE INDEX `uk_paper_chunk_key` (`paper_id`, `index_version`, `chunk_key`)');

DROP PROCEDURE IF EXISTS ra_m13_add_column;
DROP PROCEDURE IF EXISTS ra_m13_add_index;
