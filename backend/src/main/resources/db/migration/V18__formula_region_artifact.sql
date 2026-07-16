-- P4-F: user-addressable formula regions, separate from immutable layout artifacts.
CREATE TABLE IF NOT EXISTS paper_formula_region (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id        BIGINT        NOT NULL,
    document_hash   CHAR(64)      NOT NULL,
    parser_version  VARCHAR(128)  NOT NULL,
    page_number     INT           NOT NULL,
    region_key      CHAR(64)      NOT NULL,
    box_x           DECIMAL(9,8)  NOT NULL,
    box_y           DECIMAL(9,8)  NOT NULL,
    box_width       DECIMAL(9,8)  NOT NULL,
    box_height      DECIMAL(9,8)  NOT NULL,
    latex           TEXT          NULL,
    confidence      DECIMAL(6,5)  NOT NULL DEFAULT 0,
    source          VARCHAR(24)   NOT NULL,
    status          VARCHAR(24)   NOT NULL,
    created_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_formula_region_version (paper_id, document_hash, parser_version, page_number, region_key),
    INDEX idx_formula_region_lookup (paper_id, document_hash, parser_version, page_number, status),
    CONSTRAINT fk_formula_region_paper FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
