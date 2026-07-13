# 开发进度（精简版）

> 详细文件变更和历史讨论以 Git 为准；本文只保留阶段、关键交付和验证结果。

## 当前状态

- 最近一次功能记录：2026-07-12，阶段 10.3。
- 当前基线：前端生产构建通过；后端测试已切换到 `test` profile + H2 内存库。
- 主链路：论文入库 → PDF / 元数据处理 → AI 分析 → RAG 索引 → 阅读 / 检索 / Gap / 写作。

## 阶段索引

| 阶段 | 日期 | 关键交付 | 验证 |
|---|---|---|---|
| 1. 项目脚手架 | 06-27 | Vue 3、Spring Boot、Vite 代理、基础路由 | 前后端连通 |
| 2. 论文库 | 06-29 | MySQL 表结构、文件夹、标签、论文 CRUD、分页筛选 | API 与页面可用 |
| 2.5. 数据底座 | 06-30 | PDF 上传、存储、预览、PDFBox 文本提取、Crossref DOI | 上传链路可用 |
| 3. Agent 核心 | 07-02 | LLM 接入、精读、对比、Gap、自然语言检索 | 基础 AI 链路可用 |
| 4. 需求审计 | 07-03 | 校验、异常处理、CORS、安全、异步基础设施 | 后端测试通过 |
| 4.1–4.8 | 07-06 | 取消/重试、文库改版、元数据补全、标签推荐、交互修复 | Playwright + 构建通过 |
| 4.9–4.13 | 07-06–07-07 | LangChain4j 结构化输出、ChatMemory、工具调用、推荐、SSE | 单元测试持续通过 |
| 4.14–4.21 | 07-07 | 异步任务持久化、Skill Registry、Planner、Workflow、任务中心、论文入库和文献调研流水线 | 任务/步骤/确认链路完成 |
| 4.22–4.24 | 07-08–07-09 | 分析深度、相关度评分、多源检索、Semantic Scholar、引用网络 | 来源测试通过 |
| 4.25 | 07-09 | RAG MVP、可选 Qdrant、Gap 语义验证、LLM 重排序 | 向量与降级测试通过 |
| 4.26 | 07-09 | `PdfParser` 抽象、外部解析命令、公式/图表扩展点 | 解析器测试通过 |
| 4.27 | 07-09 | 阅读页数、阅读时长、进度百分比和本周清单 | 阅读服务测试通过 |
| 4.28–4.30 | 07-10 | RAG 贯穿问答/推荐/Gap，OpenAlex/IEEE/ACM，引用与时间加权 | 多源测试通过 |
| 4.31–4.32 | 07-10 | 外部 PDF 解析优先、LaTeX-OCR/图表类型扩展 | 解析测试通过 |
| 4.33–4.36 | 07-10 | PDF.js 批注、AI 批注、笔记双向链接、BibTeX、Zotero/Obsidian 同步 | 后端测试与构建通过 |
| 5. 写作辅助 | 07-10 | 写作项目、大纲、Related Work、引用检查、笔记插入 | 服务测试通过 |
| 6. UI/UX 与安全 | 07-10 | 暗色模式、API Key 加密/环境变量、看板、vxe-table、快捷键、任务中心、阅读计划增强 | 前端构建通过 |
| 7. 工程基线整理 | 07-11 | 测试库隔离、AI Bean 懒加载、异步启动开关、技术文档与 README 对齐、历史文档压缩 | 后端 206 项测试通过；前端构建通过 |
| 8. 性能与代码卫生整改 | 07-11 | 修复 RAG 降级双写、Embedding 原生批量、模型/设置缓存、文献线程池隔离、Mapper 显式列、前端 PDF/VXE 懒加载和任务逻辑复用 | 后端 206 项测试通过；前端生产构建通过 |
| 9.3 | 07-11 | 请求级输出 token 上限、失败回退拒绝、run_id/终态统计、提交后异步质量事件、统一 Golden Eval、质量事件保留与分页筛选 API | Maven clean test：226 项通过 |
| 10.1 | 07-12 | 可靠异步任务与基础可观测性：RAG 真实成功/失败、任务转换防竞态、执行超时、确认 TTL、Actuator/Micrometer、脱敏结构化日志与过期状态展示 | 后端 236 项测试通过；前端生产构建通过 |
| 10.2 | 07-12 | MySQL 可恢复任务调度：task_type/context、原子认领、租约回收、指数退避、死信、幂等键；迁移直接异步任务和 Workflow | 后端 240 项测试通过；前端生产构建通过 |
| 10.3 | 07-12 | RAG 索引版本化与 active 指针、失败保留旧版本、内存/Qdrant 运行时替换、任务队列容量与并发治理、HTTP 幂等键 | 后端 247 项测试通过；前端生产构建通过 |

## 关键架构决策

1. 使用 Spring Boot 负责业务、持久化和 Agent 编排，LangChain4j 负责模型接入。
2. Skill Registry 将 AI 原子能力拆开，Workflow Engine 负责顺序执行、步骤记录、重试和人机确认。
3. RAG 默认内存向量存储，MySQL 保存分片；Qdrant 作为可选外部存储，并在不可用时降级。
4. PDF 默认 PDFBox；外部版面恢复解析器通过命令扩展，失败自动回退。
5. API Key 支持环境变量覆盖；配置 `RA_MASTER_KEY` 后使用 AES-GCM 加密。
6. 测试 profile 使用 H2 内存库，并关闭测试期的异步孤儿任务恢复和表初始化副作用。

## 当前已知待办

