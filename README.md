# Research Assistant

本地运行的 AI 科研助手，帮助研究生管理论文、分析方法、验证研究空白、记录阅读过程并辅助写作。

## 快速启动

1. 安装 JDK 17+、Node.js 和 MySQL 8。
2. 正式部署使用 Flyway 自动初始化/升级数据库；旧库切换前先阅读 [docs/migrations.md](docs/migrations.md)。`schema.sql` 仅保留为历史参考。
3. 在 `backend` 目录运行 `mvnw.cmd spring-boot:run`。
4. 在 `frontend` 目录运行 `npm.cmd install` 和 `npm.cmd run dev`。
5. 打开 `http://localhost:5173`，在设置页填写 OpenAI 兼容 API 的 Base URL、模型和 API Key。

Windows 用户也可以运行 `scripts/start-dev.bat`；Bash 环境可使用对应的 `.sh` 脚本。

生产或本地交付环境可复制 `.env.example` 为 `.env`，再运行 `scripts/deploy-up.bat`（或 `scripts/deploy-up.sh`）通过 Docker Compose 一键启动 MySQL、后端和前端。完整流程见 [docs/deployment.md](docs/deployment.md)。

## 文档与编码约定

- 源码、Markdown、YAML、JSON、SQL 和 Shell 脚本统一保存为 UTF-8；`.sh` 使用 LF 换行。
- PowerShell 读取中文文件时显式使用 `Get-Content -Encoding utf8`；Bash/WSL 建议设置 `LANG=C.UTF-8` 和 `LC_ALL=C.UTF-8`。
- Windows 批处理脚本使用 `chcp 65001`；不要在编辑器或脚本中隐式转换项目文件编码。

## 功能概览

- 文库：文件夹、标签、PDF 上传、DOI / arXiv 元数据补全、批量操作。
- AI：论文精读、对比、追问、Gap 分析与多源验证。
- Agent：Skill Registry、自然语言 Planner、可暂停 Workflow、任务中心。
- RAG：论文分析结果和 PDF 分片生成 embedding，默认内存检索，可选 Qdrant。
- 阅读与写作：PDF 批注、笔记双向链接、阅读计划、Related Work、大纲和引用检查。

## 重要配置

| 环境变量 | 用途 |
|---|---|
| `SPRING_DATASOURCE_URL` | MySQL 连接地址 |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | 数据库账号 |
| `RA_API_KEY` / `RA_BASE_URL` / `RA_MODEL` | 覆盖数据库中的模型设置 |
| `RA_MASTER_KEY` | API Key AES-GCM 加密主密钥 |
| `APP_STORAGE_PDF_DIR` | PDF 存储目录 |
| `ASYNC_TASK_TIMEOUT` | 异步任务执行上限，默认 `30m` |
| `ASYNC_PENDING_USER_TTL` | 人机确认等待期限，默认 `24h` |
| `ASYNC_TASK_LEASE` | 可恢复任务租约时长，默认 `5m` |
| `ASYNC_RETRY_BASE_DELAY` | 自动重试基础退避，默认 `5s` |
| `ASYNC_MAX_ATTEMPTS` | 单任务最大尝试次数，默认 `3` |
| `ASYNC_MAX_INFLIGHT` | 可恢复任务最大并发执行数，默认 `20` |
| `ASYNC_MAX_QUEUE_DEPTH` | 可恢复任务队列容量，默认 `500` |
| `RAG_INDEX_RETENTION` | RAG 旧版本保留时间，默认 `7d` |
| `RAG_INDEX_CLEANUP_CRON` | RAG 旧版本清理计划，默认每天 03:45 |
| `SPRING_FLYWAY_BASELINE_ON_MIGRATE` | 仅在已核验旧库切换时临时开启，默认 `false` |
| `RA_CORS_ALLOWED_ORIGINS` | 生产前端来源白名单，禁止使用 `*` |

没有配置 `RA_MASTER_KEY` 时，API Key 仅适合本地临时开发，可能以明文保存。

运行状态可通过 `/actuator/health` 查看；低基数任务、AI 调用与 RAG 指标可从 `/actuator/metrics` 查询。

## 开发与验证

```text
前端构建：cd frontend && npm.cmd run build
后端测试：cd backend && mvnw.cmd test
前端单测：cd frontend && npm.cmd run test:unit
前端 E2E：cd frontend && npm.cmd run test:e2e
```

后端测试使用独立 H2 内存数据库，不读取开发库数据。

更多架构和接口说明见 [docs/Spec.md](docs/Spec.md)，阶段记录见 [docs/progress.md](docs/progress.md)。

## Mission 13 交付边界

- 数据库升级统一由 `backend/src/main/resources/db/migration` 下的 Flyway 迁移管理；正式部署不再手工执行旧升级脚本。
- Docker Compose 默认保持 MySQL + Spring Boot + Nginx 前端单机部署，Qdrant 仅通过 `--profile qdrant` 启用，不引入 Redis。
- `/actuator/health`、`/actuator/metrics` 和 `/api/rag/consistency` 可用于健康检查、指标采集和 RAG 数据一致性巡检。
- MySQL 备份脚本只覆盖数据库；PDF 与可选 Qdrant 数据还需按 [docs/deployment.md](docs/deployment.md) 对应卷一起备份。
