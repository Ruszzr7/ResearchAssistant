# Research Assistant 项目规格

## 1. 定位

Research Assistant 是一个本地运行的 AI 科研助手，面向 CS / AI / EE 研究生。目标不是替代 Zotero，而是把文献管理、论文理解、研究空白验证、阅读和写作串成一条可追踪的工作流。

设计原则：AI 先解释计划，关键动作由用户确认；长任务可查看阶段、取消、重试；外部服务失败时尽量降级，不阻断基础文库功能。

## 2. 系统边界

```text
Vue 3 + Vite
        │ REST / SSE
Spring Boot
  ├─ 论文、文件夹、标签、阅读标注、论文记忆、写作业务
  ├─ Skill Registry / Planner / Workflow Engine
  ├─ LangChain4j（OpenAI 兼容模型）
  ├─ 多源检索（arXiv、Crossref、Semantic Scholar、OpenAlex、IEEE、ACM）
  └─ RAG（MySQL 分片 + 内存向量，或 Qdrant）
        │
MySQL / 本地 PDF / 可选 Qdrant
```

后端负责编排与持久化，不训练模型。需要复杂并行、条件分支、补偿或分布式调度时，再评估 Temporal / Camunda 等工作流引擎。

## 3. 技术栈

| 层 | 当前实现 |
|---|---|
| 前端 | Vue 3、Vite、Element Plus、vxe-table、PDF.js |
| 后端 | Java 17、Spring Boot 3.2.6、Maven、MyBatis Plus |
| 数据 | MySQL 8；Flyway 版本化迁移，MySQL 保存任务/RAG/证据元数据 |
| AI | LangChain4j 1.15.1；OpenAI 兼容 Chat / Embedding API |
| PDF | PDF.js + PDFBox；公式区域使用现有框选识别，外部版面解析器暂不启用 |
| 向量 | 默认内存存储，可切换 Qdrant；MySQL 保存分片元数据 |

当前没有 Redis 和 Pinia 运行依赖。异步任务使用 Spring 线程池，状态、步骤和结果写入 MySQL。

## 4. 已实现模块

### 4.1 文库与阅读

- 文件夹树、标签、多条件分页筛选、排序、置顶、批量移动/删除。
- PDF 上传、浏览器预览、DOI / arXiv 元数据补全、文本/公式/图表提取。
- PDF.js 阅读器默认使用文字选择，支持高亮、下划线、选区笔记、页面批注、全文搜索与公式区域框选；笔记和批注具有不同图标、固定内容锚点及可拖动显示位置。
- 右侧批注列表支持跳转、完成和删除；完成项以绿色显示。高亮/下划线直接显示范围拖柄与删除 ×，不再提供独立“调整”模式。
- 论文助手提供“论文精读 / 缺陷分析 / 论文对比”三项入口；当前完成论文精读，后两项明确保留为后续范围。
- 阅读状态、页码和阅读时长；阅读计划模块已移除。

### 4.2 AI 与检索

- 论文结构化精读、对比、追问和研究主题相关度评分。
- 导入后的本地 PDFBox 版面制品生成版本化结构事实；后台按章节/语义分块理解并生成可恢复的全局论文画像。分块结果逐个检查点保存，状态区分处理中、部分就绪、就绪与失败，不阻塞用户先做选区问答。
- 论文记忆中的模型论断只能引用当前 PDF 版本内的稳定 block ID；无来源或越界引用在持久化前过滤。GROBID 仅作为未来可选增强，不是默认部署依赖，也不调用云解析服务。
- 选区问答上下文由服务端按固定优先级与独立字符预算组装，前端只发送会话 ID。当前 evidence 是事实与引用的唯一来源；对话历史、论文画像和旧观察只作为追问理解及检索提示。首次执行冻结上下文快照，重试不得重新吸收后来的记忆。
- 通过证据门禁的问答按论文、PDF hash 与解析版本保存：完整轮次承担短期连续对话，grounded claim 去重后承担长期观察。相同 claim 可累计确认与证据，不同 claim 不自动覆盖；跨论文知识关系留给后续论文对比功能。
- 库内 Gap 分析、外部来源验证、引用网络扩展和时间加权。
- 多源检索、去重、排序、引用网络扩展和用户确认后批量入库。
- 论文分析、摘要、方法、数据集、实验和 PDF 分片可生成 embedding；问答、推荐和 Gap 验证优先走 RAG，可选 LLM 重排序。

### 4.3 Agent 编排

