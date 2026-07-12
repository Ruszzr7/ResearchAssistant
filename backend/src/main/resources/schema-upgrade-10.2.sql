-- Mission 10.2: 可恢复任务调度元数据
-- 适用于已存在 async_task 表的 MySQL 8 数据库。

USE research_assistant;

ALTER TABLE async_task
    MODIFY COLUMN status VARCHAR(24) NOT NULL;

ALTER TABLE async_task
    ADD COLUMN IF NOT EXISTS task_type VARCHAR(64) NULL COMMENT '可恢复异步处理器类型；旧版内存任务为空',
    ADD COLUMN IF NOT EXISTS failure_code VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS max_attempts INT NOT NULL DEFAULT 3,
    ADD COLUMN IF NOT EXISTS next_run_at DATETIME NULL,
    ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(128) NULL,
    ADD COLUMN IF NOT EXISTS lease_until DATETIME NULL,
    ADD COLUMN IF NOT EXISTS last_heartbeat_at DATETIME NULL,
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128) NULL,
    ADD COLUMN IF NOT EXISTS request_hash CHAR(64) NULL;

ALTER TABLE async_task
    ADD INDEX idx_async_status_next_run (status, next_run_at),
    ADD INDEX idx_async_lease_until (status, lease_until),
    ADD UNIQUE KEY uk_async_idempotency (idempotency_key);
