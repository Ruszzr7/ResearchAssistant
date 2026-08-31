-- Agent runtime: durable logical turns, model runs, tool calls, attachments and summaries.
CREATE TABLE agent_turn (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    turn_id             VARCHAR(36)   NOT NULL,
    session_id          BIGINT        NOT NULL,
    client_request_id   VARCHAR(100)  NOT NULL,
    sequence_no         BIGINT        NOT NULL,
    status              VARCHAR(24)   NOT NULL DEFAULT 'QUEUED',
    initial_message_key VARCHAR(100)  NULL,
    final_message_key   VARCHAR(100)  NULL,
    error_code          VARCHAR(64)   NULL,
    error_message       VARCHAR(1000) NULL,
    version             INT           NOT NULL DEFAULT 0,
    started_at          DATETIME(6)   NULL,
    completed_at        DATETIME(6)   NULL,
    created_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_agent_turn_id (turn_id),
    UNIQUE KEY uk_agent_turn_request (session_id, client_request_id),
    UNIQUE KEY uk_agent_turn_sequence (session_id, sequence_no),
    INDEX idx_agent_turn_active (session_id, status, updated_at),
    CONSTRAINT fk_agent_turn_session FOREIGN KEY (session_id)
        REFERENCES research_session(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_run (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id                   VARCHAR(36)   NOT NULL,
    turn_id                  BIGINT        NOT NULL,
    attempt_no               INT           NOT NULL DEFAULT 1,
    status                   VARCHAR(24)   NOT NULL DEFAULT 'QUEUED',
    model_config_version     VARCHAR(96)   NOT NULL,
    model_capability_signature CHAR(64)    NOT NULL,
    model_snapshot_json      TEXT          NOT NULL,
    context_schema_version   VARCHAR(64)   NOT NULL,
    context_snapshot_json    MEDIUMTEXT    NOT NULL,
    document_hash            CHAR(64)      NULL,
    parser_version           VARCHAR(128)  NULL,
    max_model_calls          INT           NOT NULL,
    max_tool_calls           INT           NOT NULL,
    token_budget             INT           NOT NULL,
    timeout_ms               BIGINT        NOT NULL,
    model_calls              INT           NOT NULL DEFAULT 0,
    tool_calls               INT           NOT NULL DEFAULT 0,
    prompt_tokens            INT           NOT NULL DEFAULT 0,
    completion_tokens        INT           NOT NULL DEFAULT 0,
    result_json              MEDIUMTEXT    NULL,
    error_code               VARCHAR(64)   NULL,
    error_message            VARCHAR(1000) NULL,
    version                  INT           NOT NULL DEFAULT 0,
    started_at               DATETIME(6)   NULL,
    completed_at             DATETIME(6)   NULL,
    created_at               DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_agent_run_id (run_id),
    UNIQUE KEY uk_agent_run_attempt (turn_id, attempt_no),
    INDEX idx_agent_run_status (status, updated_at),
    CONSTRAINT fk_agent_run_turn FOREIGN KEY (turn_id)
        REFERENCES agent_turn(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_tool_call (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    tool_call_id         VARCHAR(36)   NOT NULL,
    run_id               VARCHAR(36)   NOT NULL,
    ordinal_no           INT           NOT NULL,
    tool_name            VARCHAR(96)   NOT NULL,
    arguments_json       MEDIUMTEXT    NOT NULL,
    status               VARCHAR(24)   NOT NULL DEFAULT 'REQUESTED',
    read_only            BOOLEAN       NOT NULL,
    idempotency_key      VARCHAR(128)  NOT NULL,
    attempt_count        INT           NOT NULL DEFAULT 0,
    action_ticket_hash   CHAR(64)      NULL,
    action_ticket_expires_at DATETIME(6) NULL,
    result_json          MEDIUMTEXT    NULL,
    error_code           VARCHAR(64)   NULL,
    error_message        VARCHAR(1000) NULL,
    version              INT           NOT NULL DEFAULT 0,
    started_at           DATETIME(6)   NULL,
    completed_at         DATETIME(6)   NULL,
    created_at           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_agent_tool_call_id (tool_call_id),
    UNIQUE KEY uk_agent_tool_idempotency (idempotency_key),
    UNIQUE KEY uk_agent_tool_ordinal (run_id, ordinal_no),
    INDEX idx_agent_tool_status (run_id, status, updated_at),
    CONSTRAINT fk_agent_tool_run FOREIGN KEY (run_id)
        REFERENCES agent_run(run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_attachment (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    attachment_id     VARCHAR(36)   NOT NULL,
    turn_id           BIGINT        NOT NULL,
    message_id        BIGINT        NULL,
    attachment_kind   VARCHAR(32)   NOT NULL,
    media_type        VARCHAR(128)  NOT NULL,
    original_name     VARCHAR(255)  NULL,
    storage_path      VARCHAR(1000) NULL,
    content_sha256    CHAR(64)      NOT NULL,
    size_bytes        BIGINT        NOT NULL,
    extraction_status VARCHAR(24)   NOT NULL DEFAULT 'PENDING',
    preview_text      TEXT          NULL,
    metadata_json     TEXT          NULL,
    created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_agent_attachment_id (attachment_id),
    INDEX idx_agent_attachment_turn (turn_id, created_at),
    CONSTRAINT fk_agent_attachment_turn FOREIGN KEY (turn_id)
        REFERENCES agent_turn(id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_attachment_message FOREIGN KEY (message_id)
        REFERENCES research_message(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_conversation_summary (
    id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id                 BIGINT       NOT NULL,
    revision                   INT          NOT NULL,
    schema_version             VARCHAR(64)  NOT NULL,
    covered_through_message_id BIGINT       NULL,
    summary_json               MEDIUMTEXT   NOT NULL,
    created_at                 DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_agent_summary_revision (session_id, revision),
    INDEX idx_agent_summary_latest (session_id, revision),
    CONSTRAINT fk_agent_summary_session FOREIGN KEY (session_id)
        REFERENCES research_session(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE research_message
    ADD COLUMN agent_turn_id BIGINT NULL AFTER run_id,
    ADD COLUMN message_type VARCHAR(24) NOT NULL DEFAULT 'CHAT' AFTER role,
    ADD COLUMN message_status VARCHAR(24) NOT NULL DEFAULT 'FINAL' AFTER message_type,
    ADD COLUMN evidence_schema_version VARCHAR(32) NULL AFTER evidence_json,
    ADD INDEX idx_research_message_turn (agent_turn_id, created_at, id),
    ADD CONSTRAINT fk_research_message_turn FOREIGN KEY (agent_turn_id)
        REFERENCES agent_turn(id) ON DELETE CASCADE;

ALTER TABLE paper_annotation
    ADD COLUMN agent_tool_call_id VARCHAR(36) NULL AFTER ai_generated,
    ADD COLUMN document_hash CHAR(64) NULL AFTER agent_tool_call_id,
    ADD COLUMN source_object_id VARCHAR(160) NULL AFTER document_hash,
    ADD UNIQUE KEY uk_annotation_agent_tool_call (agent_tool_call_id),
    ADD CONSTRAINT fk_annotation_agent_tool_call FOREIGN KEY (agent_tool_call_id)
        REFERENCES agent_tool_call(tool_call_id) ON DELETE SET NULL;
