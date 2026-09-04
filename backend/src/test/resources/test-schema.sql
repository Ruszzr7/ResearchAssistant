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
    formulas_json TEXT,
    figures_json TEXT,
    raw_text TEXT,
    grounded_report TEXT,
    grounded_evidence_ids_json TEXT,
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

CREATE TABLE paper_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id BIGINT NOT NULL,
    document_hash CHAR(64) NOT NULL,
    layout_parser_version VARCHAR(128) NOT NULL,
    schema_version VARCHAR(48) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'STRUCTURED',
    structure_json CLOB NOT NULL,
    chunk_summaries_json CLOB,
    profile_json CLOB,
    layout_recovery_json CLOB,
    memory_quality_json CLOB,
    profile_quality_json CLOB,
    understanding_version VARCHAR(64),
    stage_text VARCHAR(255),
    revision INT NOT NULL DEFAULT 1,
    total_chunks INT NOT NULL DEFAULT 0,
    completed_chunks INT NOT NULL DEFAULT 0,
    failed_chunks INT NOT NULL DEFAULT 0,
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    understanding_attempt_count INT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64),
    understanding_started_at TIMESTAMP,
    understanding_completed_at TIMESTAMP,
    generated_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (paper_id, document_hash, layout_parser_version, schema_version),
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

CREATE TABLE paper_annotation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    page INT,
    color VARCHAR(16),
    note TEXT,
    coordinates_json TEXT NOT NULL,
    ai_generated BOOLEAN DEFAULT FALSE,
    agent_tool_call_id VARCHAR(36),
    document_hash CHAR(64),
    source_object_id VARCHAR(160),
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    completed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
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

CREATE TABLE research_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_key VARCHAR(64) NOT NULL UNIQUE,
    title VARCHAR(255) NOT NULL,
    session_type VARCHAR(16) NOT NULL DEFAULT 'SINGLE',
    primary_paper_id BIGINT,
    last_page INT NOT NULL DEFAULT 1,
    mode VARCHAR(48) NOT NULL DEFAULT 'analysis',
    output_language VARCHAR(8) NOT NULL DEFAULT 'ZH',
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    last_activity_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (primary_paper_id) REFERENCES paper(id) ON DELETE SET NULL
);

CREATE TABLE research_session_paper (
    session_id BIGINT NOT NULL,
    paper_id BIGINT NOT NULL,
    position_no INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, paper_id),
    FOREIGN KEY (session_id) REFERENCES research_session(id) ON DELETE CASCADE,
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE
);

CREATE TABLE research_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    message_key VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL,
    message_type VARCHAR(24) NOT NULL DEFAULT 'CHAT',
    message_status VARCHAR(24) NOT NULL DEFAULT 'FINAL',
    content TEXT NOT NULL,
    run_id VARCHAR(36),
    agent_turn_id BIGINT,
    selection_anchor_json TEXT,
    evidence_json TEXT,
    evidence_schema_version VARCHAR(32),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(session_id, message_key),
    FOREIGN KEY (session_id) REFERENCES research_session(id) ON DELETE CASCADE
);

CREATE TABLE agent_turn (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    turn_id VARCHAR(36) NOT NULL UNIQUE,
    session_id BIGINT NOT NULL,
    client_request_id VARCHAR(100) NOT NULL,
    sequence_no BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    initial_message_key VARCHAR(100),
    final_message_key VARCHAR(100),
    error_code VARCHAR(64),
    error_message VARCHAR(1000),
    version INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(session_id, client_request_id),
    UNIQUE(session_id, sequence_no),
    FOREIGN KEY (session_id) REFERENCES research_session(id) ON DELETE CASCADE
);

CREATE TABLE agent_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL UNIQUE,
    turn_id BIGINT NOT NULL,
    attempt_no INT NOT NULL DEFAULT 1,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    model_config_version VARCHAR(96) NOT NULL,
    model_capability_signature CHAR(64) NOT NULL,
    model_snapshot_json TEXT NOT NULL,
    context_schema_version VARCHAR(64) NOT NULL,
    context_snapshot_json TEXT NOT NULL,
    document_hash CHAR(64),
    parser_version VARCHAR(128),
    max_model_calls INT NOT NULL,
    max_tool_calls INT NOT NULL,
    token_budget INT NOT NULL,
    timeout_ms BIGINT NOT NULL,
    model_calls INT NOT NULL DEFAULT 0,
    tool_calls INT NOT NULL DEFAULT 0,
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    model_trace_json TEXT,
    result_json TEXT,
    error_code VARCHAR(64),
    error_message VARCHAR(1000),
    version INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(turn_id, attempt_no),
    FOREIGN KEY (turn_id) REFERENCES agent_turn(id) ON DELETE CASCADE
);

CREATE TABLE agent_tool_call (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tool_call_id VARCHAR(36) NOT NULL UNIQUE,
    run_id VARCHAR(36) NOT NULL,
    ordinal_no INT NOT NULL,
    tool_name VARCHAR(96) NOT NULL,
    arguments_json TEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'REQUESTED',
    read_only BOOLEAN NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    attempt_count INT NOT NULL DEFAULT 0,
    action_ticket_hash CHAR(64),
    action_ticket_expires_at TIMESTAMP,
    result_json TEXT,
    error_code VARCHAR(64),
    error_message VARCHAR(1000),
    version INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(run_id, ordinal_no),
    FOREIGN KEY (run_id) REFERENCES agent_run(run_id) ON DELETE CASCADE
);

CREATE TABLE agent_attachment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    attachment_id VARCHAR(36) NOT NULL UNIQUE,
    turn_id BIGINT,
    session_id BIGINT NOT NULL,
    message_id BIGINT,
    attachment_kind VARCHAR(32) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    original_name VARCHAR(255),
    storage_path VARCHAR(1000),
    content_sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    extraction_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    preview_text TEXT,
    metadata_json TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (turn_id) REFERENCES agent_turn(id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES research_session(id) ON DELETE CASCADE,
    FOREIGN KEY (message_id) REFERENCES research_message(id) ON DELETE SET NULL
);

CREATE TABLE agent_conversation_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    revision INT NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    covered_through_message_id BIGINT,
    summary_json TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(session_id, revision),
    FOREIGN KEY (session_id) REFERENCES research_session(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS ai_model_capability (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    model_role VARCHAR(24) NOT NULL,
    config_signature CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    chat_supported BOOLEAN NOT NULL DEFAULT FALSE,
    tool_calling_supported BOOLEAN NOT NULL DEFAULT FALSE,
    continuous_tools_supported BOOLEAN NOT NULL DEFAULT FALSE,
    structured_supported BOOLEAN NOT NULL DEFAULT FALSE,
    image_supported BOOLEAN NOT NULL DEFAULT FALSE,
    pdf_supported BOOLEAN NOT NULL DEFAULT FALSE,
    error_code VARCHAR(64), error_message VARCHAR(1000),
    verified_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_capability_role_signature UNIQUE(model_role, config_signature)
);
