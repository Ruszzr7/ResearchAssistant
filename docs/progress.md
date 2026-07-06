# 开发进度

## 阶段 1：项目脚手架 — 2026-06-27 ✅

**目标**：创建前后端项目骨架，验证 Vue 3 ↔ Spring Boot 通信链路畅通。

**完成内容**：
- Vue 3 前端项目搭建（Vite + Element Plus + Pinia + Vue Router + axios）
- Spring Boot 后端项目搭建（Maven + Spring Boot 3.2.6 + MyBatis Plus + MySQL + Redis 依赖）
- 顶部导航 + 四页面路由（论文库 / 检索 / 分析 / Gap）
- Vite 代理配置（`/api` → `localhost:8080`）
- CORS 配置（后端允许 `localhost:5173`）
- `GET /api/hello` 接口验证通过

**文件清单**：
- `frontend/` — Vue 3 前端
- `backend/` — Spring Boot 后端
- `docs/progress.md` — 本文件
- `docs/knowledge.md` — 知识总结（待填充）

**环境依赖**：
- Node.js v24.18.0
- Java 25 (JDK, `D:\JAVA\JDK_25`)
- Maven 3.9.9 (`C:\temp\apache-maven-3.9.9`)

**启动方式**：
- 前端：`cd frontend && npm run dev`
- 后端：`cd backend && ./mvnw spring-boot:run`

---

## 阶段 2：论文库（Paper Library）— 2026-06-29 ✅

**目标**：搭建论文库的数据库、后端 API、前端页面，实现论文管理、文件夹组织的完整功能。

**数据库**：
- MySQL 8.4 安装 + `research_assistant` 库（utf8mb4）
- 四张表：`folder`（嵌套）、`paper`（论文元数据）、`tag`（标签字典）、`paper_tag`（多对多关联）

**后端**（18 个文件）：
- 四层架构：Entity → Mapper → Service → Controller
- 统一 `Result<T>` 响应格式：`{ code, message, data }`
- 文件夹 API：CRUD + 拖拽移动（`PUT /move`）+ 论文计数
- 论文 API：分页列表 + 关键词搜索 + 文件夹/标签/状态筛选 + 排序（标题/年份/导入时间）
- 常量类：`ReadingStatus`、`AcquisitionMethod`（阿里规约——消除魔法值）

**前端**（Zotero 风格）：
- 三栏布局：左=文件夹树 + 筛选，中=表格列表 + 工具栏，右=详情面板
- 可拖拽分割线调整栏宽
- 文件夹树：路径高亮（每层递进色）、论文计数、手风琴展开
- 文件夹管理：新建弹窗、排序下拉（默认/字母/数量）、编辑弹窗（上下移/移出/移入/删除/重命名）
- 工具栏：收起侧栏、导入、搜索论文、收起详情
- 表格：斑马纹、三列可排序、点击行展示详情
- 详情面板：点击标题可编辑、分割线
- 筛选恢复：左下角阅读状态 + 标签下拉筛选

**踩坑记录**：
- Lombok 与 JDK 25 不兼容 → 手写 getter/setter
- `characterEncoding=utf8mb4` 不被 JDBC 支持 → 改为 `UTF-8`
- `abstract` 是 MySQL 保留字 + Java 关键字 → SQL 用 `AS abstract_text` 别名
- 缺少 `mvnw.cmd` → PowerShell 无法启动，补充 `.cmd` 文件
- MyBatis Plus `updateById` 默认跳过 null 字段 → 用 `UpdateWrapper` 强制写入 NULL
- Vue scoped CSS 无法穿透 Element Plus 子组件 → 内联 style 或 `:deep()` 或非 scoped style
- el-tree 改 sortOrder 不改变显示顺序 → 直接操作 children 数组
- 深层对象变更不触发 Vue 重渲染 → `:key` 计数器强制重建
- 前端按钮间距紧贴需要覆盖 Element Plus 内部 padding + min-width

---

## 阶段 2.5：数据底座完善（PDF 存储 + 自动提取）— 2026-06-30 ✅

**目标**：让论文库真正能存 PDF、自动提取文本，为阶段三的 LLM 智能处理打好数据基础。

**数据库**：
- `paper` 表加 4 列：`arxiv_id`、`source_url`、`citation_count`、`processing_status`
- 新建 `schema.sql` 完整建库脚本（含所有表 DDL）

**后端**（4 个新文件 + 6 个修改）：
- `POST /api/papers/upload` — multipart 上传 PDF + 元数据，一步入库
- `GET /api/papers/{id}/pdf` — 浏览器内嵌预览 PDF
- Apache PDFBox 3.0.4 自动提取 PDF 文本 → 存入 `aiSummary`
- Crossref API 集成：输入 DOI → 自动获取标题/作者/年份/来源
- 虚拟文件夹：`folder=uncategorized`（未分类）、`folder=recent`（最近新增）
- 上传限制提升至 50MB
- 新增 `ProcessingStatus` 常量类，`AcquisitionMethod` 补 `BROWSER_DOWNLOAD`

**前端**：
- 导入对话框重构：PDF 拖拽上传 + DOI 自动获取 → 元数据自动填充
- PDF 内嵌预览 overlay（灰色系，顶部导航保留，Esc 关闭）
- 虚拟文件夹：未分类、最近新增（左侧缩进显示）
- 编辑/删除按钮移至标题行右侧（垂直排列，删除有确认弹窗）
- 详情面板新增：获取方式、arXiv ID、来源链接、PDF 链接、PDF 提取文本

**踩坑记录**：
- el-upload 在 dialog 内产生大量 file input → 换用原生 input + 自定义拖拽区
- Spring Boot 默认上传限制 1MB → `spring.servlet.multipart.max-file-size: 50MB`
- PDFBox 3.x 移除 `RandomAccessReadBufferedFile` → 直接用 `Loader.loadPDF(File)`
- 相对路径 `./data/papers` 在 Tomcat 工作目录下找不到 → 基于 `user.dir` 解析为绝对路径
- 中文文件名导致 Content-Disposition 编码错误 → `URLEncoder + filename*=UTF-8''`
- Element Plus CSS 覆盖 header border-bottom → 换用真实 `<div>` 分割线
- `makeEmptyForm` 默认年份 2025 阻挡 DOI 自动填充 → `||` 短路问题，改用显式判断

**文件清单**：
- `backend/src/main/resources/schema.sql` — 新建
- `backend/.../constant/ProcessingStatus.java` — 新建
- `backend/.../service/PdfExtractor.java` — 新建
- `backend/pom.xml` — + PDFBox 3.0.4
- `backend/.../application.yml` — + 上传限制 + 存储路径
- `backend/.../AcquisitionMethod.java` — + BROWSER_DOWNLOAD
- `backend/.../Paper.java` — + 4 字段
- `backend/.../PaperMapper.java` — SQL 补列 + uncategorized
- `backend/.../PaperService.java` — + 2 方法
- `backend/.../PaperServiceImpl.java` — 上传逻辑
- `backend/.../PaperController.java` — upload + downloadPdf
- `frontend/src/App.vue` — 分割线
- `frontend/src/views/LibraryView.vue` — 导入重构 + overlay + 虚拟文件夹 + 按钮移位

