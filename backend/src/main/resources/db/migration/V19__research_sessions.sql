-- Durable research archive: sessions own papers, chat messages and workbench runs.
CREATE TABLE IF NOT EXISTS research_session (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_key       VARCHAR(64)  NOT NULL,
    title             VARCHAR(255) NOT NULL,
    session_type      VARCHAR(16)  NOT NULL DEFAULT 'SINGLE',
    primary_paper_id  BIGINT       NULL,
    last_page         INT          NOT NULL DEFAULT 1,
    mode              VARCHAR(48)  NOT NULL DEFAULT 'analysis',
    output_language   VARCHAR(8)   NOT NULL DEFAULT 'ZH',
    archived          BOOLEAN      NOT NULL DEFAULT FALSE,
    last_activity_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_research_session_key (session_key),
    INDEX idx_research_session_activity (archived, last_activity_at),
    INDEX idx_research_session_primary (primary_paper_id),
    CONSTRAINT fk_research_session_primary FOREIGN KEY (primary_paper_id)
        REFERENCES paper(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS research_session_paper (
    session_id  BIGINT NOT NULL,
    paper_id    BIGINT NOT NULL,
    position_no INT    NOT NULL DEFAULT 0,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (session_id, paper_id),
    INDEX idx_research_session_paper (paper_id, session_id),
    CONSTRAINT fk_research_session_paper_session FOREIGN KEY (session_id)
        REFERENCES research_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_research_session_paper_paper FOREIGN KEY (paper_id)
        REFERENCES paper(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS research_message (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id            BIGINT       NOT NULL,
    message_key           VARCHAR(100) NOT NULL,
    role                  VARCHAR(16)  NOT NULL,
    content               MEDIUMTEXT   NOT NULL,
    run_id                VARCHAR(36)  NULL,
    selection_anchor_json MEDIUMTEXT   NULL,
    evidence_json         MEDIUMTEXT   NULL,
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_research_message_key (session_id, message_key),
    INDEX idx_research_message_order (session_id, created_at, id),
    INDEX idx_research_message_run (run_id),
    CONSTRAINT fk_research_message_session FOREIGN KEY (session_id)
        REFERENCES research_session(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE paper_workbench_run
    ADD COLUMN research_session_id BIGINT NULL AFTER task_id,
    ADD INDEX idx_workbench_run_session (research_session_id),
    ADD CONSTRAINT fk_workbench_run_session FOREIGN KEY (research_session_id)
        REFERENCES research_session(id) ON DELETE SET NULL;
