-- 论文列表筛选与排序索引，执行一次即可。
ALTER TABLE paper
    ADD INDEX idx_paper_folder_status (folder_id, reading_status),
    ADD INDEX idx_paper_processing_status (processing_status),
    ADD INDEX idx_paper_created_at (created_at);
