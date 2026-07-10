-- 阶段 6.4.10：阅读计划与本周要读（旧数据库升级）
-- 执行：mysql -u root -p research_assistant < schema-upgrade-6.6.6.sql

USE research_assistant;

CREATE TABLE IF NOT EXISTS reading_plan (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL COMMENT '计划名称',
    start_date  DATE                  COMMENT '计划开始日期',
    end_date    DATE                  COMMENT '计划结束日期',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS reading_plan_item (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id     BIGINT       NOT NULL COMMENT '所属计划 ID',
    paper_id    BIGINT       NOT NULL COMMENT '论文 ID',
    deadline    DATE                  COMMENT '阅读截止日期',
    priority    INT          DEFAULT 0 COMMENT '优先级，越大越优先',
    status      VARCHAR(32)  DEFAULT 'TODO' COMMENT 'TODO / IN_PROGRESS / DONE',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_plan_paper (plan_id, paper_id),
    INDEX idx_item_plan (plan_id),
    INDEX idx_item_deadline (deadline),
    FOREIGN KEY (plan_id) REFERENCES reading_plan(id) ON DELETE CASCADE,
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