---

## 阶段 3：Agent 核心架构（LLM 集成 + 智能检索 + 分析 + Gap）— 2026-07-02 ✅

**目标**：将论文管理工具升级为 AI 驱动的科研 Agent，具备深度阅读、结构化理解、横向对比、Gap 分析能力。

**数据库**：
- 新增 3 张表：`settings`（KV 配置）、`paper_analysis`（结构化分析结果）、`comparison`（对比记录）
- schema.sql 完整建库脚本更新

**后端**（21 个新文件）：
- **基础设施**：Settings 实体/Mapper/Service/Controller、LLMService（DeepSeek API 封装，热更新 Key）
- **处理管道**：TextPreprocessor（规则过滤）→ PaperProcessingService（编排 PDF→文本→LLM→持久化）→ AgentOrchestrator（调度入口）
- **Agent 技能**：对比分析（PaperCompare）、Gap 分析（GapAnalyzer 两步验证）、智能检索（6 步对话式 + arXiv API）、标签/文件夹建议
- **外部集成**：ArxivFetcher（arXiv API 免费查询）、LLMService（OpenAI 兼容格式）
- **API 端点**：`/api/agent/*`（处理/对比/Gap/追问/检索）、`/api/search/*`（提炼/执行/扩展/导入）、`/api/settings/*`（读写/测试连接）

**前端**（5 个新/改文件）：
- `SettingsView.vue` — API Key / 模型 / Base URL 配置页面
- `SearchView.vue` — 完整六步对话式检索（输入→提炼确认→搜索结果→扩展→入库）
- `AnalysisView.vue` — 三模式分析页（精读/对比/库内推荐 + 追问）
- `GapView.vue` — Gap 分析页（论文勾选→库内分析→外部验证 + 追问 + Markdown 导出）
- `LibraryView.vue` — 新增 AI 分析按钮、分析结果展示、处理状态标签
- `App.vue` — 导航栏设置图标 + 弹窗式 API 配置
- `api/index.js` — axios 封装（统一 baseURL + 超时 + 响应拦截）
- `router/index.js` — 新增 `/settings` 路由

**本轮修复**（Bugs → Spec 对齐）：
- AgentOrchestrator.java 缺 Map/List import
- AgentOrchestratorImpl.java 重复 import
- SearchView `doExpand()` 使用索引而非实际 paperId
- SearchController 批量导入从 TODO stub → 实际实现
- Gap 分析从单步 LLM 调用 → 两步流程（库内分析 + arXiv 外部验证）
- PdfExtractor 移除 10k 字符截断限制
- 前端导入调用同步后端新接口格式

**关键技术决策**：
- API Key 存数据库 settings 表（热更新，无需重启）
- LLM 调用后端中转（前端不暴露 Key）
- 处理异步化（processingStatus 状态机：PENDING→PROCESSING→COMPLETED/FAILED）
- arXiv API 免费公开，无需用户额外配置
- MVP 不做向量检索、不做 Vision 图表理解

**文件清单**：
- 新建：`entity/Settings.java`、`entity/PaperAnalysis.java`、`entity/Comparison.java`
- 新建：`mapper/SettingsMapper.java`、`mapper/PaperAnalysisMapper.java`、`mapper/ComparisonMapper.java`
- 新建：`service/SettingsService.java` + impl、`service/LLMService.java` + impl、`service/SearchService.java` + impl
- 新建：`service/AgentOrchestrator.java` + impl、`service/PaperProcessingService.java`
- 新建：`service/ArxivFetcher.java`、`service/TextPreprocessor.java`
- 新建：`controller/AgentController.java`、`controller/SearchController.java`、`controller/SettingsController.java`
- 新建：`frontend/src/views/SettingsView.vue`、`frontend/src/api/index.js`
- 修改：`schema.sql`、`PaperServiceImpl.java`、`App.vue`、`LibraryView.vue`、`SearchView.vue`、`AnalysisView.vue`、`GapView.vue`、`router/index.js`

---

## 阶段 4：需求对齐审计与功能补全 — 2026-07-03 ✅

**目标**：对照 `CLAUDE.md` + `Spec.md` 审计项目现状，修复 P0 安全/稳定性问题，补齐 P1 功能缺口，提升代码质量。

**审计结论**：
- 文档中四个阶段均标记完成，但代码存在依赖版本错误、SQL 注入、CORS 过宽、裸线程、标签接口缺失、Gap 验证未生效等问题。
- 功能实现对比表与修复路线图已写入计划文件 `C:\Users\26674\.claude\plans\claude-spec-shiny-parrot.md`。

**P0 — 安全与稳定性基线**：
- 前端依赖版本回退到稳定版：`vue-router ^4.3.0`、`pinia ^2.1.7`、`vite ^5.2.0`、`@vitejs/plugin-vue ^5.0.4`、`axios ^1.6.8`。
- 移除形同虚设的 Pinia（`main.js` + `package.json`）。
- 后端启用 Spring `@Async` + `ThreadPoolTaskExecutor`，替换所有裸 `new Thread()`。
- 修复 `PaperMapper` `ORDER BY ${sortDir}` SQL 注入（Service 层白名单校验）。
- 收紧 CORS：只允许 `http://localhost:5173`。
- 新增全局异常处理器 `@RestControllerAdvice`。
- Controller 参数校验：新增 DTO + `@Valid`，替换 `Map` 强制类型转换。

**P1 — 功能补齐**：

- 补齐标签生命周期：后端 `TagController` + `TagService` + Mapper 扩展；前端详情面板可增删改标签。
- 暴露文件夹 Gap 分析接口 `POST /api/agent/gap/folder/{folderId}`。
- arXiv 导入后自动触发 AI 处理（PDF 下载完成 → `processPaper`）。
- 记录 LLM token 消耗到 `paper_analysis.token_used`。
- Gap 外部验证真正生效：前端分两步调用 `/gap/internal` + `/gap/verify`。
- 统一前端 API 调用：`App.vue` 与 `LibraryView.vue` 改用 `@/api`；Crossref 改用 `fetch`。
- 清理 `AnalysisView.vue` 死代码 `formatAnalysisReport()` 与未使用的 `paperDomain`。

**P2 — 质量优化**：
- 优化论文列表 N+1 标签查询为批量 `IN` 查询。
- 移除 Redis 死依赖（`pom.xml` + `application.yml`）。
- 新增 `spring-boot-starter-validation` 依赖。

**验证结果**（环境：MySQL 8.0.28 + JDK 17 + Maven 3.9.9 + Node.js v24）：