- Skill Registry：原子能力统一注册、描述和测试。
- Planner + PlanExecutor：将自然语言目标转换为顺序 Skill 计划。
- Workflow Engine：`paper-import`、`literature-survey`、`gap-research`。
- 论文精读使用规则路由的固定 Workflow 管理检索、模型调用、Evidence Gate、重试和记忆更新；Skill 保持为可复用原子能力，不允许开放式工具循环跳过引用门禁。
- 支持异步任务、阶段提示、持久化步骤、失败点重试、取消、执行超时和带过期状态的 `PENDING_USER` 人机确认；可恢复任务通过 MySQL task_type/context 调度，具有队列容量、并发上限、租约和幂等键保护，旧版闭包任务仅兼容进程内执行。

### 4.4 写作与交互

- 写作项目、论文关联、阅读笔记引用。
- 大纲生成、Related Work 生成、引用位置建议与段落冲突检查。
- 数据看板、暗色模式、全局快捷键和 Command Palette。

## 5. 主要接口分组

| 分组 | 代表接口 |
|---|---|
| 文库 | `/api/papers`、`/api/folders`、`/api/tags` |
| 阅读 | `/api/papers/{id}/reading-progress` |
| 论文记忆 | `/api/papers/{id}/memory`、`/api/papers/{id}/memory/understand` |
| Agent | `/api/agent/process`、`/api/agent/compare`、`/api/agent/gap`、`/api/agent/chat` |
| 工作流 | `/api/agent/workflow/{key}`、`/api/agent/workflow/{taskId}/confirm` |
| 任务 | `/api/agent/tasks`、`/api/agent/task/{taskId}/cancel` |
| 阅读标注 | `/api/papers/{paperId}/annotations` |
| 写作 | `/api/writing/projects`、`/api/writing/outline`、`/api/writing/related-work` |
| 设置 | `/api/settings`、`/api/settings/test` |

统一响应格式为 `{ code, message, data }`；长耗时 AI 操作优先返回任务 ID，再由前端轮询任务状态或使用 SSE。

## 6. 数据与安全约定

- 正式运行由 `backend/src/main/resources/db/migration` 下的 Flyway 迁移负责初始化和升级；`V12.1` 是无损基线，`V13` 增加 RAG 一致性审计表，`V13.1` 修复旧库实际 schema 缺失。`schema.sql` 与 `schema-upgrade-*.sql` 仅作历史参考。
- 已有数据库切换到 Flyway 前必须备份并核验 schema；只允许在确认数据库对应基线后临时使用 `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true`，禁止对未知版本数据库盲目 baseline。
- RAG 一致性巡检通过 `/api/rag/consistency` 比对 MySQL active 指针、active version 元数据和 active chunk 数量，审计写入 `rag_consistency_audit` 不影响只读巡检结果。
- MySQL 同时保存版本化论文结构/画像、服务端对话轮次、长期 grounded observations 和冻结的上下文快照；当前不增加 Redis、图数据库或独立知识库。向量后端只负责候选原文证据召回，不能成为 citation 真源。
- PDF 存放在 `app.storage.pdf-dir`，默认 `./data/papers`。
- API Key 支持 `RA_API_KEY` 等环境变量覆盖；配置 `RA_MASTER_KEY` 后使用 AES-GCM 加密保存。
- 测试使用 `test` profile 的 H2 内存库，不得依赖开发库中的论文或任务数据。

## 6.1 部署与可运维性

- 默认交付拓扑是 MySQL + Spring Boot + Nginx/Vue；Qdrant 通过 Compose profile 可选启用，不引入 Redis。
- `/actuator/health` 用于 liveness/readiness，`/actuator/metrics` 用于低基数任务、AI、外部 API 和 RAG 指标采集；生产日志默认关闭 SQL stdout。
- 生产 profile 启动时校验 `RA_MASTER_KEY`、MySQL JDBC URL、PDF 目录和明确 CORS 白名单，校验失败即停止启动。
- MySQL、PDF 数据卷和可选 Qdrant 数据必须分别纳入备份策略；恢复后先检查健康状态，再执行 RAG 一致性巡检。

## 7. 当前待办

1. 在真实多节点环境执行压力测试并接入组织现有告警平台；本 Mission 提供指标、队列边界和测试入口，不引入新的基础设施。
2. 继续按真实使用数据优化前端大包体积和复杂页面拆分。
3. 工作流出现并行、条件分支或补偿需求后，再评估专用工作流引擎。

## 8. 文档维护

- `Spec.md`：只保留当前产品边界、架构和接口约定。
- `progress.md`：只记录阶段、日期、关键交付和验证结果。
- `knowledge.md`：只保留可复用的设计决策、踩坑和排查方法。
- 详细历史 diff 以 Git 为准，不在文档中重复保存。
- 源码、Markdown、配置和脚本统一使用 UTF-8；PowerShell 读取中文文件时显式指定 `-Encoding utf8`。
