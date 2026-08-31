-- The unified Agent runtime owns research turns from this version onward.
-- Old Workbench rows and observation memory were test data and are intentionally not migrated.
DROP TABLE IF EXISTS paper_workbench_step;
DROP TABLE IF EXISTS paper_conversation_turn;
DROP TABLE IF EXISTS paper_memory_observation;
DROP TABLE IF EXISTS paper_workbench_run;

ALTER TABLE paper_analysis
    DROP INDEX idx_paper_analysis_workbench_run,
    DROP COLUMN workbench_run_id;