- 前端 `npm install` + `npm run build` 通过（Vite v5.4.21），`npm run dev` 正常启动于 `localhost:5173`。
- 后端 `mvn compile` / `mvn package -DskipTests` 通过，Spring Boot 正常启动于 `localhost:8080`。
- 数据库 `schema.sql` 初始化成功，7 张表全部创建。
- 端到端接口验证（无真实 LLM Key 情况下）：
  - ✅ 文件夹：创建 / 重命名 / 删除 / 移动（异常父级已删除时正确报数据库异常）。
  - ✅ 论文：创建 / 更新 / 删除 / 分页列表 / 标签筛选 / 排序方向 SQL 注入白名单生效。
  - ✅ PDF：arXiv 导入自动下载 PDF（`2201.00978.pdf`），`/api/papers/{id}/pdf` 预览返回 200；本地上传 PDF + 自动文本提取成功。
  - ✅ 标签：创建 / 重命名 / 删除 / 分配给论文 / 按标签筛选论文。
  - ✅ 设置：读写 `api_key`、`model`、`base_url`，`/settings/test` 对假 Key 正确返回“连接失败”。
  - ✅ 检索：`/api/agent/search` arXiv 搜索、`/api/search/execute` 按关键词执行、`/api/search/expand` 扩展检索均正常。
  - ✅ Gap 验证：`/api/agent/gap/verify` 对 Gap 标题执行 arXiv 验证并返回 red/yellow/green 等级。
  - ✅ CORS：仅允许 `http://localhost:5173`，其他 origin 返回 403。
  - ✅ 全局异常：`IllegalArgumentException`、`MethodArgumentNotValidException` 等均返回统一 `Result`。

**本轮运行时修复**：
- 修复 `AsyncTaskService` ↔ `PaperServiceImpl` 循环依赖：`AsyncTaskService` 改为直接注入 `PaperMapper`。
- 修复 arXiv PDF 下载 301 问题：下载与搜索结果中的 `pdfUrl` 改用无 `.pdf` 后缀的 canonical URL，并显式开启 `HttpClient.Redirect.NORMAL`。
- 修复 `paper.processing_status` 默认值为 `PENDING`（原 `NULL`）。

**未能完成 / 需要真实 LLM Key 才能验证的项**：
- AI 论文精读 (`/agent/process/{paperId}/stream`)、横向对比 (`/agent/compare`)、Gap 库内分析 (`/agent/gap/internal`)、Agent 对话 (`/agent/chat`)、标签/文件夹推荐 (`/agent/tag-suggestions`、`/agent/folder-suggest`)。
- 设置页的“测试连接”在填入真实 `api_key` 后才能返回成功。
- LLM token 消耗写入 `paper_analysis.token_used` 的链路，需在真实 API 响应带 `usage` 字段时验证。

**文件清单**：
- 新建：`config/AsyncConfig.java`、`service/AsyncTaskService.java`、`service/LLMStreamService.java`、`service/TagService.java` + impl、`controller/TagController.java`、`common/GlobalExceptionHandler.java`、`dto/GapRequest.java`、`dto/CompareRequest.java`、`dto/ChatRequest.java`、`dto/SearchExpandRequest.java`、`dto/LlmResponse.java`
- 修改：`BackendApplication.java`、`CorsConfig.java`、`PaperMapper.java`、`PaperServiceImpl.java`、`AgentController.java`、`SearchController.java`、`LLMService.java` + impl、`PaperProcessingService.java`、`ArxivFetcher.java`、`AsyncTaskService.java`、`schema.sql`、`pom.xml`、`application.yml`、`package.json`、`main.js`、`App.vue`、`LibraryView.vue`、`AnalysisView.vue`、`GapView.vue`

---

## 阶段 4.1：长时 AI 操作交互完善 — 2026-07-06 ✅

**目标**：为所有长时间运行的 AI 操作增加取消、阶段提示与重试入口，避免用户只能被动等待，并统一资源清理防止泄漏。

**完成内容**：

- 新增可复用 composable `useCancellableTask`：统一管理 loading、阶段文案、错误、取消、重试。
- 新增 `isCancelError` 工具函数：统一识别 `AbortError` / `CanceledError`，避免取消时弹窗/报错。
- API 层静默处理取消错误，不打印错误日志。
- 四个核心视图接入：
  - `LibraryView.vue` — AI 分析按钮（取消分析 / 重试）
  - `AnalysisView.vue` — 精读分析（三阶段提示）+ 对比分析
  - `GapView.vue` — 空白分析（库内分析 → 外部验证两阶段）
  - `SearchView.vue` — 开始检索 / 确认搜索 / 扩展检索（三个独立任务实例）
- 统一使用 `AbortController` 传递 signal，轮询 timer / SSE reader 在取消或组件卸载时自动清理。

**阶段提示文案**：
- 精读：正在提交分析任务… → 等待分析完成… → 正在流式输出分析报告…
- 对比：正在生成对比报告…
- Gap：正在分析研究空白… → 正在进行外部验证…
- 检索：正在提炼检索要素… / 正在检索论文… / 正在扩展检索…

**关键修复**：
- Vue 模板中不直接消费嵌套 ref（`task.isLoading`），改为在 `<script setup>` 顶层解构为裸 ref，避免 `disabled` prop 类型错误。
- `useCancellableTask.cancel()` 不提前清空 `currentController`，由 `run` 的 `finally` 统一重置 `isLoading`，防止取消后 loading 卡住。
- 非取消错误不再从 `run` 中抛出，避免触发 Vue "Unhandled error during execution of component event handler" warning。

**验证结果**（环境：前端 dev server + 后端模拟延迟路由）：
- SearchView：点击「开始检索」后按钮变为「取消检索」，显示「正在提炼检索要素…」，点击取消后按钮恢复，Console 无 warning/error。
- 模拟后端 500 错误后，错误文案 +「重试」按钮正常出现，点击重试可再次发起请求。
- AnalysisView / GapView / LibraryView 页面加载无新增 warning。

**文件清单**：
- 新建：`frontend/src/composables/useCancellableTask.js`、`frontend/src/utils/cancel.js`
- 修改：`frontend/src/api/index.js`、`frontend/src/views/LibraryView.vue`、`frontend/src/views/AnalysisView.vue`、`frontend/src/views/GapView.vue`、`frontend/src/views/SearchView.vue`

**待后续验证**：
- MySQL 启动后，LibraryView / AnalysisView / GapView 的真实 AI 取消/重试流程。
- SSE 精读流在取消时是否正确关闭连接。

## 阶段 4.2：交互细节修复与后台任务 — 2026-07-06 ✅

**目标**：修复用户反馈的 7 项交互与功能问题，将 PDF 批注、项目打包、AI 编排优化标记为未来需求。

**完成内容**：

1. **修复大文件 PDF 上传失败**：将 `paper.ai_summary` 从 `TEXT` 改为 `MEDIUMTEXT`，并在 `PdfExtractor` 中加 15MB 长度上限，避免大 PDF 提取文本超出数据库字段限制。
2. **论文分析左侧改版**：标题从「当前论文」改为「论文选择」，选择论文后下方新增「当前论文」展示行。
3. **图标悬停提示**：为 LibraryView 左栏和工具栏的图标按钮添加 `el-tooltip` / `title` 提示。
4. **新建文件夹默认父类**：在新建文件夹的 tree-select 中增加「我的文库」虚拟根节点，避免无文件夹时显示 No data。
5. **未来需求归档**：新建 `docs/Spec.md`，将「PDF 批注与 AI 辅助批注」、「项目打包与分发部署」写入未来需求。
6. **AI 任务后台运行**：新增全局任务状态管理 `globalTaskStore` + `useGlobalTask`，文献检索、论文分析、研究空白、论文库 AI 分析切换页面后任务不取消，返回页面可恢复进度与状态。
7. **导航栏加粗**：顶部四个导航选项字体加粗。

