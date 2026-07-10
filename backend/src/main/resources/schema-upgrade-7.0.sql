-- 阶段 7.0：写作辅助模块数据库升级脚本
-- 适用于已运行过前期阶段的数据库

USE research_assistant;

CREATE TABLE IF NOT EXISTS writing_project (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    title         VARCHAR(255) NOT NULL COMMENT '项目标题',
    topic         VARCHAR(500)          COMMENT '研究选题',
    outline_json  MEDIUMTEXT            COMMENT '大纲 JSON: [{level,title,children:[]}]',
    related_work  MEDIUMTEXT            COMMENT '生成的 Related Work 段落',
    draft_content LONGTEXT              COMMENT '用户写作区草稿',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS writing_project_paper (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id  BIGINT       NOT NULL COMMENT '写作项目 ID',
    paper_id    BIGINT       NOT NULL COMMENT '论文 ID',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_paper (project_id, paper_id),
    INDEX idx_project_paper_project (project_id),
    INDEX idx_project_paper_paper (paper_id),
    FOREIGN KEY (project_id) REFERENCES writing_project(id) ON DELETE CASCADE,
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
