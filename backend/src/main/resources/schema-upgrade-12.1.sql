-- Mission 12.1：RAG provenance 与 Evidence 契约
-- 在已执行 schema-upgrade-10.3.sql 的数据库上执行一次。

ALTER TABLE paper_chunk
    ADD COLUMN IF NOT EXISTS chunk_key VARCHAR(160) NULL COMMENT '稳定 chunk 标识',
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(32) NOT NULL DEFAULT 'PDF_TEXT' COMMENT 'PDF_TEXT / ANALYSIS_FIELD',
    ADD COLUMN IF NOT EXISTS chunk_order INT NOT NULL DEFAULT 0 COMMENT '索引内稳定顺序',
    ADD COLUMN IF NOT EXISTS page_start INT NULL COMMENT '证据起始页',
    ADD COLUMN IF NOT EXISTS page_end INT NULL COMMENT '证据结束页',
    ADD COLUMN IF NOT EXISTS char_start INT NULL COMMENT '规范化文本起始偏移',
    ADD COLUMN IF NOT EXISTS char_end INT NULL COMMENT '规范化文本结束偏移',
    ADD COLUMN IF NOT EXISTS content_hash CHAR(64) NULL COMMENT '规范化内容 SHA-256';

UPDATE paper_chunk
SET chunk_key = CONCAT('legacy-', id),
    content_hash = SHA2(TRIM(content), 256)
WHERE chunk_key IS NULL OR content_hash IS NULL;

ALTER TABLE paper_chunk
    MODIFY COLUMN chunk_key VARCHAR(160) NOT NULL,
    MODIFY COLUMN content_hash CHAR(64) NOT NULL;

ALTER TABLE paper_chunk
    ADD UNIQUE KEY uk_paper_chunk_key (paper_id, index_version, chunk_key);