**跳过 / 未来处理**：

- AI 编排与精读报告输出质量优化（需求 7）：涉及 Prompt 工程、后处理、结构化输出，需在后续专门迭代中设计。

**文件清单**：

- 新建：`frontend/src/stores/globalTaskStore.js`、`frontend/src/composables/useGlobalTask.js`、`docs/Spec.md`
- 修改：`frontend/src/App.vue`、`frontend/src/views/LibraryView.vue`、`frontend/src/views/AnalysisView.vue`、`frontend/src/views/GapView.vue`、`frontend/src/views/SearchView.vue`、`backend/src/main/resources/schema.sql`、`backend/src/main/java/com/research/assistant/service/PdfExtractor.java`

## 阶段 4.3：文库列表改版与年份自动提取 — 2026-07-06 ✅

**目标**：解决导入论文学年固定为 2025 的问题，并按用户要求重构文库管理界面。

**完成内容**：

1. **年份自动提取**：上传 PDF 后，若年份为空或默认值 2025，后端从 PDF 文本前 1500 字符中自动提取 4 位年份并写入 `paper.year`。
2. **文库表格加列**：新增类目、标签、状态、期刊/会议列。
   - 类目根据 `arxivId` / `source` 推断（预印本 / 期刊 / 会议 / 期刊或会议）。
   - 标签显示前两个，超出显示 `+n`。
   - 状态用 Element Tag 展示。
3. **操作列**：每行论文后新增「论文分析」「信息」「···」操作。
   - 论文分析：跳转到 AnalysisView 并自动选中该论文。
   - 信息：打开右侧详情面板。
   - ··· 下拉：编辑 / 置顶 / 重命名 / 移动 / 删除。
4. **右侧详情面板精简**：移除标题下方的编辑/AI 分析/精读/对比/推荐/删除按钮，仅保留 AI 分析状态与取消/重试入口。
5. **AnalysisView 左栏优化**：去掉刚新增的「当前论文」行；`el-select` 无数据时显示「暂无论文」。
6. **排序按钮提示**：文件夹排序按钮改用 svg `title` 实现悬停提示，避免与 `el-dropdown` 嵌套冲突。
7. **取消按钮验证**：通过 Playwright 实测 AnalysisView 精读取消、SearchView 检索取消均正常恢复按钮状态。

**验证结果（Playwright，2026-07-06）**：
- 修复 `LibraryView.vue` 右侧详情面板缺少 AI 分析触发按钮的问题（新增「AI 分析 / 取消分析」按钮，分析完成后按钮后方显示「已完成」）。
- 标签：点击表格标签单元格 → 输入/选择标签 → 保存后表格即时刷新为 `survey`。
- 阅读状态：点击状态 Tag → 切换为「正读」后成功保存并弹出「状态已更新」提示。
- 标签筛选：左下角筛选选择 `survey` 后仅显示该标签论文。
- AI 操作取消/阶段提示：
  - AnalysisView 精读：按钮变为「取消精读」，显示「正在流式输出分析报告…」，完成后恢复。
  - AnalysisView 对比：按钮变为「取消对比」，显示「正在生成对比报告…」，点击取消后恢复。
  - GapView：按钮变为「取消空白分析」，显示「正在分析研究空白…」，点击取消后恢复。
  - SearchView：「开始检索」→「取消检索」+「正在提炼检索要素…」；「确认搜索」→「取消搜索」+「正在检索论文…」，点击取消后恢复。
- 所有 AI 操作取消后弹出 `ElMessage.info('已取消')`。
- 前端 `npm run build` 通过，Console 无新增 error/warning。

**本轮 /simplify 已应用项**：
- `LibraryView.vue`：`saveTagsForPaper` 改用 `filter/map`；`initLibrary` 三个加载并行；`triggerAiAnalysis` 完成后刷新并行。
- `AnalysisView.vue`：SSE 每 part 只判断一次 event 类型；内联 markdown 转 HTML 抽离到 `frontend/src/utils/markdown.js`。
- `GapView.vue`：`parseGapsFromMarkdown` 用 `Map` 替代循环内线性查找；`buildGapReportText` 复用 `levelMeta`。

**本轮 /simplify 跳过项**（涉及较大重构或行为变更）：
- 用 Pinia 替换 `globalTaskStore`：项目已移除 Pinia，该建议不适用。
- `useGlobalTask` 与 `useCancellableTask` 提取共享核心 runner、跨页面任务闭包泄漏：需要改动任务抽象和 retry 机制，建议单独迭代。
- 各视图 `restoreSession/saveSession` 抽取 `useSession` composable、SSE 抽取通用 composable、`categoryLabel` / `truncate` 等通用化：可在后续统一封装。
- `SettingsView` 与 `App.vue` 设置逻辑、`SearchView` 与 `LibraryView` 文件夹推荐逻辑重复：可提取共享 composable，后续处理。

**文件清单**：

- 修改：`frontend/src/views/LibraryView.vue`、`frontend/src/views/AnalysisView.vue`、`frontend/src/views/GapView.vue`、`frontend/src/views/SearchView.vue`
- 新建：`frontend/src/utils/markdown.js`
- 修改：`backend/src/main/java/com/research/assistant/service/impl/PaperServiceImpl.java`、`backend/src/main/java/com/research/assistant/service/PdfExtractor.java`

## 阶段 4.4：PDF 导入自动元数据补全 — 2026-07-06 ✅

**目标**：参考 Zotero / 小绿鲸，上传 PDF 时自动从文本中提取 DOI / arXiv ID，再调用 Crossref / arXiv API 回填标题、作者、年份、期刊/会议、摘要等元数据，减少手工填写。

**完成内容**：

1. **后端标识符提取与元数据查询**：
   - 新建 `IdentifierExtractor`：从 PDF 文本中扫描 DOI 与 arXiv ID，优先返回 arXiv ID。
   - 新建 `CrossrefFetcher`：通过 DOI 查询 Crossref，解析标题/作者/年份/期刊/摘要/URL。
   - 复用现有 `ArxivFetcher.getMetadata` 获取 arXiv 元数据。
   - 新建 `MetadataNormalizer`：统一把不同来源的作者字符串转成数据库要求的 JSON 数组 `[{"name":"...","role":""}]`。
   - 新建 `MetadataEnrichmentService`：编排「提取前 5 页文本 → 识别标识符 → 查 arXiv/Crossref → 规范化 → 返回结果」。
   - 扩展 `PdfExtractor`：新增 `extractFirstPages` 与 `extractFromMultipartFile`，支持从上传流直接提取、仅扫描前 N 页。

2. **后端 API 与入库兜底**：
   - 新增 `POST /api/papers/enrich-metadata`：接收 PDF，返回 `EnrichmentResult`（即使未识别到标识符也返回 200）。
   - 新增 `POST /api/papers/{id}/enrich-metadata`：为已入库论文补全缺失字段（nice-to-have，接口已预留）。
   - `PaperServiceImpl.create/update/uploadPdfAndCreate` 中对 `authors` 做规范化兜底，保证数据库格式一致。

