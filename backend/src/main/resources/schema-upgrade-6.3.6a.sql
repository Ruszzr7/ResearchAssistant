-- 阶段 6.3.6a：阅读进度管理数据库迁移脚本
-- 用法：mysql -u root -p research_assistant < schema-upgrade-6.3.6a.sql

ALTER TABLE paper
    ADD COLUMN page_count   INT      DEFAULT NULL COMMENT 'PDF 总页数' AFTER pinned,
    ADD COLUMN current_page INT      DEFAULT 0 COMMENT '当前读到第几页' AFTER page_count,
    ADD COLUMN read_seconds INT      DEFAULT 0 COMMENT '累计阅读时长（秒）' AFTER current_page,
    ADD COLUMN last_read_at DATETIME DEFAULT NULL COMMENT '最后阅读时间' AFTER read_seconds;