- 对比/Gap 质量门禁与 eval、多模型 fallback。
- Flyway/Liquibase 数据库迁移和 Docker 部署。
- 超大前端组件拆分、首屏包体积优化、接口契约测试。

## 文档维护规则

- 新功能只在此处追加一行阶段记录，不复制完整文件清单。
- 设计决策和可复用经验写入 `docs/knowledge.md`。
- 已完成事项不要在 `docs/improvements.md` 重复维护。
## Mission 11（已实施 11.1）
- 安全/API 基线：统一设置脱敏、生产加密 fail-closed、请求 ID、异常安全响应、主要 DTO 校验、单机 AI 并发保护和 PDF 路径 containment。
- 验证：后端 255 项测试通过；前端生产构建通过。
- 未纳入：Flyway/Liquibase、完整 Docker、备份恢复和 Spring Security，保留至后续 Mission。
## Mission 11.2（已完成）
- 请求契约收敛：Folder/Tag/Workflow/Agent/Search/Paper 批量请求改用 DTO 或边界校验，保持现有 JSON 字段兼容。
- 新增核心 Controller MockMvc 契约测试；MySQL、Backend 启动脚本补充 readiness 检查。
- 验证：后端 260 项测试通过；前端生产构建通过。
## Mission 11.3（已完成）
- 验收：Paper/Search/Workflow 动态请求已类型化并限制字段；新增 6 项 MockMvc 契约测试。
- 后端 `mvnw.cmd -DforkCount=0 test`：266 项通过；前端 `npm.cmd run build`：通过（保留既有大 chunk 警告）。

## Mission 12.1（已完成）
- RAG 证据链：为分片补充稳定 `evidenceId/chunkKey`、索引版本、来源类型、字符定位和内容 hash；检索、Gap 验证与写作引用统一回传来源元数据。
- 质量门禁：新增后端候选证据校验，拒绝未知 ID、超长/不包含于候选内容的 snippet；Agent 直出证据标记为 `UNVERIFIED`，Gap 评分不计入未验证证据。
- 版本与降级：索引先写入 BUILDING 分片并准备运行时快照，再发布 ACTIVE；Qdrant 先写新版本后清理旧版本，检索过滤非 active 版本并在异常时降级内存，同时暴露结构化检索状态和 Micrometer 指标。
- Golden Eval 与展示：新增本地确定性的 RAG Golden Eval、`/api/ai-quality/rag-golden`、Gap/写作页面证据 ID 与定位展示，以及 `schema-upgrade-12.1.sql`。
- 验证：后端 `mvnw.cmd -DforkCount=0 test` 通过（275 项）；前端 `npm.cmd run build` 通过（保留既有 chunk 体积警告）。

## Mission 12.2（已完成，Mission 12 结束）
- 质量门禁扩展：compare-papers 要求多论文比较表和比较维度；analyze-gaps 要求至少三个 Gap 标题、三个研究维度和验证/研究方向，失败时不持久化不合格 Markdown。
- Golden Eval：新增 compare/gap 本地 fixture、通过率与失败原因统计，并暴露 `/api/ai-quality/synthesis-golden`。
- 外部调用可靠性：统一超时、最多重试、并发许可、失败降级和 Micrometer 指标；LiteratureSearchService 与 VerifyGapsSkill 的外部检索、Embedding 单段/批量调用共用策略，不依赖 Redis。
- 验证：后端 `mvnw.cmd -DforkCount=0 test` 通过（282 项）；前端 `npm.cmd run build` 通过（保留既有 chunk 体积警告）。

## Mission 13：Frontend Productization & Deployment/Data Safety（已完成本轮交付）

- 数据库迁移：接入 Flyway；`V12.1__baseline_current_schema.sql` 提供无损基线，`V13__mission13_data_safety.sql` 增加 RAG 一致性审计表；测试 profile 显式关闭 Flyway，避免污染 H2 测试 schema。
- 部署与安全：新增后端/前端 Dockerfile、Nginx API/SSE 代理、MySQL + Spring Boot + Vue Compose、环境模板、启动/备份/恢复脚本；生产 profile 启动时校验主密钥、MySQL、PDF 目录和 CORS。
- 可运维性：新增 `/api/rag/consistency`，对比 active 指针、active version 和 MySQL 分片数量并保留审计；延续 Actuator health/metrics、结构化日志、任务和外部 API 指标链路。
- 前端产品化：统一任务 API 与轮询 composable，修复跨页批量选择，提取文库批量操作组件；PDF 阅读器改为带上下占位高度的可见页窗口；看板增加引用网络和研究主题概览。
- 前端质量：新增 Vitest + Vue Test Utils + Playwright 配置和 smoke E2E；分页选择/任务轮询单测通过（5/5）。已安装 `@playwright/test@1.49.1` 及匹配 Chromium 1148/headless shell 1148，并用真实浏览器 smoke 校验通过；当前 Node 26 桌面环境下 Playwright Test CLI 仍在 runner 启动阶段无输出，标准 `npm run test:e2e` 需在正常 CI/Node LTS 环境复核。
- 旧库兼容修复：新增 `V13.1__repair_legacy_schema.sql`，无损补齐被错误 baseline 的异步任务、RAG、AI 质量和分片证据字段；修正 MySQL 8.0.28 不支持 `ADD COLUMN IF NOT EXISTS` 的启动兜底逻辑。
- 验证：后端 `mvnw.cmd -DforkCount=0 test` 通过（286 项，含双管理器 claim 竞争测试）；前端 `npm.cmd run test:unit` 通过（5 项）；前端 `npm.cmd run build` 通过。当前环境未安装 Docker CLI，Compose 仅完成静态审计，未执行容器启动。
