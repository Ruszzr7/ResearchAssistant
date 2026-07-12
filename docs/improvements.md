# 技术债与优化路线图

本文只记录当前仍有价值的改进项。已完成事项以 `docs/progress.md` 的阶段索引和 Git 历史为准。

## 已完成能力

- 论文结构化分析已包含可复现要素、实验设置、Benchmark、公式/图表和研究主题相关度。
- 文献来源已扩展到 arXiv、Crossref、Semantic Scholar、OpenAlex、IEEE Xplore、ACM DL，并支持引用网络扩展。
- 已接入 RAG：MySQL 分片 + 内存向量存储，支持可选 Qdrant 和 LLM 重排序。
- PDF 已抽象为 `PdfParser`，支持 PDFBox fallback、外部版面恢复解析、公式/图表扩展点。
- 已完成 PDF.js 批注、AI 批注、阅读进度、笔记双向链接、BibTeX 导出、Zotero/Obsidian 同步。
- 已完成写作辅助、阅读计划、任务中心、暗色模式、命令面板和数据看板。
- API Key 支持环境变量覆盖和 AES-GCM 加密；测试已切换到 H2 内存库。

## P0：可靠性与质量保证

- [x] 将论文精读已有质量门禁扩展到 `compare-papers`、`analyze-gaps`，补齐对应 golden eval（Mission 12.2）。
- [x] 为外部来源和 Embedding 增加统一超时、限流、重试和降级指标（Mission 12.2）。

## P1：工程化

- [x] 用 Flyway 统一替代正式部署中的手写数据库升级脚本，保留旧脚本作为历史参考（Mission 13）。
- [x] 为可恢复任务补充队列容量、并发、租约和幂等边界的可测试入口，并导出任务/RAG/外部 API 指标；跨节点实机压测仍需在部署环境执行（Mission 13）。
- [x] 增加前端单测、smoke E2E 配置和生产健康/指标巡检方案；真实告警规则由部署平台按 `/actuator/metrics` 接入（Mission 13）。
- [x] 提供 Dockerfile、MySQL/Flyway 初始化、前后端一键部署、备份与恢复脚本（Mission 13）。

## P2：产品体验

- [x] 抽取任务 API/轮询、文库批量选择组件，并将 PDF.js 改为可见页窗口；`LibraryView.vue` 的更深层业务拆分保留为后续低风险迭代（Mission 13）。
- [ ] 继续优化 vxe-table、PDF.js 和 Markdown 依赖的首屏包体积；本 Mission 保留既有大 chunk 警告，不做高风险打包重构。
- [x] 增加引用关系图谱、研究主题仪表盘和 RAG 一致性巡检入口（Mission 13）。
- [ ] 评估多模型 fallback；当前 LangChain4j 已支持 OpenAI 兼容模型，但仍是单一有效配置。

## 技术边界

当前 Workflow Engine 只支持顺序步骤、持久化状态、重试和人机确认，不支持并行、条件分支、循环和补偿。工作流复杂度显著增加后再评估 Temporal / Camunda。

## 面试定位

这是一个以 Java/Spring Boot 为工程骨架、以 LangChain4j 为 AI 接入层的本地科研 Agent。核心亮点是把 LLM、RAG、异步任务、可暂停工作流和人机确认串成可运行系统；后续重点是质量评估、可观测性和部署工程化。
- [x] Mission 11.1：敏感配置统一脱敏、生产加密 fail-closed、请求 ID、全局异常脱敏、主要 DTO 校验、Controller 契约测试和 PDF 路径 containment。
- [x] Mission 11.2：Folder/Tag/Workflow/Agent/Search/Paper 批量请求契约收敛、核心 MockMvc 测试和 MySQL/Backend readiness 脚本。
- [x] Mission 11.3: Paper write, Search execute/import, and Workflow confirm request contracts narrowed with validation and MockMvc coverage.

## Mission 12.1 已完成

- [x] RAG provenance：稳定分片身份、版本、来源和字符定位贯穿 MySQL、内存/Qdrant、检索上下文、Gap 与写作引用。
- [x] Evidence validation：后端候选集校验、snippet 包含关系、未知 ID 拒绝、`VERIFIED/UNVERIFIED` 状态与前端展示。
- [x] RAG index/retrieval reliability：active 版本发布顺序、Qdrant 新旧版本切换保护、内存降级、结构化检索状态和指标。
- [x] RAG Golden Eval：本地 fixture、recall@5/MRR/grounded rate、失败原因统计和查询 API。

## Mission 12.2 已完成

- [x] compare-papers / analyze-gaps 确定性质量门禁与 fail-closed 持久化边界。
- [x] compare/gap Golden Eval fixture、通过率、失败原因与查询 API。
- [x] 外部来源和 Embedding 统一超时、重试、并发许可、降级状态与 Micrometer 指标。
