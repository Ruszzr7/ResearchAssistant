-- Redesign phase 6: resumable semantic understanding progress for paper memory.
ALTER TABLE paper_memory
    ADD COLUMN understanding_version VARCHAR(64)  NULL AFTER memory_quality_json,
    ADD COLUMN stage_text             VARCHAR(255) NULL AFTER understanding_version,
    ADD COLUMN total_chunks           INT          NOT NULL DEFAULT 0 AFTER revision,
    ADD COLUMN completed_chunks       INT          NOT NULL DEFAULT 0 AFTER total_chunks,
    ADD COLUMN failed_chunks          INT          NOT NULL DEFAULT 0 AFTER completed_chunks,
    ADD COLUMN prompt_tokens          INT          NOT NULL DEFAULT 0 AFTER failed_chunks,
    ADD COLUMN completion_tokens      INT          NOT NULL DEFAULT 0 AFTER prompt_tokens,
    ADD COLUMN understanding_started_at   DATETIME(6) NULL AFTER last_error_code,
    ADD COLUMN understanding_completed_at DATETIME(6) NULL AFTER understanding_started_at;

CREATE INDEX idx_memory_understanding_version
    ON paper_memory (paper_id, understanding_version, status);
