# 知识总结（精简版）

> 只保留可复用的设计决策、踩坑和排查方法；完整历史以 Git 为准。

## 1. 编码与项目边界

- 源码、Markdown、YAML、JSON、SQL 和 Shell 统一使用 UTF-8，Shell 使用 LF。
- PowerShell 读取中文文件显式使用 `Get-Content -Encoding utf8`；Bash/WSL 使用 UTF-8 locale；Windows `.bat` 设置 `chcp 65001`。
- 项目是本地单机优先的 Java/Spring Boot 科研 Agent，不为了简历引入 Redis 或人为拆分微服务。

## 2. 后端与数据库

- Controller 负责协议，Service 负责业务，Mapper/Entity 负责持久化；长任务优先返回 taskId。
- MyBatis-Plus 承担常规 CRUD，复杂查询使用显式列和参数化 SQL；MySQL `abstract` 列通过别名映射为 `abstractText`。
- 正式环境由 Flyway 迁移 `backend/src/main/resources/db/migration`；`schema.sql` 和 `schema-upgrade-*.sql` 仅作历史参考。
- 测试使用独立 H2 schema 并关闭 Flyway，不依赖开发库数据。旧库切换前先备份并核验 schema，禁止对未知版本盲目 baseline。

## 3. 文件、PDF 与外部服务

- PDF 通过 `PdfParser` 抽象，PDFBox 是 fallback；Marker/MinerU/Grobid 等外部命令必须有超时和失败回退。
- 文件路径必须限制在配置目录内；PDF 数据和 MySQL 数据分开备份。
- 学术来源和 Embedding 统一经过超时、重试、并发许可和降级策略；“无结果”和“调用失败”应由状态或指标区分。

## 4. LangChain4j 与 Agent

- LangChain4j 1.15.1 是 Java AI 接入层，使用统一模型工厂、AI Services、Tool、ChatMemory 和 Embedding API。
- API Key 可由环境变量覆盖；配置 `RA_MASTER_KEY` 后使用 AES-GCM 保存，否则仅适合本地临时开发。
- `ResearchAiConfig` 使用编程式 AI Service，因为模型配置来自数据库；结构化输出先映射 POJO，失败时走受门禁约束的 JSON fallback/repair。
- Skill Registry 管理原子能力，Planner 只生成计划，PlanExecutor 负责校验和顺序执行；普通对话记忆不与工具 Agent 共享。

## 5. 异步任务与可观测性

- Spring `ThreadPoolTaskExecutor` 负责进程内执行，`async_task` 保存状态、上下文、租约、重试和结果。
- 可恢复任务使用 `task_type + context_json` 重建处理器；数据库条件更新负责 claim，租约回收防止进程崩溃后永久占用。
- 取消、超时、过期和终态转换由状态机保护，迟到回调不能覆盖终态；对外保留兼容的 `PROCESSING/COMPLETED` 字段。
- Actuator 暴露 health/info/metrics；Micrometer 只记录低基数任务、AI、Embedding、外部来源和 RAG 指标。日志不写提示词、论文正文、Provider 响应体或凭据。
- SSE 每次写入后 flush；客户端断开后不再向已关闭 response 写入。

## 6. RAG 与证据

- 标准链路：分片 → Embedding → 向量粗召回 → 可选重排序 → 带来源元数据的上下文 → Agent。
- RAG 版本通过 `rag_index_state + rag_index_version + paper_chunk.index_version` 管理；新版本先 BUILDING/READY，再切换 ACTIVE，失败时保留旧版本。
- `evidenceId` 由论文、索引版本、分片序号和内容 hash 稳定派生，不能信任模型自由生成的证据身份。
- 候选证据必须校验 ID、snippet 长度和包含关系；未经候选集确认的 Agent 证据标记为 `UNVERIFIED`。
- Qdrant 写入或检索失败时路由回退内存；一致性巡检只读比对 active 指针、版本元数据和分片数量。

## 7. Vue、PDF.js 与前端任务

- 前端通过 Vite 代理 `/api`；页面状态使用 Vue refs/reactive 和 composables，长任务轮询统一收敛到任务 API。
- PDF.js 批注保存归一化坐标，渲染时按 viewport 换算；可见页窗口加上下占位高度控制长文档渲染成本。
- vxe-table 的动态 DOM 需要 `:deep()` 或全局选择器；大表格和 PDF 查看器使用异步组件。

## 8. 安全、部署与验证

- 设置读取端只返回脱敏值和 `configured`；生产缺少 `RA_MASTER_KEY` 时 fail-closed；CORS 禁止通配符。
- API 使用 `{ code, message, data }` 包络，并通过 HTTP 状态表达校验、冲突、容量和外部服务错误；请求 ID 用于排查。
- 默认拓扑为 MySQL + Spring Boot + Nginx/Vue，Qdrant 通过 Compose profile 可选启用；Docker 启动前注入 `.env` 并通过 healthcheck 验证。
- 验证顺序：后端 `mvnw.cmd test`，前端 `npm.cmd run test:unit`、`npm.cmd run build`，部署环境再执行 Compose、备份恢复和健康检查。