3. **前端导入对话框增强**：
   - 在 DOI 输入行增加「自动识别」按钮，选择 PDF 后可用。
   - 点击后上传 PDF 到 `/api/papers/enrich-metadata`，回填空字段到预览表单。
   - 预览表单扩展显示「DOI」「arXiv ID」「摘要」，方便核对。
   - 保留原 DOI 手动获取按钮作为 fallback。

4. **字段保护策略**：
   - 前后端均只回填当前为空的字段，用户已填写内容不被覆盖。
   - 文件名自动填充的标题会被保留；如需覆盖，用户可手动清空标题后再点「自动识别」。

**验证结果**：

- 后端单元测试：`IdentifierExtractorTest` 8 项、`MetadataNormalizerTest` 9 项全部通过。
- 后端 `mvn test` 全部通过。
- 前端 `npm run build` 通过。
- Playwright 端到端验证（arXiv PDF `1706.03762`）：
  - 选择 PDF 后点击「自动识别」，表单回填作者、年份 2017、来源 arXiv、arXiv ID、摘要。
  - 点击「导入论文」后，表格新增论文，详情面板显示完整元数据。
  - 原 DOI 获取按钮仍可正常工作。

**关键修复**：

- `PdfExtractor.extractFromMultipartFile` 改为读取 `byte[]` 后调用 `Loader.loadPDF(byte[])`，因为 PDFBox 3.0.4 的 `Loader` 没有 `InputStream` 重载。
- arXiv 正则必须带有 `arXiv:` / `arxiv.org/abs/` 前缀，避免把 DOI 中的 `YYYY.NNNNN` 数字段误识别为 arXiv ID。
- DOI 匹配前先将文本空白移除，解决 PDF 文本提取偶尔在 DOI 中插入空格的问题。

**文件清单**：

- 新建：
  - `backend/src/main/java/com/research/assistant/service/identifier/IdentifierExtractor.java`
  - `backend/src/main/java/com/research/assistant/service/identifier/IdentifierResult.java`
  - `backend/src/main/java/com/research/assistant/service/metadata/CrossrefFetcher.java`
  - `backend/src/main/java/com/research/assistant/service/metadata/MetadataNormalizer.java`
  - `backend/src/main/java/com/research/assistant/service/metadata/MetadataEnrichmentService.java`
  - `backend/src/main/java/com/research/assistant/dto/EnrichmentResult.java`
  - `backend/src/test/java/com/research/assistant/service/identifier/IdentifierExtractorTest.java`
  - `backend/src/test/java/com/research/assistant/service/metadata/MetadataNormalizerTest.java`
- 修改：
  - `backend/src/main/java/com/research/assistant/service/PdfExtractor.java`
  - `backend/src/main/java/com/research/assistant/controller/PaperController.java`
  - `backend/src/main/java/com/research/assistant/service/impl/PaperServiceImpl.java`
  - `frontend/src/views/LibraryView.vue`

---

## 阶段 4.5：标签管理融合与设置页 Key 显示修复 — 2026-07-06 ✅

**目标**：将全局标签管理直接融合进标签选择 UI，避免单独设置页；修复设置页保存 API Key 后刷新再进入 Key 栏变空的问题。

**完成内容**：

1. **标签管理融合进标签选择下拉框**：
   - `LibraryView.vue` 的标签编辑弹窗中，`el-select` 选项使用自定义插槽，每个标签名称右侧显示 `✕` 删除按钮。
   - 悬停标签选项时显示删除按钮，点击删除按钮可直接删除全局标签并即时刷新列表。
   - 删除标签时，如果当前论文已选中该标签，会从选中列表中移除；后端 `TagService.delete` 会级联删除 `paper_tag` 关联，避免脏数据。
   - 保留 `allow-create` 能力：用户可在同一选择框中输入新标签并保存，自动创建标签并分配给论文。

2. **设置页 API Key 显示修复**：
   - 后端 `SettingsController.getAll()` 对 `api_key` 值做脱敏处理：返回 `前缀 + **** + 后缀`（如 `sk-tes****cdef`）。
   - 前端 `SettingsView.vue` 加载时把脱敏后的 Key 填入输入框，用户可继续编辑；`doSave()` 仅在输入框内容与脱敏值不同时才提交 `api_key`，避免用掩码覆盖真实 Key。

**验证结果（Playwright，2026-07-06）**：

- 设置页输入 Key 保存后刷新，重新打开设置页显示 `sk-tes****cdef`，不会变空。
- 标签弹窗中删除 `survey` 标签，下拉框变为 No data，且该标签从所有论文上移除。
- 通过 API + UI 实测标签创建与分配：
  - 在弹窗中输入新标签并选择后保存，论文行即时显示该标签。
  - 后端 `/api/tags` 与 `/api/tags/papers/{paperId}/tags` 接口工作正常。
- 测试用临时标签 `apitag` / `uiautotag` 已清理，论文行恢复「点击添加标签」。

**关键修复**：

- `SettingsController` 不再把真实 Key 明文返回给前端，既修复显示又降低泄露风险。
- 前端保存设置时增加「值是否被用户真正修改」的判断，避免误传掩码字符串覆盖数据库。

**文件清单**：

- 修改：
  - `frontend/src/views/LibraryView.vue`
  - `frontend/src/views/SettingsView.vue`
  - `backend/src/main/java/com/research/assistant/controller/SettingsController.java`

---

## 阶段 4.6：AI 分析按钮状态补全 — 2026-07-06 ✅

**目标**：已完成的 AI 分析不应再触发重复分析，避免用户误点击。

**完成内容**：

1. **LibraryView 右侧详情面板**：当 `currentPaper.processingStatus === 'COMPLETED'` 时，禁用「AI 分析」按钮，并继续显示「已完成」状态标签。
2. **AnalysisView 精读模式**：当 `mainPaper.processingStatus === 'COMPLETED'` 时，禁用「开始精读分析」按钮。

**验证结果（Playwright，2026-07-06）**：

- 选择 `processingStatus=COMPLETED` 的论文，LibraryView 详情面板「AI 分析」按钮置灰不可点。
- 选择 `processingStatus=PENDING` 的论文，按钮可正常点击。
- `/analysis?paperId=25&mode=read`（COMPLETED）中「开始精读分析」按钮置灰；`/analysis?paperId=28&mode=read`（PENDING）按钮可用。

**文件清单**：

- 修改：
  - `frontend/src/views/LibraryView.vue`
  - `frontend/src/views/AnalysisView.vue`

---

## 阶段 4.7：标签删除与论文行交互优化 — 2026-07-06 ✅

**目标**：让标签删除入口更直观，并取消论文行点击展开详情的行为，避免误操作。

**完成内容**：

1. **标签删除按钮常驻且右对齐**：
   - `LibraryView.vue` 中 `.tag-delete-btn` 去掉 `display:none`，改为始终 `display:inline-block`。
   - `.tag-option-row` 使用 `width:100%` + `justify-content:space-between`，确保标签名在左、删除按钮在右。

