-- Mission 10.3：RAG 索引版本化与原子激活
-- 在已执行 schema-upgrade-10.2.sql 的数据库上执行一次。

ALTER TABLE paper_chunk
    ADD COLUMN IF NOT EXISTS index_version INT NOT NULL DEFAULT 1 COMMENT '所属 RAG 索引版本';

CREATE TABLE IF NOT EXISTS rag_index_state (
    paper_id       BIGINT      NOT NULL PRIMARY KEY,
    active_version INT         DEFAULT NULL,
    next_version   INT         NOT NULL DEFAULT 0,
    updated_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rag_index_version (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id      BIGINT       NOT NULL,
    version_no    INT          NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    chunk_count   INT          NOT NULL DEFAULT 0,
    error         VARCHAR(1000) DEFAULT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at  DATETIME     DEFAULT NULL,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rag_paper_version (paper_id, version_no),
    KEY idx_rag_active (paper_id, status),
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 将旧版 paper_chunk 视为版本 1，并建立 ACTIVE 指针。
INSERT INTO rag_index_state (paper_id, active_version, next_version)
SELECT paper_id, 1, 1
FROM paper_chunk
GROUP BY paper_id
ON DUPLICATE KEY UPDATE
    active_version = COALESCE(active_version, 1),
    next_version = GREATEST(next_version, 1);

INSERT INTO rag_index_version (paper_id, version_no, status, chunk_count, activated_at)
SELECT paper_id, 1, 'ACTIVE', COUNT(*), CURRENT_TIMESTAMP
FROM paper_chunk
GROUP BY paper_id
ON DUPLICATE KEY UPDATE
    status = IF(status = 'FAILED', 'ACTIVE', status),
    chunk_count = VALUES(chunk_count),
    activated_at = COALESCE(rag_index_version.activated_at, VALUES(activated_at));
