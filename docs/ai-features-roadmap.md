# Research Assistant — 剩余 AI 功能盘点（LangChain4j 规划基础）

> 本文档用于在新会话中快速对齐：当前哪些 AI 能力已落地、哪些还需要补全，以及若引入 LangChain4j 应优先做什么。
> 
> 统计时间：2026-07-06
> 技术栈：Spring Boot 3 + Vue 3 + MySQL 8 + MyBatis Plus；计划引入 LangChain4j 做 AI 编排。

---

## 一、已实现的核心 AI 能力

| 能力 | 实现状态 | 关键文件 / 端点 |
|---|---|---|
| **LLM 统一调用** | 已实现 | `LLMService` / `LLMServiceImpl` / `LLMStreamService`；支持 DeepSeek/OpenAI 兼容接口、流式 SSE、每次从 `settings` 热读 Key |
| **论文精读 / 结构化分析** | 已实现 | `AgentController.process/{paperId}`、`process/{paperId}/stream`、`/analysis/{paperId}`；`PaperProcessingService` 完成 PDF 提取 → 文本清洗 → LLM → 解析 → 持久化 |
| **论文横向对比** | 已实现 | `AgentController.compare` → `AgentOrchestrator.comparePapers`，支持自定义维度 |
| **Gap 分析（库内 + 外部验证）** | 已实现 | `AgentController.gap/internal/verify/stream`；`AgentOrchestrator.analyzeGaps`；外部验证用 arXiv 标题关键词检索 |
| **追问对话** | 已实现 | `AgentController.chat`、`/gap/chat` → `AgentOrchestrator.chatAbout` |
| **标签建议** | 已实现（后端） | `AgentController.tag-suggestions` → `AgentOrchestrator.suggestTags` |
| **文件夹推荐** | 已实现 | `AgentController.folder-suggest` → `AgentOrchestrator.suggestFolder/suggestFolderByTitle` |
| **对话式检索** | 已实现 | `SearchController.extract/execute/expand` → `SearchServiceImpl`；用 LLM 提炼检索要素、生成推荐理由 |
| **自动元数据补全** | 已实现 | `MetadataEnrichmentService`：从 PDF 识别 DOI/arXiv ID，调 Crossref/arXiv 回填 |
| **异步任务编排** | 已实现 | `AsyncTaskService`：PDF 下载、AI 分析均走 Spring `@Async` |

### 前端已接线的页面

- `AnalysisView.vue`：精读（SSE 流式）、对比、库内推荐、追问
- `GapView.vue`：勾选 ≥3 篇论文 → 库内 Gap 分析 → 外部验证 → 追问
- `SearchView.vue`：自然语言 → 检索要素提炼 → 执行检索 → 扩展检索 → 文件夹推荐
- `LibraryView.vue`：AI 分析触发、PDF 自动识别元数据、文件夹 Agent 推荐、标签编辑

---

## 二、仍缺失 / 半实现的 AI 能力（即需要继续做的）

| 能力 | 当前状态 | 说明与建议重点 |
|---|---|---|
| **AI 阅读状态推荐** | 缺失 | 当前阅读状态为纯手动下拉；可基于论文摘要/标题推荐 `UNREAD/READING/READ` |
| **`Paper.aiSummary` 字段** | 未真正使用 | 实体注释“Agent 生成的内容摘要（未实现）”，当前只在 PDF 上传时临时存提取文本 |
| **库内论文推荐** | 半实现 / 规则化 | `AnalysisView.doRecommend` 仅按作者、关键词、文件夹硬规则匹配，未用 LLM/语义 |
| **标签建议 UI** | 未接线 | 后端 `/agent/tag-suggestions` 已存在，但 `LibraryView` 标签弹窗未调用，依赖手动输入 |
| **文件夹推荐深度** | 半实现 | 仅基于标题，未利用摘要或 PDF 全文；建议接入摘要/核心贡献 |
| **Search 扩展检索** | 半实现 | `expandSearch` 只是用标题关键词再查 arXiv，未真正做引文网络 / 作者追踪 / 相关工作 LLM 推理 |
| **Gap 外部验证** | 偏简单 | 仅按标题前 5 个词搜 arXiv，无语义比对、无引用关系、无正文学术验证 |
| **对话记忆** | 缺失 | `chatAbout` 每次只传当前上下文字符串，无多轮历史、无会话隔离 |
| **工具调用 / MCP** | 缺失 | 当前无 Tool Calling 框架；arXiv 搜索、Crossref 查询都是硬编码 HTTP 调用 |

---

## 三、引入 LangChain4j 的架构建议

1. **LLM 层可直接替换**
   现有 `LLMService` 接口较薄（`chat / chatWithUsage / chatStream`），适合保留为防腐层，内部改用 LangChain4j 的 `OpenAiChatModel` 或 `StreamingChatLanguageModel`。

2. **Prompt 集中管理**
   当前 Prompt 以 `private static final String` 分散在 `AgentOrchestratorImpl`、`PaperProcessingService`、`SearchServiceImpl` 中。引入 LangChain4j 后，建议迁移到 `@SystemMessage` / `@UserMessage` 注解或集中 Prompt 模板，便于版本管理与调优。

3. **结构化输出可升级**
   现在用正则/简单 JSON 提取字段（`extractField`），容易因 LLM 输出不稳定失败。LangChain4j 的 `AiServices` + POJO 返回可替代当前手写解析，提升 `PaperProcessingService` 和 `SearchServiceImpl` 的鲁棒性。

4. **记忆与会话**
   当前追问没有持久化历史。LangChain4j 的 `ChatMemory`（如 `MessageWindowChatMemory`）可接入对话接口，给每个用户/会话一个独立记忆 store。

5. **工具调用需求明确**
   项目已有 arXiv 搜索、Crossref 查询、PDF 提取等能力，天然适合封装为 LangChain4j `@Tool`，让 LLM 在 Gap 验证、扩展检索、元数据补全中自主决定调用哪些工具。

6. **异步与流式保持现状**
   Spring `@Async` + 自定义 SSE 的并发模型运行良好；LangChain4j 流式结果可以通过 `StreamingResponseBody` 继续推送给前端，改动集中在 `LLMStreamService` 即可。

7. **建议优先接入的场景（按 ROI 排序）**
   1. 论文结构化分析（POJO 输出）
   2. 对话记忆
   3. Gap 验证 / 扩展检索的工具调用
   4. 阅读状态与标签的 Agent 推荐

---

## 四、下一会话可讨论的议题

- LangChain4j 在 Spring Boot 3 中的最小集成方案（依赖、配置、与现有 `LLMService` 的适配）。
- 是否用 `AiServices` 定义一个统一科研 Agent 接口，替代现有的 `AgentOrchestrator`。
- 工具类封装：`ArxivFetcher`、`CrossrefFetcher`、`PdfExtractor` 如何变成 `@Tool`。
- 记忆存储选型：内存 `ChatMemory` vs 数据库存储（`paper_analysis` / 新增 `conversation` 表）。
- 先选一个最小闭环做 PoC：例如用 LangChain4j 重写「论文精读 → 结构化 JSON 输出」，验证端到端可行性。
