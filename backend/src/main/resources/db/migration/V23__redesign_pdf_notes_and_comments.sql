-- PDF 标注语义重构：历史数据均为开发测试数据，按产品确认直接清空，
-- 不把旧“批注/便签/独立笔记”的含义猜测迁移为新语义。
DELETE FROM paper_annotation;

ALTER TABLE paper_annotation
    MODIFY COLUMN type VARCHAR(32) NOT NULL
        COMMENT 'HIGHLIGHT / UNDERLINE / NOTE / COMMENT / FREEHAND',
    ADD COLUMN completed TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'COMMENT 是否已完成' AFTER ai_generated,
    ADD COLUMN completed_at DATETIME NULL
        COMMENT 'COMMENT 完成时间' AFTER completed,
    ADD INDEX idx_annotation_paper_type (paper_id, type, page);

-- 旧独立文本笔记已由选区 NOTE 取代；写作工作台改为聚合 NOTE。
DROP TABLE IF EXISTS paper_note_link;
DROP TABLE IF EXISTS note;
