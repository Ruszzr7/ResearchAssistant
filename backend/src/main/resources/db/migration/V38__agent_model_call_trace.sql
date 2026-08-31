ALTER TABLE agent_run
    ADD COLUMN model_trace_json MEDIUMTEXT NULL AFTER completion_tokens;
