-- 阶段 6.3.3b：引用网络扩展依赖 Semantic Scholar paperId
ALTER TABLE paper
    ADD COLUMN semantic_scholar_id VARCHAR(100) DEFAULT NULL COMMENT 'Semantic Scholar paperId' AFTER arxiv_id;
