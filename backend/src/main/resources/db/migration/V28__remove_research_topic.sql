DELETE FROM settings WHERE key_name = 'research_topic';

ALTER TABLE paper_analysis
    DROP COLUMN relevance_score,
    DROP COLUMN relevance_reason;
