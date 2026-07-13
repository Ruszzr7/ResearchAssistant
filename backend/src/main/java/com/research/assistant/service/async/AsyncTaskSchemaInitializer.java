package com.research.assistant.service.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
                status      VARCHAR(24)  NOT NULL,
                stage_text  VARCHAR(255),
                result_json MEDIUMTEXT,
                error       TEXT,
                created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_status_updated_at (status, updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
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
            ensureColumn(connection, "workflow_type", "VARCHAR(64) NULL");
            ensureColumn(connection, "context_json", "MEDIUMTEXT NULL");
            ensureColumn(connection, "title", "VARCHAR(255) NULL");
            ensureColumn(connection, "task_type", "VARCHAR(64) NULL");
            ensureColumn(connection, "failure_code", "VARCHAR(64) NULL");
            ensureColumn(connection, "attempt_count", "INT NOT NULL DEFAULT 0");
            ensureColumn(connection, "max_attempts", "INT NOT NULL DEFAULT 3");
            ensureColumn(connection, "next_run_at", "DATETIME NULL");
            ensureColumn(connection, "lease_owner", "VARCHAR(128) NULL");
            ensureColumn(connection, "lease_until", "DATETIME NULL");
            ensureColumn(connection, "last_heartbeat_at", "DATETIME NULL");
            ensureColumn(connection, "idempotency_key", "VARCHAR(128) NULL");
            ensureColumn(connection, "request_hash", "CHAR(64) NULL");
            statement.execute("ALTER TABLE async_task MODIFY COLUMN status VARCHAR(24) NOT NULL");
            statement.execute(CREATE_WORKFLOW_STEP_TABLE_SQL);
            log.info("async_task / workflow_step 已就绪");
        } catch (SQLException e) {
            log.warn("确保 async_task / workflow_step 表存在失败: {}", e.getMessage());
        }
    }

    private void ensureColumn(Connection connection, String columnName, String definition) throws SQLException {
        String existsSql = "SELECT 1 FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = 'async_task' AND column_name = ?";
        try (PreparedStatement preparedStatement = connection.prepareStatement(existsSql)) {
            preparedStatement.setString(1, columnName);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE async_task ADD COLUMN `" + columnName + "` " + definition);
        }
    }
}
