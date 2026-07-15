-- P1-B2: versioned, cacheable PDF layout artifacts.
CREATE TABLE IF NOT EXISTS paper_layout_artifact (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id           BIGINT       NOT NULL,
    document_hash      CHAR(64)     NOT NULL,
    parser_version     VARCHAR(96)  NOT NULL,
    status             VARCHAR(24)  NOT NULL DEFAULT 'READY',
    layout_confidence  DECIMAL(6,5) NOT NULL DEFAULT 0,
    page_count         INT          NOT NULL DEFAULT 0,
    blocks_json        MEDIUMTEXT   NOT NULL,
    generated_at       DATETIME(6)  NOT NULL,
    created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_layout_paper_hash_parser (paper_id, document_hash, parser_version),
    INDEX idx_layout_paper_generated (paper_id, generated_at),
    CONSTRAINT fk_layout_artifact_paper FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
