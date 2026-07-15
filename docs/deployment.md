# ResearchAssistant 部署与数据安全

## Docker 一键部署

1. 复制 `.env.example` 为 `.env`。
2. 设置随机的 `RA_MASTER_KEY`，并修改数据库密码。
3. 执行：

```text
scripts\deploy-up.bat
```

或：

```bash
./scripts/deploy-up.sh
```

启动后访问 `http://localhost:8088`。MySQL、PDF 文件和可选 Qdrant 均使用持久化卷。

启用 Qdrant：

```text
docker compose --profile qdrant up -d --build
```

## Flyway

新库由 Spring Boot 启动时执行 `backend/src/main/resources/db/migration`。正式部署不再手动执行 `schema.sql` 或 `schema-upgrade-*.sql`。

已有数据库切换前必须先备份，并确认 schema 完整；只有完成检查后，才临时设置 `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` 启动一次。新库保持 `false`。

## 备份与恢复

```text
scripts\backup-mysql.bat
scripts\restore-mysql.bat backups\research_assistant_YYYYMMDD_HHMMSS.sql
scripts/backup-mysql.sh
scripts/restore-mysql.sh backups/research_assistant_YYYYMMDD_HHMMSS.sql
```

MySQL 备份不能替代 PDF 目录备份。使用以下脚本备份或恢复命名数据卷：

```text
scripts/backup-volumes.sh
scripts/backup-volumes.bat
scripts/restore-volume.sh research-assistant-papers-data backups/research-assistant-papers-data_YYYYMMDD_HHMMSS.tar.gz
scripts\restore-volume.bat research-assistant-papers-data backups\research-assistant-papers-data_YYYYMMDD_HHMMSS.tar.gz
```

恢复数据卷前停止后端和前端容器；恢复后重新启动并依次检查 `/actuator/health`、Flyway 状态和 `/api/rag/consistency`。Qdrant 数据卷只在启用 Qdrant profile 时需要恢复。

## 健康检查

- 前端：`http://localhost:8088`
- 后端：`http://localhost:8080/actuator/health`
- RAG 元数据一致性：`/api/rag/consistency` 或 `/api/rag/consistency?paperId=1`

Qdrant 是可选依赖，不可用时继续使用现有内存降级链路；MySQL 和 Flyway 失败则后端不应进入可用状态。
