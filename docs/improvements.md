# Research Assistant — 待改进、技术债与优化路线图

> **使用说明**：每完成一项改进，请在对应条目前的方框内打勾（将 `- [ ]` 改为 `- [x]`），并在 `docs/progress.md` 中记录完成时间与验证结果。

---

## 一、核心能力缺口（使用者视角）

### 1. AI 分析深度不足

- [x] 在 `PaperAnalysisResult` / `PaperProcessingService` 中增加 `reproducibleArtifacts`、`experimentSetup`、`benchmarkResults` 等字段。
- [x] 提取论文中的核心公式、伪代码、源码链接、数据集与评测指标。
- [x] 增加「与我当前课题相关度」评分，允许用户输入研究主题后做相关性排序。

*已完成于阶段 6.3.1（2026-07-08）。*

### 2. Gap 分析学术价值偏低

- [x] 引入向量检索，对 Gap 描述与候选论文摘要做语义相似度计算。
- [x] 接入 Semantic Scholar（第二文献源），与已有 Crossref / arXiv 共同做多源验证。
- [x] 接入 IEEE Xplore / ACM DL 等付费/机构来源，进一步扩大验证覆盖面。
- [x] 按引用网络判断 Gap 是否被核心工作覆盖，按发表时间加权。

### 3. 文献调研来源过窄

- [x] 抽象 `LiteratureSource` 接口，实现 `ArxivSource`、`SemanticScholarSource`、`CrossrefSource`。
- [x] 在 `MultiSourceSearchSkill` 与 `SearchService` 中并行查询多源，统一去重与排序。
- [x] 增加「按引用网络扩展」步骤（前向/后向引用、作者追踪）—— 依赖 Semantic Scholar `paperId`，已预留字段，列为阶段 6.3.3b。

### 4. 没有向量检索 / RAG

- [x] 引入向量数据库（推荐 `pgvector` 或 `Qdrant`）或 Redis 向量能力。
- [x] 对论文摘要、核心贡献、方法概述、PDF 分段生成 embedding（MVP：MySQL + 内存向量存储）。
- [x] 所有问答、推荐、Gap 验证优先走 RAG 召回 + LLM 重排序。
- [x] 将 `CLAUDE.md` 中「向量检索 不做」更新为当前状态说明。

### 5. PDF 解析质量脆弱

- [x] 将 PDF 解析抽象为 `PdfParser` 接口，支持 PDFBox、PyMuPDF、Marker、Grobid、MinerU 等多种实现。
- [x] 默认接入版面恢复能力更强的解析器（如 Marker / MinerU）。
- [x] 对公式使用 LaTeX-OCR，对图表做单独提取（MVP：外部命令扩展点已就绪）。

### 6. 阅读工作流不完整

- [x] 实现 PDF 预览器内的批注持久化（高亮、下划线、便签、手写圈注、坐标、颜色、笔记）。
- [x] 支持 AI 自动批注（标记核心方法、创新点、实验结论、潜在问题）。
- [x] 增加真正的阅读进度管理（阅读时长、进度百分比、当前页、PDF 页数自动提取）。
- [x] 支持笔记与论文段落的双向链接。
- [x] 支持 BibTeX 导出、Zotero/Obsidian 同步。
- [x] 增加「本周要读」清单、阅读计划、deadline 提醒。

### 7. 对论文写作没有直接帮助

- [x] 新增写作辅助模块：根据选题自动生成大纲。
- [x] 根据收藏论文生成 Related Work 段落。
- [x] 推荐引用位置、检查段落与已有文献的重复或冲突。
- [x] 与阅读笔记打通，允许一键插入笔记到写作区。

*已完成于阶段 7.0（2026-07-10）。*

### 8. UI/UX 仍处可用但不够精致阶段

- [x] 评估用专业数据表格组件（vxe-table、AG Grid）替换手写列拖动。
- [x] 增加暗色模式。
- [x] 增加全局快捷键 / Command Palette。
- [x] 增加首页数据看板（本月新增、文件夹堆积、任务状态）。
- [x] ~~提升移动端可用性（暂不实现）~~

### 9. AI 输出缺乏质量保证