2. **论文行不再点击展开详情**：
   - 移除 `<tr>` 上的 `@click="selectPaper(paper.id)"`。
   - 详情面板仅通过操作列的「信息」按钮打开。
   - 保留行高亮样式 `row-active`，用于标识当前正在查看详情的论文。

**验证结果（Playwright，2026-07-06）**：

- 点击论文行标题/单元格不再打开右侧详情面板。
- 点击「信息」按钮可正常打开详情面板。
- 标签下拉框中每个标签选项的 `✕` 删除按钮默认可见，并靠右显示。
- 测试标签已清理。

**文件清单**：

- 修改：
  - `frontend/src/views/LibraryView.vue`

---

## 阶段 4.8：置顶样式、标签删除对齐与设置弹窗 Key 显示修复 — 2026-07-06 ✅

**目标**：优化置顶行视觉标识，让标签删除按钮更贴合右边缘，并修复顶部设置弹窗保存后 Key 栏空白的问题。

**完成内容**：

1. **置顶行高亮竖条**：
   - `LibraryView.vue` 中移除 `.row-pinned` 的灰色背景覆盖，仅保留首列左侧 `box-shadow: inset 3px 0 0 0 #409eff` 蓝色高亮竖条；置顶行与普通行一样遵循白灰斑马纹或选中高亮。
2. **标签删除按钮右对齐**：
   - 去掉 `.tag-delete-btn` 的 `display:none`/悬停显示逻辑，改为始终显示。
   - `.tag-option-row` 宽度设为 `100%`、`padding-right: 0`，配合非 scoped 样式将 `.el-select-dropdown__item:has(.tag-option-row)` 的 `padding-right` 设为 `8px`，使 `✕` 与选项右边缘仅留 8px 间距。
3. **顶部设置弹窗 API Key 显示**：
   - `App.vue` 的 `loadSettings` 中把后端返回的脱敏 Key 直接填入输入框。
   - `doSave` 仅在用户输入了新的 Key（与脱敏值不同且非空）时才提交 `api_key`，避免用掩码覆盖真实 Key。

**验证结果（Playwright，2026-07-06）**：

- 将论文置顶后，首列左侧出现蓝色竖条；取消置顶后竖条消失。
- 标签下拉框中 `✕` 与选项右侧间距约 8px，默认可见。
- 顶部设置弹窗保存新 Key 后关闭，再次打开显示 `sk-new****cdef`；设置页 `/settings` 同样显示脱敏后的 Key。
- 论文行点击不再展开详情，「信息」按钮仍可打开详情。

**注意**：验证过程中为了测试保存流程，数据库中的 `api_key` 被临时改为测试值 `sk-newtest1234567890abcdef`，请在设置页重新填入你的真实 Key。

**文件清单**：

- 修改：
  - `frontend/src/views/LibraryView.vue`
  - `frontend/src/App.vue`

---

## 阶段 4.9：LangChain4j 集成 Phase 0 + Phase 1 — 2026-07-06 ✅

**目标**：在不破坏现有功能的前提下引入 LangChain4j 做 AI 编排，先解决“结构化输出不稳定”这一最痛问题。

**完成内容**：

1. **依赖接入**：
   - `backend/pom.xml` 新增 `langchain4j` + `langchain4j-open-ai`（版本 `1.0.0`），不使用 starter 的静态自动配置。

2. **动态模型工厂**：
   - 新建 `LangChain4jModelFactory`，从 `settings` 表热读 `api_key` / `base_url` / `model`。
   - 复现原 `LLMServiceImpl` 的 base URL 规范化（兼容带/不带 `/v1`、斜杠）与 `kimi-k2.7-code` 温度=1.0 逻辑。

3. **非流式 LLM 调用迁移**：
   - 重写 `LLMServiceImpl.chat()` / `chatWithUsage()`，内部改走 `ChatModel.chat(...)`。
   - 保留 `LLMService` 接口与 `LLMStreamService` 委托，前后端 URL/DTO 不变。

4. **论文精读结构化输出 POJO 化**：
   - 新建 `ResearchAiService`（`AiServices` 接口）+ `PaperAnalysisResult` POJO（带 `@Description`）。
   - 新建 `ResearchAiConfig` 用 `AiServices.builder(...).chatModel(...)` 编程式构建代理。
   - 改造 `PaperProcessingService.process()`：优先走 POJO 输出；失败时回退到旧的手写 `extractField` 解析。
   - 通过 `Result<PaperAnalysisResult>` 获取 token usage 并写入 `paper_analysis.token_used`。

5. **单元测试**：
   - `LangChain4jModelFactoryTest`：base URL 规范化、温度解析、缺失配置抛异常。
   - `LLMServiceImplTest`：验证 content 与 token 计数、null usage 处理。
   - `PaperProcessingServiceTest`：POJO 成功路径映射到实体、POJO 失败回退到旧解析。

**验证结果**：

- `mvn clean compile` 通过，`mvn test` 28 项全部通过。
- Spring Boot 成功启动，`settings` 表配置被正确读取，无 bean 循环依赖。
- `PaperAnalysis.method_type` 仍保持旧格式 `domain|methodType`，DB schema 无需变更。

**关键踩坑**：

- LangChain4j 1.0.0 中 `AiServices` / `@SystemMessage` / `@UserMessage` 在 `langchain4j` artifact，不在 `langchain4j-core`。
- 1.0.0 API 与 0.x 差异大：`ChatModel` 替代 `ChatLanguageModel`，`chat(ChatMessage...)` 返回 `ChatResponse`，`generate(...)` 已不存在。
- `Result<T>` 才能拿到 token usage，普通 POJO 返回拿不到。
- `OpenAiChatModel` 实现 `ChatModel`，`AiServices.builder().chatModel(...)` 接收 `ChatModel`。

**文件清单**：

- 新建：
  - `backend/src/main/java/com/research/assistant/service/ai/LangChain4jModelFactory.java`
  - `backend/src/main/java/com/research/assistant/service/ai/ResearchAiService.java`
  - `backend/src/main/java/com/research/assistant/service/ai/ResearchAiConfig.java`
  - `backend/src/main/java/com/research/assistant/service/ai/PaperAnalysisResult.java`
  - `backend/src/test/java/com/research/assistant/service/ai/LangChain4jModelFactoryTest.java`
  - `backend/src/test/java/com/research/assistant/service/impl/LLMServiceImplTest.java`
  - `backend/src/test/java/com/research/assistant/service/PaperProcessingServiceTest.java`
- 修改：
  - `backend/pom.xml`
  - `backend/src/main/java/com/research/assistant/service/impl/LLMServiceImpl.java`
  - `backend/src/main/java/com/research/assistant/service/PaperProcessingService.java`

**待后续验证**：

- 真实 DeepSeek/Kimi Key 下论文精读是否成功、POJO 解析稳定性、fallback 触发率。
- 流式 SSE 改用 LangChain4j（Phase 5）。



## 阶段 4.10：LangChain4j 集成 Phase 2 — 2026-07-06 ✅

**目标**：在 Phase 0/1 基础上实现多轮对话记忆，让 Agent 追问能联系上下文。

**完成内容**：

