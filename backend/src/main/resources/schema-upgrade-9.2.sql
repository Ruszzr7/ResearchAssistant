-- Mission 9.2-B：为已有数据库增加 AI 质量事件表。
CREATE TABLE IF NOT EXISTS ai_quality_event (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id               BIGINT       NULL,
    task_type              VARCHAR(64)  NOT NULL,
    stage                  VARCHAR(32)  NOT NULL,
    prompt_version         VARCHAR(64)  NOT NULL,
    model_name             VARCHAR(128) NULL,
    status                 VARCHAR(20)  NOT NULL,
    repaired               TINYINT(1)   NOT NULL DEFAULT 0,
    retry_count            INT          NOT NULL DEFAULT 0,
    validation_errors_json TEXT         NULL,
    error_message          TEXT         NULL,
    prompt_tokens          INT          NOT NULL DEFAULT 0,
    completion_tokens      INT          NOT NULL DEFAULT 0,
    total_tokens           INT          NOT NULL DEFAULT 0,
    latency_ms             BIGINT       NOT NULL DEFAULT 0,
    created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ai_quality_created_at (created_at),
    INDEX idx_ai_quality_paper (paper_id),
    INDEX idx_ai_quality_status_created (status, created_at),
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
