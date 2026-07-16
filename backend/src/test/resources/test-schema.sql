CREATE TABLE settings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    key_name VARCHAR(100) NOT NULL UNIQUE,
    value VARCHAR(2000) NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE folder (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    parent_id BIGINT,
    sort_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tag (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE paper (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    authors TEXT,
    year INT,
    source VARCHAR(300),
    doi VARCHAR(200) UNIQUE,
    arxiv_id VARCHAR(100),
    semantic_scholar_id VARCHAR(100),
    source_url VARCHAR(1000),
    citation_count INT DEFAULT 0,
    `abstract` TEXT,
    keywords VARCHAR(500),
    pdf_path VARCHAR(500),
    acquisition_method VARCHAR(30),
    folder_id BIGINT,
    reading_status VARCHAR(20) DEFAULT 'UNREAD',
    pinned BOOLEAN DEFAULT FALSE,
    page_count INT,
    current_page INT DEFAULT 0,
    read_seconds INT DEFAULT 0,
    last_read_at TIMESTAMP,
    ai_summary TEXT,
    processing_status VARCHAR(20) DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE paper_analysis (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id BIGINT NOT NULL UNIQUE,
    sections_json TEXT,
    core_contribution TEXT,
    method_type VARCHAR(50),
    method_summary TEXT,
    datasets_json TEXT,
    models_json TEXT,
    key_findings_json TEXT,
    limitations_json TEXT,
    tables_summary_json TEXT,
    figures_summary_json TEXT,
    reproducible_artifacts_json TEXT,
    experiment_setup_json TEXT,
    benchmark_results_json TEXT,
    relevance_score INT,
    relevance_reason TEXT,
    formulas_json TEXT,
    figures_json TEXT,
    raw_text TEXT,
    grounded_report TEXT,
    grounded_evidence_ids_json TEXT,
    workbench_run_id VARCHAR(36),
    layout_document_hash CHAR(64),
    layout_parser_version VARCHAR(96),
    token_used INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE
);

CREATE TABLE paper_tag (
    paper_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    PRIMARY KEY (paper_id, tag_id)
);

CREATE TABLE paper_chunk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id BIGINT NOT NULL,
    index_version INT NOT NULL DEFAULT 1,
    chunk_key VARCHAR(160) NOT NULL,
    source_type VARCHAR(32) NOT NULL DEFAULT 'PDF_TEXT',
    chunk_order INT NOT NULL DEFAULT 0,
    page_start INT,
    page_end INT,
    char_start INT,
    char_end INT,
    content_hash CHAR(64) NOT NULL,
    chunk_type VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    embedding_json TEXT NOT NULL,
    source VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (paper_id, index_version, chunk_key)
);

CREATE TABLE paper_layout_artifact (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id BIGINT NOT NULL,
    document_hash CHAR(64) NOT NULL,
    parser_version VARCHAR(96) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'READY',
    layout_confidence DOUBLE NOT NULL DEFAULT 0,
    page_count INT NOT NULL DEFAULT 0,
    blocks_json CLOB NOT NULL,
    provenance_json CLOB,
    generated_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (paper_id, document_hash, parser_version),
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE
);

CREATE TABLE paper_formula_region (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id BIGINT NOT NULL,
    document_hash CHAR(64) NOT NULL,
    parser_version VARCHAR(128) NOT NULL,
    page_number INT NOT NULL,
    region_key CHAR(64) NOT NULL,
    box_x DOUBLE NOT NULL,
    box_y DOUBLE NOT NULL,
    box_width DOUBLE NOT NULL,
    box_height DOUBLE NOT NULL,
    latex CLOB,
    confidence DOUBLE NOT NULL DEFAULT 0,
    source VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (paper_id, document_hash, parser_version, page_number, region_key),
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE
);

CREATE TABLE rag_index_state (
    paper_id BIGINT PRIMARY KEY,
    active_version INT,
    next_version INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE rag_index_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    chunk_count INT NOT NULL DEFAULT 0,
    error VARCHAR(1000),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    activated_at TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (paper_id, version_no)
);

CREATE TABLE note (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(255),
    content TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE paper_note_link (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id BIGINT NOT NULL,
    note_id BIGINT NOT NULL,
    page INT,
    coordinates_json TEXT,
    anchor_text TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE reading_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    start_date DATE,
    end_date DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE reading_plan_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    paper_id BIGINT NOT NULL,
    deadline DATE,
    priority INT DEFAULT 0,
    status VARCHAR(32) DEFAULT 'TODO',
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (plan_id) REFERENCES reading_plan(id) ON DELETE CASCADE
);

CREATE TABLE writing_project (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    topic VARCHAR(500),
    outline_json TEXT,
    related_work TEXT,
    draft_content TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE writing_project_paper (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    paper_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE ai_quality_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id BIGINT,
    run_id VARCHAR(36) NOT NULL,
    parent_event_id BIGINT,
    task_type VARCHAR(64) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    model_name VARCHAR(128),
    status VARCHAR(20) NOT NULL,
    final_status VARCHAR(20),
    repaired BOOLEAN NOT NULL DEFAULT FALSE,
    retry_count INT NOT NULL DEFAULT 0,
    validation_errors_json TEXT,
    error_message TEXT,
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    total_tokens INT NOT NULL DEFAULT 0,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE async_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL UNIQUE,
    status VARCHAR(24) NOT NULL,
    task_type VARCHAR(64),
    workflow_type VARCHAR(64),
    context_json TEXT,
    title VARCHAR(255),
    stage_text VARCHAR(255),
    result_json TEXT,
    error TEXT,
    failure_code VARCHAR(64),
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    next_run_at TIMESTAMP,
    lease_owner VARCHAR(128),
    lease_until TIMESTAMP,
    last_heartbeat_at TIMESTAMP,
    idempotency_key VARCHAR(128),
    request_hash CHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE workflow_step (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    step_index INT NOT NULL,
    step_name VARCHAR(128) NOT NULL,
    skill_name VARCHAR(64) NOT NULL,
    input_json TEXT,
    output_json TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    error TEXT,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(task_id, step_index)
);

CREATE TABLE paper_workbench_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL UNIQUE,
    task_id VARCHAR(36),
    workflow VARCHAR(48) NOT NULL,
    scope VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PLANNED',
    paper_ids_json TEXT NOT NULL,
    request_json TEXT NOT NULL,
    plan_json TEXT NOT NULL,
    artifact_versions_json TEXT NOT NULL,
    evidence_required BOOLEAN NOT NULL DEFAULT TRUE,
    max_steps INT NOT NULL,
    token_budget INT NOT NULL,
    repair_count INT NOT NULL DEFAULT 0,
    evidence_count INT NOT NULL DEFAULT 0,
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    total_tokens INT NOT NULL DEFAULT 0,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    result_json TEXT,
    error_code VARCHAR(64),
    error_message VARCHAR(1000),
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE paper_workbench_step (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    step_index INT NOT NULL,
    step_name VARCHAR(128) NOT NULL,
    skill_name VARCHAR(96) NOT NULL,
    step_kind VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    evidence_count INT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    total_tokens INT NOT NULL DEFAULT 0,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    input_summary_json TEXT,
    output_summary_json TEXT,
    error_code VARCHAR(64),
    error_message VARCHAR(1000),
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(run_id, step_index),
    FOREIGN KEY (run_id) REFERENCES paper_workbench_run(run_id) ON DELETE CASCADE
);

CREATE TABLE rag_consistency_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id BIGINT,
    provider VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    active_version INT,
    metadata_chunk_count INT NOT NULL DEFAULT 0,
    expected_chunk_count INT,
    details_json TEXT,
    checked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
