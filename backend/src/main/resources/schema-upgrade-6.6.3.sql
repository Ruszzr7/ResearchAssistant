-- 阶段 6.4.8：笔记与论文双向链接（从旧数据库升级）
USE research_assistant;

CREATE TABLE IF NOT EXISTS note (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(255),
    content     TEXT,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS paper_note_link (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id         BIGINT       NOT NULL,
    note_id          BIGINT       NOT NULL,
    page             INT                   COMMENT '页码（从 1 开始）',
    coordinates_json TEXT                  COMMENT '归一化坐标 JSON',
    anchor_text      TEXT                  COMMENT '选中原文片段',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_link_paper (paper_id),
    INDEX idx_link_note (note_id),
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE,
    FOREIGN KEY (note_id) REFERENCES note(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
