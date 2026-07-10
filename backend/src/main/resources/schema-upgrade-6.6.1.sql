-- 阶段 6.4.6：PDF 批注持久化（从旧数据库升级）
USE research_assistant;

CREATE TABLE IF NOT EXISTS paper_annotation (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id         BIGINT       NOT NULL COMMENT '所属论文 ID',
    type             VARCHAR(32)  NOT NULL COMMENT 'HIGHLIGHT / UNDERLINE / NOTE / FREEHAND',
    page             INT          NOT NULL COMMENT '页码（从 1 开始）',
    color            VARCHAR(16)           COMMENT '颜色，例如 #ffeb3b',
    note             TEXT                  COMMENT '批注文字',
    coordinates_json TEXT         NOT NULL COMMENT '归一化坐标与页面信息 JSON',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_annotation_paper (paper_id),
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
