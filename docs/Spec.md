# Research Assistant 项目规格

## 产品定位

Research Assistant 是本地运行的科研助手，目标是把论文管理、PDF 阅读、论文理解、Agent 连续对话和研究档案连接成可恢复、可追溯的系统。

## 当前架构

```text
Vue 3 / Vite
      │ REST / SSE
Spring Boot / LangChain4j
      ├─ 文库、标注与研究档案
      ├─ Agent Turn/Run/ToolCall、自然语言澄清与 ActionTicket
      ├─ 论文结构、画像、稳定来源、严格 cite 与页面定位
      └─ 异步任务及对话/文档双角色模型适配
      │
MySQL / 本地 PDF
```

| 层 | 当前实现 |
|---|---|
| 前端 | Vue 3、Vite、Element Plus、vxe-table、PDF.js canvas、PDFium/WASM 交互层 |
| 后端 | Java 17、Spring Boot 3.2.6、Maven、MyBatis Plus |
| 数据 | MySQL 8、Flyway、本地 PDF 文件 |
| AI | LangChain4j 1.15.1，OpenAI-compatible 与 Gemini Native，对话/文档双角色配置 |
| PDF | PDFBox 版面事实；PDFium 负责浏览器字符命中、选择和搜索 |
| 检索 | 版本化本地来源的关键词、编号、章节、类型与版面结构搜索；不使用 Embedding、向量数据库或独立 RAG |

项目当前不依赖 Redis，也不使用 Pinia。异步任务由 Spring 线程池执行，状态和结果持久化到 MySQL。

## 已实现能力

### 文库与阅读

- 文件夹、标签、筛选、批量操作、PDF 上传和 DOI/arXiv 元数据补全。
- PDF 搜索、缩放、高亮、下划线、选区笔记和选区批注。
- 批注列表支持跳转、完成和删除；标注范围按需编辑。
- 文字选择默认由 PDFium 提供；公式精确框选作为需要可编辑 LaTeX 时的后手。

### 论文理解与对话

- PDFBox 版面制品和版本化论文结构是服务端论文事实层。
- 导入不自动理解；用户打开论文后手动启动。优先生成 `PROFILE_READY` 画像，连续三次真实失败且本地来源可读时才开放明确受限兜底。
- 一篇论文可以创建多个上下文完全隔离的对话；选区、文件和公式都是只服务当前轮次的可选附件。
- 每轮使用持久化 Agent Turn/Run/ToolCall；刷新、澄清和客户端操作等待都从数据库恢复。
- 论文事实必须先读取当前 PDF 版本的稳定来源，并把回答块绑定到本轮已读 `sourceObjectId`；服务端校验版本并生成 cite 预览。历史和画像只帮助理解，不能替代原文。

### Agent 与任务

- LangChain4j AI Services 负责自由文本的原生 Tool Calling 循环；通用 Agent 使用受限论文搜索/读取工具和服务端引用协议。轻量 Skill 注册表只按需描述重复能力并贡献原子工具，不编排固定流程；页面操作 Skill 仅在用户明确提出操作时开放，并继续走可信 ActionTicket，不向模型开放坐标；不新增专用 Research 工作流 Skill。
- 只有可信 `sourceObjectId` 等结构化 UI 事件可走极窄直接路径；自然语言歧义在同一输入框追问。
- 页面修改必须由服务端解析可信目标、签发 ActionTicket，并在前端真实回执后完成；不实现一键撤销。
- 异步任务支持持久化状态、取消、重试、超时、容量限制和重启恢复。
- 文章对比、外部文献检索、Gap 分析、自然语言计划、导出同步、引用网络扩展和写作助手已退出当前产品范围。

## 数据职责

| 数据 | 真源与用途 |
|---|---|
| PDF | 本地文件，原始事实载体 |
| 版面、结构、画像、研究消息、Agent 运行、任务和标注 | MySQL + Flyway |
| 浏览器字符范围与矩形 | PDFium 交互事实，用于选择、搜索和精确回链 |
| 本地来源与文本分片 | 确定性查找和回读；只有当前版本 `SourceObject/SourceLocator` 可形成 cite 与页面目标 |

所有派生产物绑定 PDF SHA-256 和解析版本；PDF 变化后旧锚点、结构、记忆和索引不得继续用于当前回答。

## 安全与运维

- Flyway 迁移目录是唯一数据库结构真源，测试使用 H2。
- API Key 可由环境变量覆盖；设置 `RA_MASTER_KEY` 后使用 AES-GCM 加密保存。
- 生产环境要求明确的 CORS 白名单、MySQL 地址和 PDF 目录。
- 日志和指标不记录密钥、完整 Prompt、论文正文或 Provider 响应体。
- 默认通过 Windows 本地启动脚本运行 MySQL、Spring Boot 和 Vite；不引入 Redis、向量数据库或额外检索服务。

## 当前重点

1. 使用真实对话与文档解析供应商完成能力探测和端到端对话验收。
2. 使用陌生单/双栏论文、公式/图表问题和提示注入样本验证 cite、定位与隔离。
3. 验证五类页面操作、重复回执、刷新恢复和同论文多对话完全隔离。

当前产品范围和稳定约束以本文档为准。
