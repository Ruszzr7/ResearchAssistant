-- Evidence-driven writing: persist claims and traceable paper evidence.
CREATE TABLE IF NOT EXISTS writing_claim (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id   BIGINT       NOT NULL,
    section_name VARCHAR(120)  NOT NULL,
    claim_text   TEXT          NOT NULL,
    position_no  INT           NOT NULL DEFAULT 0,
    created_at   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_writing_claim_project (project_id, position_no, id),
    CONSTRAINT fk_writing_claim_project FOREIGN KEY (project_id)
        REFERENCES writing_project(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS writing_claim_evidence (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id            BIGINT        NOT NULL,
    paper_id            BIGINT        NOT NULL,
    research_session_id BIGINT        NULL,
    relation_type       VARCHAR(16)   NOT NULL,
    page_number         INT           NULL,
    locator             VARCHAR(255)  NULL,
    quote_text          MEDIUMTEXT    NOT NULL,
    note                VARCHAR(1000) NULL,
    created_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_writing_evidence_claim (claim_id, id),
    INDEX idx_writing_evidence_paper (paper_id, claim_id),
    INDEX idx_writing_evidence_session (research_session_id),
    CONSTRAINT fk_writing_evidence_claim FOREIGN KEY (claim_id)
        REFERENCES writing_claim(id) ON DELETE CASCADE,
    CONSTRAINT fk_writing_evidence_paper FOREIGN KEY (paper_id)
        REFERENCES paper(id) ON DELETE CASCADE,
    CONSTRAINT fk_writing_evidence_session FOREIGN KEY (research_session_id)
        REFERENCES research_session(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
