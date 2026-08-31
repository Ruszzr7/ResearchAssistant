ALTER TABLE agent_attachment
    MODIFY COLUMN turn_id BIGINT NULL,
    ADD COLUMN session_id BIGINT NULL AFTER turn_id,
    ADD INDEX idx_agent_attachment_session (session_id, created_at),
    ADD CONSTRAINT fk_agent_attachment_session FOREIGN KEY (session_id)
        REFERENCES research_session(id) ON DELETE CASCADE;

UPDATE agent_attachment a
JOIN agent_turn t ON t.id = a.turn_id
SET a.session_id = t.session_id
WHERE a.session_id IS NULL;

ALTER TABLE agent_attachment MODIFY COLUMN session_id BIGINT NOT NULL;
