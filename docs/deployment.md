# ResearchAssistant 部署与数据安全

## Docker 部署

1. 复制 `.env.example` 为 `.env`。
2. 设置随机的 `RA_MASTER_KEY`，并修改数据库密码。
3. 执行：

```text
docker compose up -d --build
```

启动后访问 `http://localhost:8088`。MySQL、PDF 文件和可选 Qdrant 均使用持久化卷。

## Flyway

新库由 Spring Boot 启动时执行 `backend/src/main/resources/db/migration`。该 Flyway 目录是唯一数据库结构真源。

已有数据库切换前必须先备份，并确认 schema 完整；只有完成检查后，才临时设置 `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` 启动一次。新库保持 `false`。

## 备份与恢复

项目不再维护重复的备份包装脚本。MySQL 使用 `mysqldump`/`mysql` 原生命令备份与恢复；Docker 命名卷使用 Docker 提供的卷备份方式。MySQL 备份不能替代 PDF 目录或 `research-assistant-papers-data` 数据卷备份。

恢复数据前停止后端和前端容器；恢复后重新启动并依次检查 `/actuator/health`、Flyway 状态和 `/api/rag/consistency`。

## Windows 本地脚本

`scripts` 只保留五个面向开发环境的 `.cmd`：分别启动数据库、启动前端、启动/重启后端、整体启动和整体终止。脚本以 PID 文件管理自己启动的进程，并在端口由未知进程占用时拒绝强制终止。

## 健康检查

- 前端：`http://localhost:8088`
- 后端：`http://localhost:8080/actuator/health`
- RAG 元数据一致性：`/api/rag/consistency` 或 `/api/rag/consistency?paperId=1`

Qdrant 是可选依赖，不可用时继续使用现有内存降级链路；MySQL 和 Flyway 失败则后端不应进入可用状态。