1. **数据层**：
   - `schema.sql` 新增 `conversation` 表（`memory_id`, `role`, `content`, `created_at`）。
   - 新建 `Conversation` 实体 + `ConversationMapper`（按 `memory_id` 查询/删除）。

2. **ChatMemory 持久化**：
   - 新建 `JdbcChatMemoryStore` 实现 LangChain4j `ChatMemoryStore`，消息按 `SYSTEM/USER/AI` 角色落库。
   - `ResearchAiConfig` 注入 `ChatMemoryStore`，构建 `MessageWindowChatMemory`（maxMessages=20）。

3. **多轮对话接口**：
   - `ResearchAiService.chat(@MemoryId, @V("question"))` 返回 `Result<String>`。
   - `AgentOrchestrator.chatAbout(conversationId, context, question)` 首次调用时把角色提示 + 上下文作为 `SystemMessage` 写入记忆；后续调用直接追加用户问题。
   - `AgentOrchestrator` 保留原 `chatAbout(context, question)` 单轮方法作为退化 fallback。

4. **Controller 接入**：
   - `ChatRequest` 新增 `conversationId` 字段。
   - `POST /api/agent/chat` 与 `POST /api/agent/gap/chat` 都改为多轮版本，传入 `conversationId`。

**验证结果**：

- `mvn test` 28 项全部通过。
- 后端启动后，curl 实测 `/api/agent/chat`：
  - 第一轮：提供论文上下文并问核心贡献 → 正确回答 Transformer。
  - 第二轮：仅问“它用了什么注意力机制？” → 模型结合记忆回答 Scaled Dot-Product Attention / Multi-Head Attention。
- `/api/agent/gap/chat` 同样能基于 Gap 上下文连续对话。

**关键踩坑**：

- `ConversationMapper.deleteByMemoryId` 误用 `@Select` 注解导致返回 null 报错，应使用 `@Delete`。
- `ResearchAiService.chat` 同时存在方法级 `@SystemMessage` 与调用方手动写入记忆的 `SystemMessage` 时，LangChain4j 会优先/覆盖注解版本，导致上下文丢失。解决：移除 chat 方法的 `@SystemMessage`，完全由调用方通过记忆注入角色 + 上下文。
- `{{it}}` 模板在存在 `@MemoryId` 与问题两个参数时无法自动映射，改用 `@V("question")` + `{{question}}`。
- 已有运行中的旧后端进程占用 8080，必须杀掉重启才能加载新代码。

**文件清单**：

- 新建：
  - `backend/src/main/java/com/research/assistant/entity/Conversation.java`
  - `backend/src/main/java/com/research/assistant/mapper/ConversationMapper.java`
  - `backend/src/main/java/com/research/assistant/service/ai/JdbcChatMemoryStore.java`
- 修改：
  - `backend/src/main/resources/schema.sql`
  - `backend/src/main/java/com/research/assistant/service/ai/ResearchAiConfig.java`
  - `backend/src/main/java/com/research/assistant/service/ai/ResearchAiService.java`
  - `backend/src/main/java/com/research/assistant/service/AgentOrchestrator.java`
  - `backend/src/main/java/com/research/assistant/service/impl/AgentOrchestratorImpl.java`
  - `backend/src/main/java/com/research/assistant/dto/ChatRequest.java`
  - `backend/src/main/java/com/research/assistant/controller/AgentController.java`

---

## 阶段 4.11：LangChain4j 集成 Phase 3 — 2026-07-06 ✅

**目标**：将项目中已有的 arXiv 搜索、Crossref 查询、PDF 文本提取等能力封装为 LangChain4j `@Tool`，让 LLM 在 Gap 验证中自主调用。

**完成内容**：

1. **工具类封装**：
   - 新建 `ResearchTools`，用 `@Tool` 暴露：
     - `searchArxiv(query, maxResults)`
     - `fetchCrossref(doi)`
     - `searchLocalPapersByTitle(keyword)`
     - `extractPdfTextByPath(pdfPath, maxPages)`
   - 工具内部调用现有 `ArxivFetcher` / `CrossrefFetcher` / `PdfExtractor` / `PaperMapper`，不重复实现网络/解析逻辑。

2. **工具型 Agent 接口拆分**：
   - 新建 `ResearchToolAgent`，仅包含需要工具调用的 `verifyGaps(gapReport)`。
   - `ResearchAiConfig` 单独构建 `ResearchToolAgent` Bean，**不绑定 ChatMemoryProvider**，避免工具任务受历史消息污染。
   - `ResearchAiService` 保持只负责论文精读与多轮对话，不加载 tools。

3. **Gap 验证改造**：
   - `AgentOrchestrator.verifyGaps(gapReport)` 优先调用 `ResearchToolAgent.verifyGaps`，解析返回的 JSON 数组。
   - 失败时回退到旧的手动关键词 + arXiv 搜索规则验证。
   - `AgentController` 的 `/api/agent/gap`、`/api/agent/gap/folder/{folderId}`、`/api/agent/gap/verify` 统一走新的 `AgentOrchestrator.verifyGaps`。

**验证结果**：

- `mvn test` 28 项全部通过。
- curl 实测 `/api/agent/gap/verify`：
  - LLM 调用 `searchArxiv` 检索每个 Gap，返回带 `evidence` 和 `relatedPapers` 的验证结果。
  - 第一个 Gap（Transformer 长序列复杂度高）被判定为 `green`，并列出多篇相关论文。
  - 第二个 Gap（大模型推理理论基础）被判定为 `yellow`，证据准确。
- 多轮对话 `/api/agent/chat` 仍正常工作，记忆未受工具 Agent 影响。

**关键踩坑**：

- 工具方法与对话方法共用同一个 `AiServices` Bean 时，LangChain4j 会为无 `@MemoryId` 的方法使用默认 memoryId，加载到历史消息中内容为 null 的 AI 记录后会抛 `IllegalArgumentException: text cannot be null`。
- 解决：将需要工具但不需要记忆的方法拆到独立的 `ResearchToolAgent`，配置时不设 `chatMemoryProvider`。
- 数据库中 `conversation` 表曾残留 `memory_id='default'` 的脏数据（来自早期调试），清理后对话恢复正常。

**文件清单**：

- 新建：
  - `backend/src/main/java/com/research/assistant/service/ai/ResearchTools.java`
  - `backend/src/main/java/com/research/assistant/service/ai/ResearchToolAgent.java`
- 修改：
  - `backend/src/main/java/com/research/assistant/service/ai/ResearchAiConfig.java`
  - `backend/src/main/java/com/research/assistant/service/ai/ResearchAiService.java`
  - `backend/src/main/java/com/research/assistant/service/AgentOrchestrator.java`
  - `backend/src/main/java/com/research/assistant/service/impl/AgentOrchestratorImpl.java`
  - `backend/src/main/java/com/research/assistant/controller/AgentController.java`

---

## 阶段 4.12：LangChain4j 集成 Phase 4 — 2026-07-07 ✅

**目标**：补齐 Agent 推荐能力：标签建议、文件夹推荐、阅读状态推荐，并把标签建议 UI 接线。

**完成内容**：

