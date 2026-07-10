-- 阶段 6.4.7：AI 自动批注（为 paper_annotation 增加 ai_generated 字段）
USE research_assistant;

ALTER TABLE paper_annotation
    ADD COLUMN IF NOT EXISTS ai_generated TINYINT(1) DEFAULT 0 COMMENT '是否由 AI 自动生成';
