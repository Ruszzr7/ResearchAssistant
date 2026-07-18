-- Server-owned context snapshots, dialogue turns and grounded long-term observations.
ALTER TABLE paper_workbench_run
    ADD COLUMN primary_paper_id BIGINT NULL AFTER research_session_id,
    ADD COLUMN conversation_id VARCHAR(64) NULL AFTER primary_paper_id,
    ADD COLUMN context_schema_version VARCHAR(64) NULL AFTER artifact_versions_json,
    ADD COLUMN context_snapshot_json MEDIUMTEXT NULL AFTER context_schema_version,
    ADD INDEX idx_workbench_conversation (primary_paper_id, conversation_id, created_at);

CREATE TABLE paper_conversation_turn (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id                BIGINT       NOT NULL,
    conversation_id         VARCHAR(64)  NOT NULL,
    source_run_id           VARCHAR(36)  NOT NULL,
    document_hash           CHAR(64)     NOT NULL,
    parser_version          VARCHAR(128) NOT NULL,
    question                VARCHAR(4000) NOT NULL,
    answer                  MEDIUMTEXT   NOT NULL,
    selection_block_ids_json TEXT        NOT NULL,
    claims_json             MEDIUMTEXT   NOT NULL,
    evidence_refs_json      MEDIUMTEXT   NOT NULL,
    created_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_paper_conversation_turn_run (source_run_id),
    INDEX idx_paper_conversation_turn (
        paper_id, conversation_id, document_hash, parser_version, created_at
    ),
    CONSTRAINT fk_paper_conversation_turn_paper FOREIGN KEY (paper_id)
        REFERENCES paper(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE paper_memory_observation (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id                BIGINT       NOT NULL,
    document_hash           CHAR(64)     NOT NULL,
    parser_version          VARCHAR(128) NOT NULL,
    claim_fingerprint       CHAR(64)     NOT NULL,
    claim_text              TEXT         NOT NULL,
    evidence_refs_json      MEDIUMTEXT   NOT NULL,
    source_run_id           VARCHAR(36)  NOT NULL,
    source_conversation_id  VARCHAR(64)  NULL,
    confirmation_count      INT          NOT NULL DEFAULT 1,
    status                  VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    first_seen_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_confirmed_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_paper_memory_observation (
        paper_id, document_hash, parser_version, claim_fingerprint
    ),
    INDEX idx_paper_memory_observation_recent (
        paper_id, document_hash, parser_version, status, last_confirmed_at
    ),
    CONSTRAINT fk_paper_memory_observation_paper FOREIGN KEY (paper_id)
        REFERENCES paper(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
