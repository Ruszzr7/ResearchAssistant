package com.research.assistant.service.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 异步任务表初始化器。
 *
 * <p>项目使用手写 schema.sql 管理表结构，但新增表时用户可能忘记执行。
 * 该组件在启动时幂等地创建 {@code async_task} 表，避免任务持久化因表缺失而失败。</p>
 */
@Component
public class AsyncTaskSchemaInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskSchemaInitializer.class);

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS async_task (
                id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                task_id     VARCHAR(36)  NOT NULL UNIQUE,
                status      VARCHAR(20)  NOT NULL,
                stage_text  VARCHAR(255),
                result_json MEDIUMTEXT,
                error       TEXT,
                created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_status_updated_at (status, updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private static final String ADD_WORKFLOW_TYPE_COLUMN_SQL = """
            ALTER TABLE async_task
            ADD COLUMN IF NOT EXISTS workflow_type VARCHAR(64) NULL COMMENT '工作流模板 key，普通任务为空'
            """;

    private static final String ADD_CONTEXT_JSON_COLUMN_SQL = """
            ALTER TABLE async_task
            ADD COLUMN IF NOT EXISTS context_json MEDIUMTEXT NULL COMMENT '工作流启动上下文 JSON'
            """;

    private static final String ADD_TITLE_COLUMN_SQL = """
            ALTER TABLE async_task
            ADD COLUMN IF NOT EXISTS title VARCHAR(255) NULL COMMENT '任务展示标题'
            """;

    private static final String CREATE_WORKFLOW_STEP_TABLE_SQL = """
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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private final DataSource dataSource;
    private final boolean schemaInitEnabled;

    public AsyncTaskSchemaInitializer(DataSource dataSource,
                                      @Value("${app.async.schema-init-enabled:true}") boolean schemaInitEnabled) {
        this.dataSource = dataSource;
        this.schemaInitEnabled = schemaInitEnabled;
    }

    @Override
    public void afterPropertiesSet() {
        if (!schemaInitEnabled) {
            log.info("已跳过异步任务表启动初始化（配置 app.async.schema-init-enabled=false）");
            return;
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE_SQL);
            statement.execute(ADD_WORKFLOW_TYPE_COLUMN_SQL);
            statement.execute(ADD_CONTEXT_JSON_COLUMN_SQL);
            statement.execute(ADD_TITLE_COLUMN_SQL);
            statement.execute(CREATE_WORKFLOW_STEP_TABLE_SQL);
            log.info("async_task / workflow_step 已就绪");
        } catch (SQLException e) {
            log.warn("确保 async_task / workflow_step 表存在失败: {}", e.getMessage());
        }
    }
}
