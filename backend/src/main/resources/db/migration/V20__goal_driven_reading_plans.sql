-- Goal-driven reading: plans define an objective and every paper can produce a
-- durable outcome linked to the research session where it was developed.
ALTER TABLE reading_plan
    ADD COLUMN objective TEXT NULL AFTER name,
    ADD COLUMN success_criteria TEXT NULL AFTER objective;

UPDATE reading_plan SET objective = name WHERE objective IS NULL OR TRIM(objective) = '';

ALTER TABLE reading_plan
    MODIFY COLUMN objective TEXT NOT NULL;

ALTER TABLE reading_plan_item
    ADD COLUMN reading_question TEXT NULL AFTER paper_id,
    ADD COLUMN expected_output VARCHAR(48) NOT NULL DEFAULT 'SUMMARY' AFTER reading_question,
    ADD COLUMN outcome MEDIUMTEXT NULL AFTER notes,
    ADD COLUMN research_session_id BIGINT NULL AFTER outcome,
    ADD COLUMN completed_at DATETIME(6) NULL AFTER research_session_id,
    ADD INDEX idx_reading_item_session (research_session_id),
    ADD CONSTRAINT fk_reading_item_session FOREIGN KEY (research_session_id)
        REFERENCES research_session(id) ON DELETE SET NULL;

-- Preserve genuine legacy notes as outcomes; a legacy DONE item without any
-- recorded result returns to IN_PROGRESS instead of pretending to be complete.
UPDATE reading_plan_item
SET outcome = notes
WHERE status = 'DONE' AND (outcome IS NULL OR TRIM(outcome) = '')
  AND notes IS NOT NULL AND TRIM(notes) <> '';

UPDATE reading_plan_item
SET status = 'IN_PROGRESS'
WHERE status = 'DONE' AND (outcome IS NULL OR TRIM(outcome) = '');

UPDATE reading_plan_item
SET completed_at = updated_at
WHERE status = 'DONE' AND completed_at IS NULL;
