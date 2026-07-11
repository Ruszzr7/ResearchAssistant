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

- [ ] 结构化输出增加 schema 校验、字段补全和自动修复。
- [ ] 为 `analyze-paper`、`compare-papers`、`analyze-gaps` 建立 20–50 篇 golden eval 集。
- [ ] 增加 prompt 版本管理、token 预算、调用耗时和失败率记录。
- [ ] 为外部来源和 Embedding 增加统一超时、限流、重试和降级指标。

## P1：工程化

- [ ] 用 Flyway 或 Liquibase 统一替代手写数据库升级脚本。
- [ ] 将异步任务从进程内线程池演进为可重试、可限流、有死信记录的任务队列。
- [ ] 为 `PENDING_USER` 任务增加 TTL、过期状态和恢复策略。
- [ ] 增加接口契约测试、启动健康检查和 Micrometer metrics。
- [ ] 提供 Dockerfile、MySQL 初始化和前后端一键部署方案。

## P2：产品体验

- [ ] 拆分 `LibraryView.vue`、`PdfViewer.vue` 等超大组件，抽取通用 session、SSE、设置和推荐逻辑。
- [ ] 优化 vxe-table、PDF.js 和 Markdown 依赖的首屏包体积。
- [ ] 增加引用关系图谱、研究主题仪表盘和更细粒度的来源证据展示。
- [ ] 评估多模型 fallback；当前 LangChain4j 已支持 OpenAI 兼容模型，但仍是单一有效配置。

## 技术边界

当前 Workflow Engine 只支持顺序步骤、持久化状态、重试和人机确认，不支持并行、条件分支、循环和补偿。工作流复杂度显著增加后再评估 Temporal / Camunda。

## 面试定位

这是一个以 Java/Spring Boot 为工程骨架、以 LangChain4j 为 AI 接入层的本地科研 Agent。核心亮点是把 LLM、RAG、异步任务、可暂停工作流和人机确认串成可运行系统；后续重点是质量评估、可观测性和部署工程化。
