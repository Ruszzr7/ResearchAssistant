-- P2-B: bind persisted whole-paper reports to their workbench run and layout version.
ALTER TABLE paper_analysis
    ADD COLUMN grounded_report MEDIUMTEXT NULL AFTER raw_text,
    ADD COLUMN grounded_evidence_ids_json MEDIUMTEXT NULL AFTER grounded_report,
    ADD COLUMN workbench_run_id VARCHAR(36) NULL AFTER grounded_evidence_ids_json,
    ADD COLUMN layout_document_hash CHAR(64) NULL AFTER workbench_run_id,
    ADD COLUMN layout_parser_version VARCHAR(96) NULL AFTER layout_document_hash,
    ADD INDEX idx_paper_analysis_workbench_run (workbench_run_id);
