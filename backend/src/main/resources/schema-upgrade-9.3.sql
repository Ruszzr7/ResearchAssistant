-- Mission 9.3：为已执行 9.2 质量事件表的数据库增加运行链路和终态字段。
ALTER TABLE ai_quality_event
    ADD COLUMN run_id VARCHAR(36) NULL,
    ADD COLUMN parent_event_id BIGINT NULL,
    ADD COLUMN final_status VARCHAR(20) NULL;

UPDATE ai_quality_event
SET run_id = CONCAT('legacy-', id)
WHERE run_id IS NULL;

UPDATE ai_quality_event
SET final_status = CASE status
    WHEN 'PASS' THEN 'PASS'
    WHEN 'REPAIRED' THEN 'REPAIRED'
    WHEN 'FALLBACK' THEN 'FALLBACK'
    WHEN 'FAILED' THEN 'REJECTED'
    ELSE NULL
END
WHERE final_status IS NULL;

ALTER TABLE ai_quality_event
    MODIFY COLUMN run_id VARCHAR(36) NOT NULL,
    ADD INDEX idx_ai_quality_run (run_id, created_at),
    ADD INDEX idx_ai_quality_stage_created (stage, created_at),
    ADD INDEX idx_ai_quality_final_status_created (final_status, created_at);
