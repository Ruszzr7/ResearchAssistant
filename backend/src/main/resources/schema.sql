-- Research Assistant 数据库初始化脚本
-- 用法: mysql -u root -p < schema.sql

CREATE DATABASE IF NOT EXISTS research_assistant
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE research_assistant;

-- 删表顺序：先删子表再删父表
DROP TABLE IF EXISTS paper_tag;
DROP TABLE IF EXISTS paper;
DROP TABLE IF EXISTS tag;
DROP TABLE IF EXISTS folder;

-- 文件夹（嵌套：parent_id 自引用）
CREATE TABLE folder (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    parent_id   BIGINT       DEFAULT NULL,
    sort_order  INT          DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (parent_id) REFERENCES folder(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 标签字典
CREATE TABLE tag (
    id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 论文
CREATE TABLE paper (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    title               VARCHAR(500)  NOT NULL,
    authors             TEXT,                          -- JSON: [{"name":"xx","role":"first|corresponding"}]
    year                INT,
    source              VARCHAR(300),                  -- 期刊/会议/预印本
    doi                 VARCHAR(200) UNIQUE,
    arxiv_id            VARCHAR(100)  DEFAULT NULL,    -- arXiv ID
    semantic_scholar_id VARCHAR(100)  DEFAULT NULL COMMENT 'Semantic Scholar paperId',
    source_url          VARCHAR(1000) DEFAULT NULL,    -- 来源 URL
    citation_count      INT           DEFAULT 0,       -- 被引次数
    `abstract`          TEXT,                          -- MySQL 保留字，需反引号
    keywords            VARCHAR(500),
    pdf_path            VARCHAR(500)  DEFAULT NULL,    -- 本地 PDF 路径
    acquisition_method  VARCHAR(30)   DEFAULT NULL,    -- OA / BROWSER_DOWNLOAD / MANUAL_UPLOAD
    folder_id           BIGINT        DEFAULT NULL,
    reading_status      VARCHAR(20)   DEFAULT 'UNREAD',
    pinned              TINYINT(1)    DEFAULT 0 COMMENT '是否置顶',
    page_count          INT           DEFAULT NULL COMMENT 'PDF 总页数',
    current_page        INT           DEFAULT 0 COMMENT '当前读到第几页',
    read_seconds        INT           DEFAULT 0 COMMENT '累计阅读时长（秒）',
    last_read_at        DATETIME      DEFAULT NULL COMMENT '最后阅读时间',
    ai_summary          MEDIUMTEXT    DEFAULT NULL,    -- Agent 生成内容摘要（PDF 提取文本可能较大）
    processing_status   VARCHAR(20)   DEFAULT 'PENDING',    -- PENDING / PROCESSING / COMPLETED / FAILED
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_paper_folder_status (folder_id, reading_status),
    INDEX idx_paper_processing_status (processing_status),
    INDEX idx_paper_created_at (created_at),
    FOREIGN KEY (folder_id) REFERENCES folder(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- 阶段三新增表
-- ============================================================

-- 系统设置（Key-Value，如 API Key、模型名）
CREATE TABLE IF NOT EXISTS settings (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    key_name   VARCHAR(100) NOT NULL UNIQUE,
    value      VARCHAR(2000) NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 论文结构化分析结果（Agent 深度阅读产出）
CREATE TABLE IF NOT EXISTS paper_analysis (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id            BIGINT NOT NULL UNIQUE,
    sections_json       MEDIUMTEXT,       -- 章节结构 JSON
    core_contribution   TEXT,             -- 核心贡献
    method_type         VARCHAR(50),      -- THEORETICAL / EXPERIMENTAL / SYSTEM / SURVEY
    method_summary      TEXT,             -- 方法概述
    datasets_json       TEXT,             -- 使用的数据集
    models_json         TEXT,             -- 使用的模型/算法
    key_findings_json   TEXT,             -- 主要发现
    limitations_json    TEXT,             -- 局限性
    tables_summary_json TEXT,             -- 表格摘要
    figures_summary_json TEXT,            -- 图表摘要
    reproducible_artifacts_json MEDIUMTEXT, -- 可复现要素 JSON：公式、伪代码、源码/数据集链接、评测指标等
    experiment_setup_json       TEXT,       -- 实验设置 JSON：任务定义、数据集、基线、评测指标、实现细节
    benchmark_results_json      MEDIUMTEXT, -- Benchmark 结果 JSON
    formulas_json               MEDIUMTEXT, -- 公式识别结果 JSON（LaTeX 列表）
    figures_json                MEDIUMTEXT, -- 图表提取结果 JSON（区域/路径列表）
    relevance_score             INT DEFAULT NULL, -- 与用户研究主题的相关度评分（1-10）
    relevance_reason            TEXT,       -- 相关度评分理由
    raw_text            MEDIUMTEXT,       -- 原始提取文本（供后续引用）
    token_used          INT DEFAULT 0,    -- 本次分析消耗 token
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 论文对比记录
CREATE TABLE IF NOT EXISTS comparison (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_ids  VARCHAR(500) NOT NULL,     -- 逗号分隔的论文 ID
    result_json MEDIUMTEXT,               -- 对比结果 JSON
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 对话历史（多轮 ChatMemory 持久化）
CREATE TABLE IF NOT EXISTS conversation (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    memory_id    VARCHAR(100) NOT NULL,     -- 会话标识（如 paperId 或 frontend sessionId）
    role         VARCHAR(20)  NOT NULL,     -- SYSTEM / USER / AI
    content      MEDIUMTEXT,                -- 消息内容
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_memory_id (memory_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 论文-标签关联（多对多）
CREATE TABLE paper_tag (
    paper_id BIGINT NOT NULL,
    tag_id   BIGINT NOT NULL,
    PRIMARY KEY (paper_id, tag_id),
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE,
    FOREIGN KEY (tag_id)   REFERENCES tag(id)   ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- 阶段 4.15+ 新增：异步任务持久化
-- ============================================================

CREATE TABLE IF NOT EXISTS async_task (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id        VARCHAR(36)  NOT NULL UNIQUE,
    workflow_type  VARCHAR(64)  NULL     COMMENT '工作流模板 key，普通任务为空',
    context_json   MEDIUMTEXT   NULL     COMMENT '工作流启动上下文 JSON',
    title          VARCHAR(255) NULL     COMMENT '任务展示标题',
    status         VARCHAR(20)  NOT NULL COMMENT 'PENDING / PROCESSING / COMPLETED / FAILED / CANCELLED / PENDING_USER / EXPIRED',
    stage_text     VARCHAR(255),
    result_json    MEDIUMTEXT,
    error          TEXT,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status_updated_at (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 工作流步骤持久化
CREATE TABLE IF NOT EXISTS workflow_step (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id       VARCHAR(64)  NOT NULL COMMENT '关联 async_task.task_id',
    step_index    INT          NOT NULL COMMENT '步骤下标，从 0 开始',
    step_name     VARCHAR(128) NOT NULL COMMENT '步骤展示名',
    skill_name    VARCHAR(64)  NOT NULL COMMENT '调用的 Skill 名称',
    input_json    TEXT                  COMMENT '解析后的输入参数 JSON',
    output_json   TEXT                  COMMENT '执行结果 JSON',
    status        VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / PROCESSING / COMPLETED / FAILED / CANCELLED / SKIPPED',
    error         TEXT                  COMMENT '失败原因',
    started_at    DATETIME     DEFAULT NULL,
    completed_at  DATETIME     DEFAULT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_step (task_id, step_index),
    KEY idx_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 阶段 6.3.4a：RAG 向量分片
-- ============================================================

CREATE TABLE IF NOT EXISTS paper_chunk (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id      BIGINT       NOT NULL COMMENT '所属论文 ID',
    chunk_type    VARCHAR(32)  NOT NULL COMMENT '分片类型：RAW/CONTRIBUTION/METHOD/FINDING/LIMITATION/DATASET',
    content       MEDIUMTEXT   NOT NULL COMMENT '文本内容',
    embedding_json TEXT        NOT NULL COMMENT 'embedding float 数组 JSON',
    source        VARCHAR(255)          COMMENT '来源说明',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_paper_id (paper_id),
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 阶段 6.4.6：PDF 批注持久化
-- ============================================================

CREATE TABLE IF NOT EXISTS paper_annotation (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id         BIGINT       NOT NULL COMMENT '所属论文 ID',
    type             VARCHAR(32)  NOT NULL COMMENT 'HIGHLIGHT / UNDERLINE / NOTE / FREEHAND',
    page             INT          NOT NULL COMMENT '页码（从 1 开始）',
    color            VARCHAR(16)           COMMENT '颜色，例如 #ffeb3b',
    note             TEXT                  COMMENT '批注文字',
    coordinates_json TEXT         NOT NULL COMMENT '归一化坐标与页面信息 JSON',
    ai_generated     TINYINT(1)   DEFAULT 0 COMMENT '是否由 AI 自动生成',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_annotation_paper (paper_id),
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 阶段 6.4.8：笔记与论文双向链接
-- ============================================================

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

-- ============================================================
-- 阶段 6.4.10：阅读计划与本周要读
-- ============================================================

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
    notes       TEXT                  COMMENT '阅读备注',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_plan_paper (plan_id, paper_id),
    INDEX idx_item_plan (plan_id),
    INDEX idx_item_deadline (deadline),
    FOREIGN KEY (plan_id) REFERENCES reading_plan(id) ON DELETE CASCADE,
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 阶段 7.0：写作辅助模块
-- ============================================================

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

-- ============================================================
-- Mission 9.2-B：AI 结构化输出质量事件
-- ============================================================

CREATE TABLE IF NOT EXISTS ai_quality_event (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id               BIGINT       NULL,
    run_id                 VARCHAR(36)  NOT NULL,
    parent_event_id        BIGINT       NULL,
    task_type              VARCHAR(64)  NOT NULL,
    stage                  VARCHAR(32)  NOT NULL,
    prompt_version         VARCHAR(64)  NOT NULL,
    model_name             VARCHAR(128) NULL,
    status                 VARCHAR(20)  NOT NULL COMMENT 'PASS / REPAIRED / FALLBACK / FAILED',
    final_status           VARCHAR(20)  NULL COMMENT 'PASS / REPAIRED / FALLBACK / REJECTED',
    repaired               TINYINT(1)   NOT NULL DEFAULT 0,
    retry_count            INT          NOT NULL DEFAULT 0,
    validation_errors_json TEXT         NULL,
    error_message          TEXT         NULL,
    prompt_tokens          INT          NOT NULL DEFAULT 0,
    completion_tokens      INT          NOT NULL DEFAULT 0,
    total_tokens           INT          NOT NULL DEFAULT 0,
    latency_ms             BIGINT       NOT NULL DEFAULT 0,
    created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ai_quality_created_at (created_at),
    INDEX idx_ai_quality_run (run_id, created_at),
    INDEX idx_ai_quality_paper (paper_id),
    INDEX idx_ai_quality_stage_created (stage, created_at),
    INDEX idx_ai_quality_final_status_created (final_status, created_at),
    INDEX idx_ai_quality_status_created (status, created_at),
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
