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
    source_url          VARCHAR(1000) DEFAULT NULL,    -- 来源 URL
    citation_count      INT           DEFAULT 0,       -- 被引次数
    `abstract`          TEXT,                          -- MySQL 保留字，需反引号
    keywords            VARCHAR(500),
    pdf_path            VARCHAR(500)  DEFAULT NULL,    -- 本地 PDF 路径
    acquisition_method  VARCHAR(30)   DEFAULT NULL,    -- OA / BROWSER_DOWNLOAD / MANUAL_UPLOAD
    folder_id           BIGINT        DEFAULT NULL,
    reading_status      VARCHAR(20)   DEFAULT 'UNREAD',
    ai_summary          TEXT          DEFAULT NULL,    -- Agent 生成内容摘要
    processing_status   VARCHAR(20)   DEFAULT NULL,    -- PENDING / PROCESSING / COMPLETED / FAILED
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (folder_id) REFERENCES folder(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 论文-标签关联（多对多）
CREATE TABLE paper_tag (
    paper_id BIGINT NOT NULL,
    tag_id   BIGINT NOT NULL,
    PRIMARY KEY (paper_id, tag_id),
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE,
    FOREIGN KEY (tag_id)   REFERENCES tag(id)   ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