- [ ] 支持多模型配置与自动降级（DeepSeek + 至少一个备用模型）。
- [ ] 建立 20–50 篇论文的 eval set，定期回归测试 `analyze-paper`、`compare-papers`、`analyze-gaps`。
- [ ] 增加 prompt 版本化与 A/B 测试机制。
- [ ] 增加输出 schema 校验与自动修复（字段缺失、JSON 非法、事实一致性）。

### 10. 安全与数据保护

- [x] 对 API Key 等敏感配置加密存储，或优先读取环境变量。
- [x] 移除 `mvnw` / `mvnw.cmd` 中硬编码的 `JAVA_HOME` / `MAVEN_HOME`。

---

## 二、需要审视的技术选型（面试官视角）

### 1. Java / Spring Boot 做 AI 项目的定位

- [ ] 在 `docs/Spec.md` 或 `README.md` 中明确说明：Spring Boot 负责编排与持久化，AI 能力通过 LangChain4j 接入。
- [ ] 准备面试回答：「为什么用 Java 而不是 Python」，并说明未来 ML 服务可拆 Python 微服务。

### 2. 向量检索缺失

- [ ] 落地 `pgvector` 或 `Qdrant` PoC，补齐语义搜索与 RAG。
- [ ] 更新技术栈文档，移除「向量检索 不做」的长期声明。

### 3. Redis 使用率不足

- [ ] 明确 Redis 在项目中的真实职责（分布式锁 / 限流 / 任务队列 / 向量缓存）。
- [ ] 若暂无使用场景，从技术栈文档中移除 Redis，降低部署复杂度。

### 4. 自研 Workflow Engine 的边界

- [ ] 在文档中明确自研引擎的边界：不支持并行、条件分支、循环、超时、补偿。
- [ ] 当工作流超过 10 个步骤或需要多分支时，评估 Temporal / Camunda / Activiti。

### 5. 异步任务架构扩展性

- [ ] 引入消息队列（RabbitMQ / Redis Stream / Kafka）做任务队列。
- [ ] 支持任务优先级、重试、死信队列、背压。
- [ ] 对 `PENDING_USER` 任务增加 TTL 与自动取消策略。

### 6. PDFBox 局限性

- [ ] 抽象 `PdfParser` 接口，默认接入 Marker / Grobid / MinerU，保留 PDFBox 作为 fallback。

### 7. 单一模型风险

- [ ] `LLMService` 支持多模型配置与失败自动降级。
- [ ] 建立模型输出 eval 与 prompt 版本管理。

### 8. 前端表格组件选型

- [ ] 评估 vxe-table 或 AG Grid 替换 Element Plus 手写列拖动。
- [ ] 若保留 Element Plus，简化列拖动交互，不再扩展复杂表格功能。

### 9. 测试与可观测性

- [ ] 将 AI eval 测试集纳入 CI。
- [ ] 增加接口契约测试与性能测试。
- [ ] 增加可观测性：Micrometer + Prometheus metrics、tracing、日志聚合。

### 10. 部署方案落地

- [ ] 编写 `Dockerfile` + `docker-compose.yml`，实现一键部署。
- [ ] 将前端构建产物嵌入后端静态资源目录，配置 SPA 路由回退。
- [ ] 验证 Docker 环境下 PDF 上传/预览、MySQL 初始化、AI 功能是否正常。

---

## 三、分阶段落地清单

### 近期（1–2 周，立刻提升产品体感）

- [ ] **P0**：接入向量检索 / RAG（pgvector / Qdrant + embedding）。
- [ ] **P0**：升级 PDF 解析（抽象 `PdfParser`，默认 Marker/MinerU）。
- [ ] **P0**：扩展文献来源（Semantic Scholar / Crossref / IEEE / ACM）。
- [ ] **P1**：模型 fallback 与多模型配置。

### 中期（1 个月，简历能讲的故事）

