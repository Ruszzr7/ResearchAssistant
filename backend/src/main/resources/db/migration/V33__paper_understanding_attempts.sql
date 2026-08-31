-- Bounded paper-understanding retries before the explicit source-reading fallback is opened.
ALTER TABLE paper_memory
    ADD COLUMN understanding_attempt_count INT NOT NULL DEFAULT 0 AFTER completion_tokens;