1. **结构化输出 POJO**：
   - 新建 `SuggestionPojos`：
     - `TagSuggestionResult`（tags 列表）
     - `FolderSuggestionResult`（folderId / reason / suggestNew / newName）
     - `ReadingStatusSuggestionResult`（status / reason）

2. **工具型 Agent 扩展推荐方法**：
   - `ResearchToolAgent` 新增 `suggestTags`、`suggestFolder`、`suggestReadingStatus`。
   - 统一使用 `@SystemMessage` + `@UserMessage("{{xxx}}")` + `@V` 参数注入，返回 `Result<POJO>`。

3. **AgentOrchestrator 推荐逻辑升级**：
   - `suggestTags`：优先走 POJO 输出；失败回退到旧字符串逗号拆分。
   - `suggestFolder` / `suggestFolderByTitle`：优先走 POJO 输出；失败回退到旧 JSON 字符串解析。
   - 新增 `suggestReadingStatus`：返回 `{status, reason}`，状态归一化为 `UNREAD/READING/READ`。

4. **Controller 暴露新端点**：
   - `POST /api/agent/reading-status-suggest`
   - 已有 `/api/agent/tag-suggestions`、`/api/agent/folder-suggest` 改为内部走新 POJO 路径。

5. **前端 UI 接线（LibraryView.vue）**：
   - 标签编辑对话框增加「AI 推荐标签」按钮：调用后端建议接口，自动创建不存在的标签并勾选。
   - 右侧详情面板阅读状态选择器旁增加「AI 推荐」按钮：调用 `/agent/reading-status-suggest` 并自动保存。
   - 导入对话框的「Agent 推荐文件夹」保持原有链路，后端已升级为 POJO 路径。

**验证结果**：

- `mvn test` 28 项全部通过。
- 前端 `npm run build` 通过。
- curl 实测：
  - `/api/agent/tag-suggestions` paperId=28 → `["Transformer","Attention Mechanism","Machine Translation","Sequence-to-Sequence Models","Encoder-Decoder"]`。
  - `/api/agent/folder-suggest` paperId=28 → 建议新建 `Deep Learning` 文件夹并给出理由。
  - `/api/agent/reading-status-suggest` paperId=28 → `{"status":"READING","reason":"..."}`。
- 前端功能待用户进入页面后人工体验验证。

**关键踩坑**：

- POJO 字段注解 `@Description` 必须使用 `dev.langchain4j.model.output.structured.Description`，而不是 `dev.langchain4j.service.Description`。
- 工具型 Agent 仍然不要绑定 ChatMemoryProvider，推荐方法也放在 `ResearchToolAgent` 中。

**文件清单**：

- 新建：
  - `backend/src/main/java/com/research/assistant/service/ai/SuggestionPojos.java`
- 修改：
  - `backend/src/main/java/com/research/assistant/service/ai/ResearchToolAgent.java`
  - `backend/src/main/java/com/research/assistant/service/AgentOrchestrator.java`
  - `backend/src/main/java/com/research/assistant/service/impl/AgentOrchestratorImpl.java`
  - `backend/src/main/java/com/research/assistant/controller/AgentController.java`
  - `frontend/src/views/LibraryView.vue`

---

## 阶段 4.13：LangChain4j 集成 Phase 5 — 2026-07-07 ✅

**目标**：将流式 SSE 精读分析迁移到 LangChain4j `StreamingChatModel`，同时保留对 Provider 异常流式 JSON 的手动回退。

**完成内容**：

1. **流式模型工厂**：
   - `LangChain4jModelFactory.createStreamingModel()` 已存在，直接用于 `LLMStreamService`。

2. **`LLMStreamService` 重构**：
   - 优先调用 `StreamingChatModel.chat(List<ChatMessage>, StreamingChatResponseHandler)`。
   - 每条 `onPartialResponse` 立即以 `event:token` 推送给前端。
   - `onCompleteResponse` 写入 `event:done` 并记录 token 消耗。
   - 增加 `outputClosed` 标志：一旦写入失败（如客户端断开）立即停止后续推送，避免日志刷屏。

3. **手动 SSE 回退**：
   - 当 LangChain4j 在尚未输出任何 token 时就失败（如 Kimi 的 `reasoning_content` 返回非法 JSON 导致其内部 Jackson 解析失败），自动回退到旧的手动 `HttpClient` + SSE 解析。
   - 回退逻辑保留 `reasoning_content` → `content` 的兼容处理。

4. **接口协议不变**：
   - 前端仍接收 `event:token` / `event:done` / `event:error`，无需改动。

**验证结果**：

- `mvn test` 28 项全部通过。
- curl 实测 `/api/agent/process/28/stream`：
  - 首先尝试 LangChain4j 流式路径。
  - 成功收到连续的 `event:token` 数据流，最终 `event:done`。
  - 若 Provider 返回非法 JSON，日志显示回退到手动 SSE 解析。

**关键踩坑**：

- `StreamingChatResponseHandler` 在 LangChain4j 1.0.0 中的方法名为 `onPartialResponse` / `onCompleteResponse` / `onError`，不是 0.x 的 `onNext` / `onComplete`。
- Kimi 等 Provider 的 `reasoning_content` 流式片段可能包含非法 JSON，LangChain4j 会调用 `onError` 并停止；保留手动回退是生产环境必需的。
- 客户端断开后，`StreamingChatModel` 仍可能继续回调，必须加 `outputClosed` 标志避免无限 IOException 刷屏。

**文件清单**：

- 修改：
  - `backend/src/main/java/com/research/assistant/service/LLMStreamService.java`

---

**目标**：将当前开发态项目转为可分发态，使他人拉取项目后能通过 Docker 一键部署，无需手动安装 JDK、Node.js、MySQL。

**推荐方案**：Docker Compose 容器化部署。

**实现流程**：
1. 前端使用生产模式构建，生成静态资源文件。
2. 将前端构建产物放入后端静态资源目录，由 Spring Boot 统一提供页面服务。
3. 后端补充单页应用路由回退配置，确保浏览器刷新非根路由时不出现 404。
4. 后端使用 Maven 打包成可执行 JAR。
5. 为后端编写 Dockerfile，采用多阶段构建：第一阶段用 Maven 镜像编译打包，第二阶段用 JRE 镜像运行。
6. 在项目根目录编写 docker-compose.yml，编排 MySQL 和后端两个服务：
   - MySQL 服务使用官方镜像，挂载数据卷持久化，并通过初始化脚本自动建表。
   - 后端服务基于后端 Dockerfile 构建，依赖 MySQL 健康检查通过后再启动，PDF 存储目录映射到宿主机以防容器重启丢失。
7. 他人部署时只需安装 Docker Desktop，在项目根目录执行容器编排启动命令，等待服务初始化后访问本地端口即可。

**待验证事项**：

- 前端嵌入后端后，直接访问和刷新各路由页面是否正常。
- PDF 上传与预览在容器环境中路径是否正确。
- MySQL 初始化脚本是否能正确创建所有表。
- 设置页配置 DeepSeek API Key 后，各 AI 功能是否正常工作。

**暂不执行**：当前阶段先继续完善功能与测试，待核心功能稳定后再进入打包阶段。

