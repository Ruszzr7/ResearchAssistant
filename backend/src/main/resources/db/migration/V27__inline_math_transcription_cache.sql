CREATE TABLE IF NOT EXISTS inline_math_transcription_cache (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id BIGINT NOT NULL,
    document_hash CHAR(64) NOT NULL,
    parser_version VARCHAR(160) NOT NULL,
    block_id VARCHAR(160) NOT NULL,
    start_offset INT NOT NULL,
    end_offset INT NOT NULL,
    source_hash CHAR(64) NOT NULL,
    provider_version VARCHAR(80) NOT NULL,
    status VARCHAR(24) NOT NULL,
    latex TEXT NULL,
    confidence DOUBLE NOT NULL DEFAULT 0,
    message VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_inline_math_transcription_paper
        FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE,
    CONSTRAINT uk_inline_math_transcription_version UNIQUE
        (paper_id, document_hash, parser_version, block_id, start_offset,
         end_offset, source_hash, provider_version)
);

CREATE INDEX idx_inline_math_transcription_lookup
    ON inline_math_transcription_cache
    (paper_id, document_hash, parser_version, block_id, start_offset, end_offset);