- [ ] **P1**：Gap 验证重做（语义检索 + 多源 + 引用网络 + 时间过滤）。
- [ ] **P1**：Docker 一键部署（移除 `mvnw` 硬编码路径）。
- [ ] **P1**：AI eval 框架（20–50 篇 golden paper 回归测试）。
- [ ] **P2**：Redis 做实事（分布式锁 / 限流 / 任务队列 / 向量缓存）。
- [ ] **P2**：自研 Workflow 边界说明 / 引入 Temporal 评估。
- [ ] **P2**：前端重构（`useSession`、SSE 通用 composable、共享 settings/文件夹推荐逻辑）。

### 长期（2 个月，形成差异化）

- [ ] **P2**：PDF 批注 + 笔记双向链接。
- [ ] **P2**：写作辅助（Related Work 生成、大纲生成、引用推荐）。
- [ ] **P3**：引用关系图谱与可视化。
- [ ] **P3**：暗色模式 + 全局快捷键 + 数据看板。
- [ ] **P3**：完整可观测性（metrics、tracing、日志聚合）。

---

## 四、历史待办事项合并索引

以下事项原本分散在 `docs/Spec.md`、`docs/progress.md`、`docs/ai-features-roadmap.md` 中，已按主题归入上文 checklist，此处仅作索引，便于追溯。

### 来自 `docs/Spec.md`

| 原编号 | 原内容 | 合并位置 |
|--------|--------|----------|
| 1 | PDF 批注与 AI 辅助批注 | 一、6. 阅读工作流 |
| 2 | 项目打包与分发部署 | 二、10. 部署方案落地；三、中期 |
| 3 | 向量检索与语义搜索 | 一、4. 没有向量检索/RAG；二、2. 向量检索缺失 |
| 4 | 引用关系图谱 | 三、长期 |

### 来自 `docs/progress.md`

| 阶段 | 原待办 / 跳过项 | 合并位置 |
|------|----------------|----------|
| 阶段 4.2 | PDF 批注、项目打包、AI 编排与精读报告输出质量优化 | 一、6. / 二、10. / 一、9. |
| 阶段 4.3 | `useGlobalTask` / `useCancellableTask` 共享核心 runner、任务闭包泄漏 | 二、5. 异步任务架构扩展性 |
| 阶段 4.3 | `restoreSession/saveSession` 抽取 `useSession`、SSE 通用 composable、`categoryLabel` / `truncate` 通用化 | 三、中期 |
| 阶段 4.3 | `SettingsView` 与 `App.vue` 设置逻辑、`SearchView` 与 `LibraryView` 文件夹推荐逻辑重复 | 三、中期 |
| 阶段 4.4 | `POST /api/papers/{id}/enrich-metadata` nice-to-have | 一、5. PDF 解析升级后自动触发 |
| 阶段 4.10 | 真实 DeepSeek/Kimi Key 下精读稳定性、Phase 5 流式 SSE LangChain4j | 一、9. AI 输出质量保证 |
| 阶段 5 / 6 | 前端功能人工体验验证、Docker 部署待验证 | 二、10. 部署方案落地 |

### 来自 `docs/ai-features-roadmap.md`

| 来源 | 原内容 | 合并位置 |
|------|--------|----------|
| 阶段三 Harness | Token 预算、降级策略、人机确认、Metrics | 一、9. / 二、5. / 二、9. |

---

## 五、给简历/面试的一句话定位

> 本项目是一个**本地运行的 AI 科研助手**，核心亮点是把 LLM、异步任务、可暂停工作流、人机确认做成了一个可运行的系统。当前最大短板是**没有向量检索/RAG 和高质量 PDF 解析**，补齐这两项后，可以从「文献管理器」升级为真正的「科研 Agent」。

面试最可能被追问的三个问题，建议提前准备：

1. **为什么用 Java 而不是 Python 做 AI？**
   - 答：为了练习后端工程与 Agent 平台能力；AI 能力通过 LangChain4j 接入，未来 ML 服务可拆 Python 微服务。

2. **为什么不做向量检索？**
   - 答：MVP 阶段为了降低复杂度暂时跳过；当前已将其列为 P0 改进项，计划用 pgvector/Qdrant 补齐。

3. **自研 Workflow Engine 和 Temporal 怎么选？**
   - 答：MVP 自研是为了轻量和学习；当需要并行、分支、补偿时会评估 Temporal，目前已明确边界并记录。
