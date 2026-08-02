# Research Assistant 项目规格

## 产品定位

Research Assistant 是本地运行的科研助手，目标是把论文管理、PDF 阅读、论文理解、连续问答、研究档案和写作辅助连接成可恢复、可追溯的工作流，而不是替代 Zotero 或训练模型。

## 当前架构

```text
Vue 3 / Vite
      │ REST / SSE
Spring Boot / LangChain4j
      ├─ 文库、标注、研究档案、写作
      ├─ 论文结构、记忆、对话与 Evidence Gate
      ├─ 固定 Workflow、异步任务与模型适配
      └─ 版面混合检索及本地文本兼容索引
      │
MySQL / 本地 PDF
```

| 层 | 当前实现 |
|---|---|
| 前端 | Vue 3、Vite、Element Plus、vxe-table、PDF.js canvas、PDFium/WASM 交互层 |
| 后端 | Java 17、Spring Boot 3.2.6、Maven、MyBatis Plus |
| 数据 | MySQL 8、Flyway、本地 PDF 文件 |
| AI | LangChain4j 1.15.1，多供应商 OpenAI-compatible Chat API |
| PDF | PDFBox 版面事实；PDFium 负责浏览器字符命中、选择和搜索 |
| 检索 | 工作台使用版面多路召回、融合排序和精确回链；旧 Skill 使用 MySQL 本地文本索引 |

项目当前不依赖 Redis，也不使用 Pinia。异步任务由 Spring 线程池执行，状态和结果持久化到 MySQL。

## 已实现能力

### 文库与阅读

- 文件夹、标签、筛选、批量操作、PDF 上传和 DOI/arXiv 元数据补全。
- PDF 搜索、缩放、高亮、下划线、选区笔记和选区批注。
- 批注列表支持跳转、完成和删除；标注范围按需编辑。
- 文字选择默认由 PDFium 提供；公式精确框选作为需要可编辑 LaTeX 时的后手。

### 论文理解与对话

- PDFBox 版面制品和版本化论文结构是服务端论文事实层。
- 全文理解生成可恢复的论文画像；只有画像就绪后开放论文对话。
- 论文助手是单一连续对话，不再拆分精读、缺陷分析和论文对比页面。
- 选区是可选附加锚点；无选区时正常检索全文，指代追问可以弱引用上一轮主题。
- 每轮问答是独立 workbench run，并绑定 conversation、PDF hash、解析版本、证据、Token 和耗时。
- 当前 evidence 是论文事实和引用的唯一来源；历史、画像和长期观察只帮助理解及检索。
- 对比文献只保留添加接口，尚未进入当前实现范围。

### Agent、任务与写作

- Skill Registry 提供原子能力；Planner/Workflow 负责编排、校验、重试和人机确认。
- 论文问答使用固定 Workflow，不允许模型绕过 Evidence Gate。
- 异步任务支持持久化状态、取消、重试、超时、容量限制和重启恢复。
- 写作项目支持论文关联、论点—证据关系、大纲、Related Work 和引用检查。

## 数据职责

| 数据 | 真源与用途 |
|---|---|
| PDF | 本地文件，原始事实载体 |
| 版面、结构、画像、会话、观察、任务、写作数据 | MySQL + Flyway |
| 浏览器字符范围与矩形 | PDFium 交互事实，用于选择、搜索和精确回链 |
| 本地文本分片 | 兼容旧 Skill 的关键词召回，不作为工作台点击证据真源 |

所有派生产物绑定 PDF SHA-256 和解析版本；PDF 变化后旧锚点、结构、记忆和索引不得继续用于当前回答。

## 安全与运维

- Flyway 迁移目录是唯一数据库结构真源，测试使用 H2。
- API Key 可由环境变量覆盖；设置 `RA_MASTER_KEY` 后使用 AES-GCM 加密保存。
- 生产环境要求明确的 CORS 白名单、MySQL 地址和 PDF 目录。
- 日志和指标不记录密钥、完整 Prompt、论文正文或 Provider 响应体。
- 默认部署为 MySQL + Spring Boot + Nginx/Vue；不引入 Redis、向量数据库或额外检索服务。

## 当前重点

1. 通过真实论文验收版面混合检索、回答块引用和正文/公式回链。
2. 根据失败样本扩充查询别名和黄金集，不为单例堆叠特殊判断。
3. 继续控制论文理解、公式识别和问答的 Token 与延迟。

详细状态见 [progress.md](progress.md)，稳定约束见 [knowledge.md](knowledge.md)。
