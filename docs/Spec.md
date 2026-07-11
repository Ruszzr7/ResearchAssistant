# Research Assistant 项目规格

## 1. 定位

Research Assistant 是一个本地运行的 AI 科研助手，面向 CS / AI / EE 研究生。目标不是替代 Zotero，而是把文献管理、论文理解、研究空白验证、阅读和写作串成一条可追踪的工作流。

设计原则：AI 先解释计划，关键动作由用户确认；长任务可查看阶段、取消、重试；外部服务失败时尽量降级，不阻断基础文库功能。

## 2. 系统边界

```text
Vue 3 + Vite
        │ REST / SSE
Spring Boot
  ├─ 论文、文件夹、标签、阅读、笔记、写作业务
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
| 数据 | MySQL 8；手写 `schema.sql` 和版本升级脚本 |
| AI | LangChain4j 1.0；OpenAI 兼容 Chat / Embedding API |
| PDF | PDFBox；可选 Marker / MinerU / Grobid 外部命令 |
| 向量 | 默认内存存储，可切换 Qdrant；MySQL 保存分片元数据 |

当前没有 Redis 和 Pinia 运行依赖。异步任务使用 Spring 线程池，状态、步骤和结果写入 MySQL。

## 4. 已实现模块

### 4.1 文库与阅读

- 文件夹树、标签、多条件分页筛选、排序、置顶、批量移动/删除。
- PDF 上传、浏览器预览、DOI / arXiv 元数据补全、文本/公式/图表提取。
- PDF.js 阅读器支持高亮、下划线、便签、手写圈注、AI 批注和笔记双向链接。
- 阅读状态、页码、阅读时长、阅读计划、本周清单和逾期提醒。

### 4.2 AI 与检索

- 论文结构化精读、对比、追问和研究主题相关度评分。
- 库内 Gap 分析、外部来源验证、引用网络扩展和时间加权。
- 多源检索、去重、排序、引用网络扩展和用户确认后批量入库。
- 论文分析、摘要、方法、数据集、实验和 PDF 分片可生成 embedding；问答、推荐和 Gap 验证优先走 RAG，可选 LLM 重排序。

### 4.3 Agent 编排

- Skill Registry：原子能力统一注册、描述和测试。
- Planner + PlanExecutor：将自然语言目标转换为顺序 Skill 计划。
- Workflow Engine：`paper-import`、`literature-survey`、`gap-research`。
- 支持异步任务、阶段提示、持久化步骤、失败点重试、取消和 `PENDING_USER` 人机确认。

### 4.4 写作与交互

- 写作项目、论文关联、阅读笔记引用。
- 大纲生成、Related Work 生成、引用位置建议与段落冲突检查。
- 数据看板、暗色模式、全局快捷键和 Command Palette。

## 5. 主要接口分组

| 分组 | 代表接口 |
|---|---|
| 文库 | `/api/papers`、`/api/folders`、`/api/tags` |
| 阅读 | `/api/papers/{id}/reading-progress`、`/api/reading-plans` |
| Agent | `/api/agent/process`、`/api/agent/compare`、`/api/agent/gap`、`/api/agent/chat` |
| 工作流 | `/api/agent/workflow/{key}`、`/api/agent/workflow/{taskId}/confirm` |
| 任务 | `/api/agent/tasks`、`/api/agent/task/{taskId}/cancel` |
| 阅读批注 | `/api/papers/{paperId}/annotations`、`/api/papers/{paperId}/notes` |
| 写作 | `/api/writing/projects`、`/api/writing/outline`、`/api/writing/related-work` |
| 设置 | `/api/settings`、`/api/settings/test` |

统一响应格式为 `{ code, message, data }`；长耗时 AI 操作优先返回任务 ID，再由前端轮询任务状态或使用 SSE。

## 6. 数据与安全约定

- `schema.sql` 是新环境的完整建库脚本；`schema-upgrade-*.sql` 用于已有库升级。
- PDF 存放在 `app.storage.pdf-dir`，默认 `./data/papers`。
- API Key 支持 `RA_API_KEY` 等环境变量覆盖；配置 `RA_MASTER_KEY` 后使用 AES-GCM 加密保存。
- 测试使用 `test` profile 的 H2 内存库，不得依赖开发库中的论文、任务或阅读计划数据。

## 7. 当前待办

1. 多模型 fallback、prompt 版本化、结构化输出校验和 20–50 篇论文 eval 集。
2. 统一数据库迁移工具，替代逐步累积的手写升级脚本。
3. 将异步执行从进程内线程池演进为可重试、可限流、可观测的任务队列。
4. 拆分超大前端页面，优化首屏包体积和公共 composable。
5. 增加 Docker 一键部署、接口契约测试和 Micrometer 指标。

## 8. 文档维护

- `Spec.md`：只保留当前产品边界、架构和接口约定。
- `progress.md`：只记录阶段、日期、关键交付和验证结果。
- `knowledge.md`：只保留可复用的设计决策、踩坑和排查方法。
- 详细历史 diff 以 Git 为准，不在文档中重复保存。
