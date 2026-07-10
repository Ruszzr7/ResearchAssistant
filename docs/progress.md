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

## 阶段 4.14：compare/gap 异步化与代码精简 — 2026-07-07 ✅

**目标**：解决 compare/gap 等长耗时 AI 接口易超时问题，并通过 simplify 清理重复代码。

**完成内容**：

1. **异步任务基础设施**
   - 新增 `AsyncTaskStatus`、`AsyncTaskResult<T>`、`AsyncTaskManager`（内存级，支持阶段文案、取消、30 分钟过期清理）。
   - `AsyncTaskService` 提供 `submitComparePapers`、`submitGapAnalysis`、`submitGapAnalysisByFolder`，并提取公共 Gap 工作流。

2. **Controller 改造**
   - `/api/agent/compare`、`/api/agent/gap`、`/api/agent/gap/folder/{folderId}` 改为返回 `taskId`。
   - 新增 `/api/agent/task/{taskId}` 查询、`/api/agent/task/{taskId}/cancel` 取消；统一由 `AsyncTaskService` 代理，Controller 不再直接依赖 `AsyncTaskManager`。

3. **LLM 超时/重试与缓存**
   - 同步 `ChatModel` 超时 60s + `maxRetries(1)`，避免单次慢请求挂死。
   - `RecommendationCache` 改为通用 `get(type, paperId)` / `put(type, paperId, value)`，统一标签/文件夹/阅读状态缓存。

4. **代码精简**
   - `AgentOrchestratorImpl`：提取通用 `callAgentString`、复用 `context.toString()`、默认阅读状态常量、提取 `buildGapContext` 消除重复循环。
   - 新增 `LLMConfigUtil`，共享 Kimi temperature 与 Base URL 规范化逻辑。
   - 前端 `task.js` / `analysis.js` 提取通用 `poll` 辅助，统一轮询、中止、超时逻辑。

**验证结果**：

- `./mvnw test` 46 项全部通过。
- `npm run build` 前端构建成功。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/service/async/AsyncTaskStatus.java`
  - `backend/src/main/java/com/research/assistant/service/async/AsyncTaskResult.java`
  - `backend/src/main/java/com/research/assistant/service/async/AsyncTaskManager.java`
  - `backend/src/main/java/com/research/assistant/service/cache/RecommendationCache.java`
  - `backend/src/main/java/com/research/assistant/service/ai/LLMConfigUtil.java`
  - `backend/src/test/java/com/research/assistant/service/async/AsyncTaskManagerTest.java`
  - `backend/src/test/java/com/research/assistant/service/cache/RecommendationCacheTest.java`
- 修改：
  - `backend/src/main/java/com/research/assistant/service/AsyncTaskService.java`
  - `backend/src/main/java/com/research/assistant/service/impl/AgentOrchestratorImpl.java`
  - `backend/src/main/java/com/research/assistant/controller/AgentController.java`
  - `backend/src/main/java/com/research/assistant/service/ai/LangChain4jModelFactory.java`
  - `backend/src/main/java/com/research/assistant/service/LLMStreamService.java`
  - `frontend/src/utils/task.js`
  - `frontend/src/utils/analysis.js`
  - `frontend/src/views/AnalysisView.vue`
  - `frontend/src/views/GapView.vue`

---

## 阶段 4.15：异步任务状态跨重启持久化 — 2026-07-07 ✅

**目标**：解决“论文分析不能当场看完，第二天还要能看结果”的问题，让 compare/gap 等异步任务在后端重启后仍可查询。

**完成内容**：

1. **新增 `async_task` 表**
   - `backend/src/main/resources/schema.sql` 追加建表语句。
   - 字段：`task_id`、`status`、`stage_text`、`result_json`、`error`、`created_at`、`updated_at`。

2. **新增 Entity / Mapper**
   - `AsyncTaskRecord` + `AsyncTaskRecordMapper`。

3. **`AsyncTaskManager` 内存 + DB 双写**
   - 提交、阶段更新、完成/失败/取消都同步写入 `async_task`。
   - `get(taskId)` 优先读内存，未命中回查数据库并缓存回内存。
   - 取消支持内存任务和数据库中的非终态任务。

4. **重启后安全结算**
   - `@PostConstruct` 将上一次未完成的 `PENDING` / `PROCESSING` 任务全部标记为 `FAILED`，并提示“服务重启，任务中断，请重新提交”。

5. **自动建表组件**
   - 新增 `AsyncTaskSchemaInitializer`，启动时幂等地创建 `async_task` 表，避免用户漏执行 schema.sql 导致任务失败。

6. **定时清理**
   - 内存任务 30 分钟清理保留。
   - DB 任务每天 3:30 清理 7 天前的终态记录。

**验证结果**：

- `./mvnw test` 48 项全部通过。
- 后端实际启动并提交 compare 任务，重启后仍能凭旧 `taskId` 查到 `COMPLETED` 结果。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/entity/AsyncTaskRecord.java`
  - `backend/src/main/java/com/research/assistant/mapper/AsyncTaskRecordMapper.java`
  - `backend/src/main/java/com/research/assistant/service/async/AsyncTaskSchemaInitializer.java`
- 修改：
  - `backend/src/main/java/com/research/assistant/service/async/AsyncTaskManager.java`
  - `backend/src/main/java/com/research/assistant/service/async/AsyncTaskResult.java`
  - `backend/src/main/resources/schema.sql`
  - `backend/src/test/java/com/research/assistant/service/async/AsyncTaskManagerTest.java`

---

## 阶段 4.16：AI Skill Registry（技能注册表）— 2026-07-07 ✅

**目标**：把散落在 `AgentOrchestratorImpl` 中的 AI 能力拆分为独立、可复用、可测试的 Skill，为后续 Workflow / Harness 打基础。

**完成内容**：

1. **新增 Skill 抽象与注册表**
   - `Skill<I, O>` 接口：`name()` + `execute(SkillContext, I)`。
   - `SkillContext`：任务 ID、阶段文案回调、跨 Skill 共享上下文。
   - `SkillRegistry`：启动时自动收集所有 `Skill` Spring Bean。

2. **核心 Skill 拆分**
   - `AnalyzePaperSkill`：论文精读 + 状态更新。
   - `ComparePapersSkill`：多论文横向对比 + 持久化 `comparison`。
   - `AnalyzeGapsSkill`：库内 Gap 分析（支持 paperIds / folderId / 全库）。
   - `VerifyGapsSkill`：Gap 外部验证（Agent 工具调用 + arXiv 手动回退）。
   - `SuggestTagsSkill`：标签推荐 + 缓存。
   - `SuggestFolderSkill`：文件夹推荐 + 缓存（支持已有论文 / 导入前标题）。
   - `SuggestReadingStatusSkill`：阅读状态推荐 + 缓存。
   - `ChatSkill`：单轮无记忆 / 多轮记忆对话。

3. **`AgentOrchestratorImpl` 退化**
   - 仅作为统一入口，负责参数封装、事务边界、异常转换。
   - 所有业务逻辑委托给对应 Skill。

4. **新增输入/输出记录**
   - `ComparePapersInput`、`AnalyzeGapsInput`、`SuggestFolderInput`、`ChatInput`。

**验证结果**：

- `./mvnw test` 48 项全部通过。
- 后端启动后，compare / gap / tag / folder / reading-status / chat 等接口仍能正常返回。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/service/ai/skill/Skill.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/SkillContext.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/SkillRegistry.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/Skills.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/AnalyzePaperSkill.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/ComparePapersSkill.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/AnalyzeGapsSkill.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/VerifyGapsSkill.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/SuggestTagsSkill.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/SuggestFolderSkill.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/SuggestReadingStatusSkill.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/ChatSkill.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/io/*.java`
- 修改：
  - `backend/src/main/java/com/research/assistant/service/impl/AgentOrchestratorImpl.java`

---

## 阶段 4.17：Planner + PlanExecutor（自然语言自动编排 Skill）— 2026-07-07 ✅

**目标**：让 AI 编排层像 Claude 的 Plan 模式一样：接到自然语言任务后，先 plan，再自动调用合适的 Skill 执行。

**完成内容**：

1. **扩展 Skill 接口**
   - `Skill<I, O>` 增加 `description()` 与 `inputType()`，让 Planner 能“看懂”每个 Skill。
   - 8 个已有 Skill 补齐描述与输入类型。

2. **新增 Plan 数据结构**
   - `Plan`：LLM 生成的执行计划（多步骤）。
   - `PlanStep`：单一步骤，包含 `skill` 名称与 `arguments` 参数。

3. **新增 Planner**
   - 读取所有 Skill 描述，通过 system prompt 要求 LLM 返回 JSON 计划。
   - 支持多步编排，参数中可使用 `{{prev}}` / `{{stepN}}` 引用上一步输出。
   - JSON 解析失败或目标不匹配时，返回空计划并给出友好提示。

4. **新增 PlanExecutor**
   - 顺序执行 `Plan.steps()`。
   - 按 Skill 名称从 `SkillRegistry` 查找并调用。
   - 解析占位符，把上一步/指定步骤的输出注入到下一步参数中。

5. **接入异步任务**
   - `AsyncTaskService.submitPlan(String goal)`：LLM 先规划，再自动执行。
   - `AgentController` 新增 `POST /api/agent/plan`，返回 `taskId`，前端复用现有轮询机制。

6. **单元测试**
   - `PlannerTest`：验证合法 JSON 解析与非法 JSON fallback。
   - `PlanExecutorTest`：验证单步执行、`{{prev}}`、`{{stepN}}`、字符串内嵌占位符。

**验证结果**：

- `./mvnw test` 全部通过。
- 示例：
  - `POST /api/agent/plan {"goal":"对比论文 28 和 25"}` → 自动调用 `compare-papers`。
  - `POST /api/agent/plan {"goal":"分析论文 28、25、24 的研究空白并验证"}` → 自动 `analyze-gaps` → `verify-gaps`。

**关键踩坑**：

- `SkillRegistry.get` 返回 `Skill<?, ?>`，Mockito `when(...).thenReturn(...)` 因泛型不变性编译失败，改用 `doReturn(...).when(registry).get(...)`。
- 完整占位符（如 `"{{prev}}"`）应直接返回结果对象，而不是序列化成 JSON 字符串，这样 `ObjectMapper.convertValue` 才能正确映射到复杂类型字段。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/service/ai/plan/Plan.java`
  - `backend/src/main/java/com/research/assistant/service/ai/plan/PlanStep.java`
  - `backend/src/main/java/com/research/assistant/service/ai/plan/Planner.java`
  - `backend/src/main/java/com/research/assistant/service/ai/plan/PlanExecutor.java`
  - `backend/src/test/java/com/research/assistant/service/ai/plan/PlannerTest.java`
  - `backend/src/test/java/com/research/assistant/service/ai/plan/PlanExecutorTest.java`
- 修改：
  - `backend/src/main/java/com/research/assistant/service/ai/skill/Skill.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/*Skill.java`（8 个）
  - `backend/src/main/java/com/research/assistant/service/AsyncTaskService.java`
  - `backend/src/main/java/com/research/assistant/controller/AgentController.java`

---

## 阶段 4.18：Workflow Engine MVP（Gap Research 流水线）— 2026-07-07 ✅

**目标**：在 Skill Registry + Planner 的基础上，实现可复用、可观测、可重试的轻量 Workflow Engine，先落地一条固定流水线验证闭环。

**完成内容**：

1. **数据模型**
   - `async_task` 表新增 `workflow_type`、`context_json`，用于标识工作流实例并保存启动上下文。
   - 新增 `workflow_step` 表，记录每一步的输入、输出、状态、错误、开始/结束时间。
   - 新增 `WorkflowStepRecord` + `WorkflowStepMapper`。

2. **Workflow 核心**
   - `WorkflowDefinition` / `WorkflowStepDefinition`：代码级工作流模板定义。
   - `WorkflowRegistry`：注册预定义模板，MVP 只包含 `gap-research`。
   - `WorkflowArgumentResolver`：解析 `{{context.*}}`、`{{prev}}`、`{{stepN}}` 占位符。
   - `WorkflowEngine`：顺序执行步骤、持久化步骤状态、失败中断、支持从失败点重试。
   - `WorkflowService` / `WorkflowController`：提供 `POST /api/agent/workflow/gap-research`、`GET /api/agent/workflow/{taskId}`、`POST /api/agent/workflow/{taskId}/retry`。

3. **与异步任务整合**
   - 扩展 `AsyncTaskResult`，新增 `workflowType` 与 `steps`。
   - `AsyncTaskManager` 支持工作流提交与重试（指定 taskId 复用已有记录）。
   - 查询任务时自动加载工作流步骤列表，现有 `/api/agent/task/{taskId}` 无需改动即可展示步骤进度。

4. **Gap Research 模板**
   - 步骤 1：库内 Gap 分析（`analyze-gaps`）。
   - 步骤 2：外部验证（`verify-gaps`，输入上一步结果）。
   - 输出：`{"gaps": "...", "verified": [...]}`，与现有 Gap 接口兼容。

5. **单元测试**
   - `WorkflowArgumentResolverTest`：验证上下文、上一步、指定步骤、字符串内嵌占位符。
   - `WorkflowEngineTest`：验证全成功、步骤失败、从失败点重试。

**验证结果**：

- `./mvnw test` 全部通过。
- 示例：
  ```bash
  curl -X POST http://localhost:8080/api/agent/workflow/gap-research \
       -H "Content-Type: application/json" \
       -d '{"paperIds":[28,25,24]}'
  curl http://localhost:8080/api/agent/task/{taskId}
  ```
  返回包含 `workflowType=gap-research` 与 `steps` 数组。

**关键踩坑**：

- `Map.of` 不允许 `null` 值，工作流参数中 `folderId: null` 需改用 `HashMap`。
- 重试时 Lambda 捕获的 `fromIndex` 必须是 effectively final，改用 `final int[]` 包装。
- 重试复用同一个 `taskId` 会导致 `async_task` 唯一键冲突，`AsyncTaskManager` 需先查已有记录并更新状态而非插入。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/service/ai/workflow/WorkflowDefinition.java`
  - `backend/src/main/java/com/research/assistant/service/ai/workflow/WorkflowStepDefinition.java`
  - `backend/src/main/java/com/research/assistant/service/ai/workflow/WorkflowRegistry.java`
  - `backend/src/main/java/com/research/assistant/service/ai/workflow/WorkflowEngine.java`
  - `backend/src/main/java/com/research/assistant/service/ai/workflow/WorkflowArgumentResolver.java`
  - `backend/src/main/java/com/research/assistant/service/ai/workflow/WorkflowService.java`
  - `backend/src/main/java/com/research/assistant/service/ai/workflow/WorkflowStepView.java`
  - `backend/src/main/java/com/research/assistant/service/ai/workflow/WorkflowException.java`
  - `backend/src/main/java/com/research/assistant/entity/WorkflowStepRecord.java`
  - `backend/src/main/java/com/research/assistant/mapper/WorkflowStepMapper.java`
  - `backend/src/main/java/com/research/assistant/controller/WorkflowController.java`
  - `backend/src/test/java/com/research/assistant/service/ai/workflow/WorkflowEngineTest.java`
  - `backend/src/test/java/com/research/assistant/service/ai/workflow/WorkflowArgumentResolverTest.java`
- 修改：
  - `backend/src/main/resources/schema.sql`
  - `backend/src/main/java/com/research/assistant/entity/AsyncTaskRecord.java`
  - `backend/src/main/java/com/research/assistant/service/async/AsyncTaskResult.java`
  - `backend/src/main/java/com/research/assistant/service/async/AsyncTaskManager.java`
  - `backend/src/main/java/com/research/assistant/service/async/AsyncTaskSchemaInitializer.java`
  - `backend/src/test/java/com/research/assistant/service/async/AsyncTaskManagerTest.java`

---

## 阶段 4.19：任务中心（Task Center）— 2026-07-07 ✅

**目标**：给所有后端异步任务/工作流提供一个统一的可观测入口，让用户能在前端查看历史任务、步骤进度、结果与错误，并支持取消和重试。

**完成内容**：

1. **后端**
   - `async_task` 表新增 `title` 字段，保存任务展示标题。
   - `AsyncTaskResult` 新增 `title` 字段，查询时从记录还原。
   - `AsyncTaskManager` 所有 `submit` 重载支持传入 `title`；列表查询 `listRecent(limit)` 从数据库加载最近任务。
   - `AsyncTaskRecordMapper` 新增 `selectRecent` / `selectRecentByWorkflowType`。
   - `AsyncTaskService` 新增 `listRecent(Integer limit)`；`compare` / `gap` / `plan` 提交时生成中文标题。
   - `AgentController` 新增 `GET /api/agent/tasks?limit=`。
   - `WorkflowEngine.submit/retry` 传入 `WorkflowDefinition.name` 或已有记录标题，使工作流任务在列表中可读。

2. **前端**
   - 新增 `TaskCenterView.vue`：表格展示任务标题、类型、状态、阶段、时间；展开抽屉展示工作流步骤 `el-steps`、结果 JSON、错误信息。
   - 页面进入时加载任务列表；对未终态任务每 3 秒自动轮询刷新。
   - 支持取消任务与重试工作流任务。
   - `App.vue` 顶部导航新增「任务中心」入口，`router/index.js` 注册 `/tasks` 路由。

3. **验证**
   - `./mvnw test` 全部通过（修复 `WorkflowEngineTest` 因 `submit` 签名变化导致的 mock 不匹配）。
   - `npm run build` 通过。

**文件清单**：

- 新增：
  - `frontend/src/views/TaskCenterView.vue`
- 修改：
  - `backend/src/main/resources/schema.sql`
  - `backend/src/main/java/com/research/assistant/entity/AsyncTaskRecord.java`
  - `backend/src/main/java/com/research/assistant/service/async/AsyncTaskResult.java`
  - `backend/src/main/java/com/research/assistant/service/async/AsyncTaskManager.java`
  - `backend/src/main/java/com/research/assistant/service/async/AsyncTaskSchemaInitializer.java`
  - `backend/src/main/java/com/research/assistant/mapper/AsyncTaskRecordMapper.java`
  - `backend/src/main/java/com/research/assistant/service/AsyncTaskService.java`
  - `backend/src/main/java/com/research/assistant/controller/AgentController.java`
  - `backend/src/main/java/com/research/assistant/service/ai/workflow/WorkflowEngine.java`
  - `backend/src/test/java/com/research/assistant/service/ai/workflow/WorkflowEngineTest.java`
  - `frontend/src/router/index.js`
  - `frontend/src/App.vue`

---

## 阶段 4.20：Paper Import 流水线 — 2026-07-07 ✅

**目标**：论文上传/创建后，自动触发 `paper-import` 工作流，完成元数据补全、标签/文件夹/阅读状态推荐、深度分析，并只把推荐结果返回给用户，由用户确认后再写入。

**完成内容**：

1. **后端**
   - 新增 `EnrichMetadataSkill`，封装 `MetadataEnrichmentService.enrichFromPaper`，失败时返回友好降级结果。
   - `WorkflowRegistry` 注册 `paper-import` 工作流：元数据补全 → 标签推荐 → 文件夹推荐 → 阅读状态推荐 → 深度分析。
   - `WorkflowService` 新增 `submitPaperImport(Long paperId)`，`WorkflowController` 新增 `POST /api/agent/workflow/paper-import`。
   - `PaperController.create/upload` 默认触发 `paper-import` 工作流（可通过 `?runWorkflow=false` 关闭），返回 `{paper, taskId}`。

2. **前端**
   - 新增 `usePaperImportRecommendations.js` composable：轮询工作流结果、应用推荐（元数据/标签/文件夹/阅读状态）。
   - `LibraryView.vue` 上传/创建后启动轮询，详情面板显示「AI · 入库推荐」区块，支持一键应用各推荐项。

3. **验证**
   - `./mvnw test` 通过。
   - `npm run build` 通过。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/service/ai/skill/EnrichMetadataSkill.java`
  - `frontend/src/composables/usePaperImportRecommendations.js`
- 修改：
  - `backend/src/main/java/com/research/assistant/service/ai/skill/Skills.java`
  - `backend/src/main/java/com/research/assistant/service/ai/workflow/WorkflowRegistry.java`
  - `backend/src/main/java/com/research/assistant/service/ai/workflow/WorkflowService.java`
  - `backend/src/main/java/com/research/assistant/controller/WorkflowController.java`
  - `backend/src/main/java/com/research/assistant/controller/PaperController.java`
  - `frontend/src/views/LibraryView.vue`

---

## 阶段 4.21：文献调研流水线（PENDING_USER 人机确认）— 2026-07-07 ✅

**目标**：把 SearchView 的同步检索流程升级为可持久化、可中断/恢复的 `literature-survey` 工作流，支持自然语言目标 → 检索要素 → 多源检索 → 用户确认 → 批量入库。

**完成内容**：

1. **后端**
   - `AsyncTaskStatus` 新增 `PENDING_USER`（非终态）。
   - `WorkflowStepDefinition` 新增 `awaitUserInput` 字段，支持步骤执行后暂停等待用户输入。
   - `WorkflowArgumentResolver` 新增 `{{input.xxx}}` 占位符解析。
   - `WorkflowEngine` 重构：
     - 提取 `runSteps` 统一处理顺序执行、重试、恢复。
     - 执行到 `awaitUserInput` 步骤后，保存步骤输出，把任务置为 `PENDING_USER`，返回当前部分结果。
     - 新增 `confirm(taskId, userInput)`，校验状态后继续执行后续步骤。
   - `AsyncTaskManager` 新增 `setPendingUser(taskId, partialResult)`；任务线程检测到已是 `PENDING_USER` 时不再覆盖为 `COMPLETED`。
   - 新增 Skill：
     - `ExtractSearchElementsSkill`：调用 `SearchService.extractSearchParams`。
     - `MultiSourceSearchSkill`：arXiv + 本地文库检索、去重。
     - `PrepareSurveyConfirmationSkill`：格式化候选列表与默认文件夹。
     - `ImportSelectedPapersSkill`：按用户选择创建 Paper 记录并尝试下载 PDF。
   - `WorkflowRegistry` 注册 `literature-survey` 工作流。
   - `WorkflowService/Controller` 新增 `POST /api/agent/workflow/literature-survey` 与 `POST /api/agent/workflow/{taskId}/confirm`。

2. **前端**
   - `SearchView.vue` 新增「工作流模式」开关；开启后点击「开始检索」提交 `literature-survey` 并跳转任务中心。
   - `TaskCenterView.vue` 新增 PENDING_USER 确认抽屉：候选论文多选、目标文件夹选择、全选/取消全选、确认入库。

3. **验证**
   - `./mvnw test` 全部通过。
   - 新增 `WorkflowArgumentResolverTest.shouldResolveInputPlaceholder`。
   - 新增 `WorkflowEngineTest.shouldPauseWorkflowForUserInput`。
   - `npm run build` 通过。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/service/ai/skill/ExtractSearchElementsSkill.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/MultiSourceSearchSkill.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/PrepareSurveyConfirmationSkill.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/ImportSelectedPapersSkill.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/io/MultiSourceSearchInput.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/io/PrepareSurveyConfirmationInput.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/io/ImportSelectedPapersInput.java`
- 修改：
  - `backend/src/main/java/com/research/assistant/service/async/AsyncTaskStatus.java`
  - `backend/src/main/java/com/research/assistant/service/async/AsyncTaskResult.java`
  - `backend/src/main/java/com/research/assistant/service/async/AsyncTaskManager.java`
  - `backend/src/main/java/com/research/assistant/service/ai/workflow/WorkflowStepDefinition.java`
  - `backend/src/main/java/com/research/assistant/service/ai/workflow/WorkflowArgumentResolver.java`
  - `backend/src/main/java/com/research/assistant/service/ai/workflow/WorkflowEngine.java`
  - `backend/src/main/java/com/research/assistant/service/ai/workflow/WorkflowRegistry.java`
  - `backend/src/main/java/com/research/assistant/service/ai/workflow/WorkflowService.java`
  - `backend/src/main/java/com/research/assistant/controller/WorkflowController.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/Skills.java`
  - `backend/src/test/java/com/research/assistant/service/ai/workflow/WorkflowEngineTest.java`
  - `backend/src/test/java/com/research/assistant/service/ai/workflow/WorkflowArgumentResolverTest.java`
  - `frontend/src/views/SearchView.vue`
  - `frontend/src/views/TaskCenterView.vue`

---

## 阶段 4.22：提升论文 AI 分析深度 — 2026-07-08 ✅

**目标**：对应 `docs/improvements.md` 第一点「AI 分析深度不足」，在不破坏现有流式精读体验的前提下，扩展论文结构化分析字段，并支持基于用户研究主题的相关度评分。

**完成内容**：

1. **数据模型扩展**
   - `PaperAnalysisResult` 新增 `reproducibleArtifacts`、`experimentSetup`、`benchmarkResults`、`relevanceScore`、`relevanceReason` 及嵌套 POJO。
   - `PaperAnalysis` 实体新增 5 个对应字段（3 个 JSON 文本列 + 2 个 relevance 列）。
   - `schema.sql` 更新 `paper_analysis` 表结构；提供 `ALTER TABLE` 迁移脚本。

2. **LLM Prompt 与解析路径**
   - `ResearchAiService.analyzePaper` 签名改为接收 `cleanedPaperText` 与 `researchTopic`，Prompt 增加「深度提取」与「相关度评分」步骤。
   - `PaperProcessingService` fallback Prompt 同步更新，并新增 `parseNullableInt` 处理 `relevance_score`。

3. **研究主题读取**
   - `PaperProcessingService` 注入 `SettingsService`，分析时读取 `research_topic` 设置并传给 LLM。
   - 前端 `SettingsView.vue` 与 `App.vue` 设置弹窗增加「研究主题」输入框。

4. **前端结构化展示**
   - `AnalysisView.vue`「精读」模式增加「报告 / 结构化」切换。
   - 新增 `StructuredAnalysis.vue` 组件，展示核心贡献、方法概述、相关度评分、实验设置、Benchmark 结果、可复现要素、主要发现、局限性。
   - 对旧数据缺失的新字段做空状态处理，避免崩溃。

5. **测试**
   - 更新 `PaperProcessingServiceTest`，覆盖 POJO 路径与 fallback 路径的新字段映射、relevance 为空场景。

**验证**：

- `./mvnw test` 通过。
- `npm run build` 通过。

**文件清单**：

- 新增：
  - `frontend/src/components/StructuredAnalysis.vue`
- 修改：
  - `backend/src/main/java/com/research/assistant/service/ai/PaperAnalysisResult.java`
  - `backend/src/main/java/com/research/assistant/entity/PaperAnalysis.java`
  - `backend/src/main/java/com/research/assistant/service/ai/ResearchAiService.java`
  - `backend/src/main/java/com/research/assistant/service/PaperProcessingService.java`
  - `backend/src/main/resources/schema.sql`
  - `backend/src/test/java/com/research/assistant/service/PaperProcessingServiceTest.java`
  - `frontend/src/views/AnalysisView.vue`
  - `frontend/src/views/SettingsView.vue`
  - `frontend/src/App.vue`

---

## 阶段 4.23：提升 Gap 外部验证学术价值 — 2026-07-09 ✅

**目标**：对应 `docs/improvements.md` 第二点「Gap 分析学术价值偏低」。在没有向量检索的前提下，引入第二文献源 Semantic Scholar，并用 LLM 对候选论文与 Gap 做语义比对，让验证从「数结果」升级为「举证据」。

**完成内容**：

1. **新增 Semantic Scholar 检索**
   - 新建 `SemanticScholarFetcher.java`，使用 `java.net.http.HttpClient` 调用 `https://api.semanticscholar.org/graph/v1/paper/search`。
   - 返回字段与 arXiv 对齐：`title`、`authors`、`summary`、`published`、`sourceUrl`、`source`、`arxivId`、`pdfUrl`。

2. **扩展 ResearchTools**
   - 在 `ResearchTools` 中注入 `SemanticScholarFetcher`。
   - 新增 `@Tool searchSemanticScholar(query, maxResults)`，供 Agent 在 Gap 验证时调用。

3. **更新 Agent Prompt**
   - `ResearchToolAgent.verifyGaps` 的 Prompt 改为要求返回结构化 JSON：每个 Gap 含 `gapTitle`、`level`、`reason`、`evidence`（标题、来源、年份、片段、URL）。
   - 明确分级标准基于证据质量，而非结果数量。

4. **重写 VerifyGapsSkill fallback**
   - 当 Agent 路径失败时，自动回退到多源语义验证：
     - 拆分 Gap 报告，提取每个 Gap 标题与正文；
     - 为每个 Gap 生成多组搜索查询，并行搜索 arXiv + Semantic Scholar；
     - 按标题去重，限制候选 12 条；
     - 用 LLM 判断候选是否真正覆盖 Gap，并抽取证据片段；
     - 按证据数量与质量输出 red/yellow/green。
   - 最终输出结构与 Agent 路径一致，均包含 `evidence` 列表。

5. **前端 GapView 证据展示**
   - 在 Gap 卡片中新增「证据」区域，列出每条证据的标题（可点击 URL）、来源 Tag、年份、摘要片段。
   - 兼容旧格式：当 `reason`/`evidence` 缺失时，仍显示旧 `label`/`resultCount`。

6. **测试**
   - 更新 `ResearchToolsTest`：补齐 Semantic Scholar 的 mock 与构造函数，新增 `searchSemanticScholar` 用例。
   - 新增 `VerifyGapsSkillTest`：覆盖 Agent 成功路径、fallback 多源证据收集、无候选时返回 red。

**验证结果**：

- `./mvnw test` 全部通过（67 项）。
- `npm run build` 前端构建成功。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/service/SemanticScholarFetcher.java`
  - `backend/src/test/java/com/research/assistant/service/ai/skill/VerifyGapsSkillTest.java`
- 修改：
  - `backend/src/main/java/com/research/assistant/service/ai/ResearchTools.java`
  - `backend/src/main/java/com/research/assistant/service/ai/ResearchToolAgent.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/VerifyGapsSkill.java`
  - `backend/src/test/java/com/research/assistant/service/ai/ResearchToolsTest.java`
  - `frontend/src/views/GapView.vue`

---

## 阶段 4.24a：扩展文献调研来源 — 2026-07-09 ✅

**目标**：对应 `docs/improvements.md` 第 3 点「文献调研来源过窄」的近期可落地部分：抽象学术来源接口、接入多个外部来源、在 `MultiSourceSearchSkill` 中并行查询并统一去重排序。引用网络扩展（前向/后向引用、作者追踪）依赖更多 API 字段，列为 6.3.3b。

**完成内容**：

1. **抽象 `LiteratureSource` 接口**
   - 新建 `com.research.assistant.service.source.LiteratureSource`，定义 `sourceName()`、`supportsSearch()`、`search(query, maxResults)`、`searchKeywords(keywords, maxResults)`。
   - 新建 `LiteratureCandidate` record，统一字段：`title`、`authors`、`year`、`summary`、`arxivId`、`doi`、`sourceUrl`、`pdfUrl`、`source`、`externalId`；内置 `dedupeKey()` 与 `mergeSources()`。

2. **实现三个外部 Source**
   - `ArxivSource`：包装 `ArxivFetcher`，多关键词串行化并加 1s 间隔，避免 arXiv 并发限流。
   - `SemanticScholarSource`：包装 `SemanticScholarFetcher`；修改 Fetcher 请求字段增加 `paperId`，输出 `externalId` 供后续引用扩展使用。
   - `CrossrefSource`：使用 `https://api.crossref.org/works?query=...` 做关键词搜索，定位为「元数据补充源」（通常无摘要，提供 DOI/期刊/会议/年份）。

3. **新增 `LiteratureSearchService`**
   - 自动收集所有 `LiteratureSource` Bean。
   - 按来源级别并行（通过 Spring `TaskExecutor` + `CompletableFuture`），每个 Source 内部串行处理多个关键词。
   - 每个 Future 设置 30s 超时与独立异常降级，单个来源失败不影响整体。
   - 统一去重（DOI > arXiv ID > 标题+年份）并合并来源标签。
   - 简单排序：有 externalId > 有摘要 > 年份降序 > 来源数量降序。
   - 基于关键词重叠生成轻量 `recommendReason`，避免逐篇 LLM 调用。

4. **改造现有检索链路**
   - `MultiSourceSearchSkill` 改为注入 `LiteratureSearchService` + `PaperMapper`：外部多源并行检索后，再与本地文库结果合并去重。
   - `SearchServiceImpl.executeSearch` 与 `expandSearch` 复用 `LiteratureSearchService`，消除原来只走 arXiv 的单一路径。

5. **测试**
   - 新增 `LiteratureSearchServiceTest`：验证去重合并、排序、推荐理由生成。
   - 新增 `ArxivSourceTest` / `SemanticScholarSourceTest`：验证字段归一化。
   - 新增 `MultiSourceSearchSkillTest`：验证外部结果与本地结果合并。

**验证结果**：

- `./mvnw test` 全部通过（76 项）。
- `npm run build` 前端构建成功。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/service/source/LiteratureSource.java`
  - `backend/src/main/java/com/research/assistant/service/source/LiteratureCandidate.java`
  - `backend/src/main/java/com/research/assistant/service/source/ArxivSource.java`
  - `backend/src/main/java/com/research/assistant/service/source/SemanticScholarSource.java`
  - `backend/src/main/java/com/research/assistant/service/source/CrossrefSource.java`
  - `backend/src/main/java/com/research/assistant/service/source/LiteratureSearchService.java`
  - `backend/src/test/java/com/research/assistant/service/source/LiteratureSearchServiceTest.java`
  - `backend/src/test/java/com/research/assistant/service/source/ArxivSourceTest.java`
  - `backend/src/test/java/com/research/assistant/service/source/SemanticScholarSourceTest.java`
  - `backend/src/test/java/com/research/assistant/service/ai/skill/MultiSourceSearchSkillTest.java`
- 修改：
  - `backend/src/main/java/com/research/assistant/service/SemanticScholarFetcher.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/MultiSourceSearchSkill.java`
  - `backend/src/main/java/com/research/assistant/service/impl/SearchServiceImpl.java`

---

## 阶段 4.24b：按引用网络扩展文献来源 — 2026-07-09 ✅

**目标**：对应 `docs/improvements.md` 第 3 点「文献调研来源过窄」的剩余项：基于 Semantic Scholar `paperId` 实现前向引用、后向引用、作者追踪，扩展文献调研的覆盖面。

**完成内容**：

1. **持久化 Semantic Scholar paperId**
   - `paper` 表新增 `semantic_scholar_id` 列；`Paper` 实体与 `PaperMapper` 同步更新。
   - 从 Semantic Scholar 导入论文时，把 `externalId` 写入 `semanticScholarId`。

2. **扩展 `SemanticScholarFetcher`**
   - 抽取公共的 `normalizePaper(JsonNode)`，统一关键词搜索与网络扩展的字段归一化。
   - 新增：
     - `fetchCitations(s2PaperId, limit)` — 前向引用（`data[].citingPaper`）
     - `fetchReferences(s2PaperId, limit)` — 后向引用（`data[].citedPaper`）
     - `fetchAuthorPapers(authorId, limit)` — 作者其他论文
     - `fetchPaperAuthorIds(s2PaperId)` — 获取论文作者 ID 列表
   - 对 `paperId` 做 URL path 编码；支持可选 `semantic_scholar_api_key` 设置。
   - 每个方向独立降级，HTTP 非 200 或异常时返回空列表。

3. **新建 `CitationNetworkExpansionService`**
   - 支持按本地 `paperId` 或原始 `s2PaperId` 扩展。
   - 扩展方向：`forward`、`backward`、`author`；默认三项全开。
   - 复用 `SemanticScholarSource.toCandidate(...)` 做字段归一化，复用 `LiteratureSearchService.deduplicate()` 去重排序。

4. **新增 REST 接口**
   - `POST /api/search/expand/network`：请求体 `{ paperId?, s2PaperId?, directions?, limit? }`，返回候选列表、总数、方向。

5. **测试**
   - 新增 `SemanticScholarFetcherTest`：mock `HttpClient`，覆盖 citations/references/authorPapers/authorIds/编码/API key/降级。
   - 新增 `CitationNetworkExpansionServiceTest`：覆盖本地 ID 扩展、多方向合并、无 S2 ID、去重。

**验证结果**：

- `./mvnw test` 全部通过（125 项）。
- `npm run build` 前端构建成功。

**文件清单**：

- 新增：
  - `backend/src/main/resources/schema-upgrade-6.3.3b.sql`
  - `backend/src/main/java/com/research/assistant/service/source/CitationNetworkExpansionService.java`
  - `backend/src/main/java/com/research/assistant/dto/NetworkExpandRequest.java`
  - `backend/src/test/java/com/research/assistant/service/SemanticScholarFetcherTest.java`
  - `backend/src/test/java/com/research/assistant/service/source/CitationNetworkExpansionServiceTest.java`
- 修改：
  - `backend/src/main/resources/schema.sql`
  - `backend/src/main/java/com/research/assistant/entity/Paper.java`
  - `backend/src/main/java/com/research/assistant/mapper/PaperMapper.java`
  - `backend/src/main/java/com/research/assistant/service/SemanticScholarFetcher.java`
  - `backend/src/main/java/com/research/assistant/service/source/SemanticScholarSource.java`
  - `backend/src/main/java/com/research/assistant/controller/SearchController.java`
  - `docs/improvements.md`
  - `docs/progress.md`

---

## 阶段 4.25a：RAG 向量检索 MVP — 2026-07-09 ✅

**目标**：对应 `docs/improvements.md` 第 4 点「没有向量检索 / RAG」，在不引入外部向量数据库的前提下，用 MySQL + 内存向量存储搭建可运行的 RAG 骨架，让对话与 Agent 工具能召回本地论文片段。

**完成内容**：

1. **Embedding 服务**
   - 新建 `EmbeddingService`，封装 `OpenAiEmbeddingModel`（OpenAI 兼容 `/embeddings` 端点）。
   - 支持 `embed(String)` 单条与 `embedBatch(List<String>)` 批量生成 embedding。
   - 失败时抛出 `EmbeddingUnavailableException`，供上层降级。

2. **文档分块**
   - 新建 `DocumentChunker`，将 `PaperAnalysis` 的结构化字段（核心贡献、方法概述、主要发现、局限性、数据集）与原始 PDF 文本切分为 ~500 字符的 chunk。
   - 结构化字段优先作为独立 chunk；原始文本按段落/窗口切分并保留 50 字符重叠。

3. **向量存储**
   - 新建 `VectorStore` 接口：`add`、`findRelevant`、`removeByPaperId`。
   - 新建 `InMemoryVectorStore`：启动时从 `paper_chunk` 表加载，运行时增量写入/删除，通过余弦相似度召回。
   - 新建 `PaperChunk` 实体与 `PaperChunkMapper`，用于持久化 embedding。

4. **索引与召回服务**
   - 新建 `RagIndexingService`：删除旧分片 → 分块 → embedding → 写入 VectorStore/DB。
   - 新建 `RagRetrievalService`：受 `rag_enabled` 设置开关控制；失败返回空列表，保证对话不中断。
   - `AsyncTaskService` 新增 `submitRagIndex(paperId)`，论文分析完成后自动异步建索引。

5. **接入对话与 Agent 工具**
   - `ChatSkill` 集成 `RagRetrievalService`，将相关片段追加到系统上下文。
   - `ResearchTools` 新增 `@Tool searchKnowledgeBase(query)`，供 Agent 在工作流/规划中调用本地知识库。

6. **Schema 更新**
   - `schema.sql` 新增 `paper_chunk` 表。

7. **测试**
   - 新增 `DocumentChunkerTest`、`InMemoryVectorStoreTest`。
   - 更新 `ResearchToolsTest`：补齐 `RagRetrievalService` mock，新增 `searchKnowledgeBase` 用例。

**验证结果**：

- `./mvnw test` 全部通过（90 项）。
- `npm run build` 前端构建成功。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/service/embedding/EmbeddingService.java`
  - `backend/src/main/java/com/research/assistant/service/embedding/EmbeddingUnavailableException.java`
  - `backend/src/main/java/com/research/assistant/service/ai/LangChain4jModelFactory.java`（`createEmbeddingModel`）
  - `backend/src/main/java/com/research/assistant/service/rag/DocumentChunk.java`
  - `backend/src/main/java/com/research/assistant/service/rag/DocumentChunker.java`
  - `backend/src/main/java/com/research/assistant/service/rag/EmbeddedChunk.java`
  - `backend/src/main/java/com/research/assistant/service/rag/ScoredChunk.java`
  - `backend/src/main/java/com/research/assistant/service/rag/VectorStore.java`
  - `backend/src/main/java/com/research/assistant/service/rag/InMemoryVectorStore.java`
  - `backend/src/main/java/com/research/assistant/service/rag/RagIndexingService.java`
  - `backend/src/main/java/com/research/assistant/service/rag/RagRetrievalService.java`
  - `backend/src/main/java/com/research/assistant/entity/PaperChunk.java`
  - `backend/src/main/java/com/research/assistant/mapper/PaperChunkMapper.java`
  - `backend/src/test/java/com/research/assistant/service/rag/DocumentChunkerTest.java`
  - `backend/src/test/java/com/research/assistant/service/rag/InMemoryVectorStoreTest.java`
- 修改：
  - `backend/src/main/resources/schema.sql`
  - `backend/src/main/java/com/research/assistant/service/PaperProcessingService.java`
  - `backend/src/main/java/com/research/assistant/service/AsyncTaskService.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/ChatSkill.java`
  - `backend/src/main/java/com/research/assistant/service/ai/ResearchTools.java`
  - `backend/src/test/java/com/research/assistant/service/PaperProcessingServiceTest.java`
  - `backend/src/test/java/com/research/assistant/service/ai/ResearchToolsTest.java`

---

## 阶段 4.25b：接入 Qdrant 向量存储并支持 Gap 语义相似度验证 — 2026-07-09 ✅

**目标**：对应 `docs/improvements.md` 第 4 点「没有向量检索 / RAG」与第 2 点「Gap 分析学术价值偏低」，将内存向量存储升级为可选的 Qdrant 向量数据库，并把本地 RAG 召回接入 Gap 验证。

**完成内容**：

1. **依赖与部署**
   - `backend/pom.xml` 新增 `io.qdrant:client:1.13.0` 与 `grpc-protobuf` / `grpc-stub` 编译依赖。
   - 新增 `docker-compose.qdrant.yml`，Windows + Docker Desktop 可一键启动 Qdrant。

2. **向量存储配置**
   - 移除 `InMemoryVectorStore` 的 `@Component`，由 `VectorStoreConfig` 根据 `settings.vector_store_provider` 在启动时决定 primary `VectorStore` bean。
   - 默认 provider 为 `memory`，零配置/测试环境仍走内存实现。
   - provider 为 `qdrant` 时构造 `VectorStoreRouter`，Qdrant 失败时自动降级到 `InMemoryVectorStore`。

3. **Qdrant 向量存储实现**
   - 新建 `QdrantVectorStore`，使用官方 Qdrant Java gRPC 客户端。
   - 懒加载连接、懒创建 collection（Cosine 距离，维度由首个 chunk 推导）。
   - `add` 双写：先写 `paper_chunk`（MySQL fallback），再批量 upsert 到 Qdrant。
   - `findRelevant` 通过 `SearchPoints` 召回，返回 `ScoredChunk`。
   - `removeByPaperId` 按 payload 过滤删除 Qdrant 中的点，并删除 MySQL 记录。
   - 所有 Qdrant 异常包装为 `VectorStoreException`，供 router 降级。

4. **RAG 与 Gap 验证打通**
   - `AnalyzePaperSkill` 在论文分析成功后调用 `RagIndexingService.indexPaper`（失败不影响主流程）。
   - `VerifyGapsSkill` fallback 路径注入 `RagRetrievalService`，对每个 Gap 检索本地库相似片段，作为 "Local Library" 候选证据与 arXiv / Semantic Scholar 证据一起交给 LLM 评判。

5. **测试**
   - 新增 `QdrantVectorStoreTest`（mock `QdrantClient`）、`VectorStoreRouterTest`。
   - 新增 `AnalyzePaperSkillTest`。
   - 扩展 `VerifyGapsSkillTest`，覆盖本地证据分支。

**验证结果**：

- `./mvnw test` 全部通过（110 项）。
- `npm run build` 前端构建成功。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/service/rag/VectorStoreConfig.java`
  - `backend/src/main/java/com/research/assistant/service/rag/VectorStoreRouter.java`
  - `backend/src/main/java/com/research/assistant/service/rag/QdrantVectorStore.java`
  - `backend/src/main/java/com/research/assistant/service/rag/VectorStoreException.java`
  - `backend/src/test/java/com/research/assistant/service/rag/QdrantVectorStoreTest.java`
  - `backend/src/test/java/com/research/assistant/service/rag/VectorStoreRouterTest.java`
  - `backend/src/test/java/com/research/assistant/service/ai/skill/AnalyzePaperSkillTest.java`
  - `docker-compose.qdrant.yml`
- 修改：
  - `backend/pom.xml`
  - `backend/src/main/java/com/research/assistant/service/rag/InMemoryVectorStore.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/AnalyzePaperSkill.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/VerifyGapsSkill.java`
  - `backend/src/test/java/com/research/assistant/service/ai/skill/VerifyGapsSkillTest.java`
  - `docs/improvements.md`
  - `docs/progress.md`

---

## 阶段 4.26：PDF 解析器抽象与公式/图表提取扩展点 — 2026-07-09 ✅

**目标**：对应 `docs/improvements.md` 第 5 点「PDF 解析质量脆弱」，将 PDF 解析从单一 PDFBox 实现抽象为可插拔接口，并预留公式/图表外部提取命令扩展点。

**完成内容**：

1. **PDF 解析器抽象**
   - 新建 `PdfParser` 接口：`parse(File)`、`parseFirstPages(File, int)`。
   - 新建 `PdfBoxPdfParser`：基于 PDFBox 的默认实现。
   - 新建 `ExternalCommandPdfParser`：通过设置 `pdf_parser_external_enabled` / `pdf_parser_external_command` 调用 Marker / MinerU / Grobid 等外部工具；失败自动回退到 PDFBox。
   - 命令执行做路径安全检查：必须位于项目目录或临时目录、必须以 `.pdf` 结尾、使用 `ProcessBuilder` 参数列表、120s 超时。

2. **公式与图表提取**
   - 新建 `FormulaExtractor` 接口与 `ExternalLatexOcrFormulaExtractor`（外部 LaTeX-OCR 命令）。
   - 新建 `FigureExtractor` 接口与 `PdfBoxFigureExtractor`（PDFBox 提取内嵌图片）、`ExternalCommandFigureExtractor`（外部命令）。

3. **统一门面**
   - 重写 `PdfExtractor`：底层委托给 `PdfParser` / `FormulaExtractor` / `FigureExtractor`，保持原有公开方法签名不变。
   - `PaperProcessingService` 在分析时调用 `extractFormulas` / `extractFigures`，结果写入 `PaperAnalysis.formulasJson` / `figuresJson`。

4. **数据模型**
   - `PaperAnalysis` 新增 `formulasJson`、`figuresJson` 字段。
   - `schema.sql` 同步更新 `paper_analysis` 表。

5. **测试**
   - 新增 `PdfExtractorTest`：验证门面正确委托文本/公式/图表提取、缺失文件返回空。

**验证结果**：

- `./mvnw test` 全部通过（90 项）。
- `npm run build` 前端构建成功。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/service/pdf/PdfParseResult.java`
  - `backend/src/main/java/com/research/assistant/service/pdf/FigureRegion.java`
  - `backend/src/main/java/com/research/assistant/service/pdf/PdfParser.java`
  - `backend/src/main/java/com/research/assistant/service/pdf/PdfBoxPdfParser.java`
  - `backend/src/main/java/com/research/assistant/service/pdf/ExternalCommandPdfParser.java`
  - `backend/src/main/java/com/research/assistant/service/pdf/formula/FormulaExtractor.java`
  - `backend/src/main/java/com/research/assistant/service/pdf/formula/ExternalLatexOcrFormulaExtractor.java`
  - `backend/src/main/java/com/research/assistant/service/pdf/figure/FigureExtractor.java`
  - `backend/src/main/java/com/research/assistant/service/pdf/figure/PdfBoxFigureExtractor.java`
  - `backend/src/main/java/com/research/assistant/service/pdf/figure/ExternalCommandFigureExtractor.java`
  - `backend/src/test/java/com/research/assistant/service/PdfExtractorTest.java`
- 修改：
  - `backend/src/main/java/com/research/assistant/service/PdfExtractor.java`
  - `backend/src/main/java/com/research/assistant/entity/PaperAnalysis.java`
  - `backend/src/main/java/com/research/assistant/service/PaperProcessingService.java`
  - `backend/src/main/resources/schema.sql`

**待后续（阶段 4.26b）**：

- 默认接入 Marker / MinerU 并验证中文/公式 PDF 解析效果。
- 用真实 LaTeX-OCR 模型做公式识别，提升提取准确率。
- 前端 AnalysisView 增加「公式 / 图表」独立展示面板。

---

## 阶段 4.27：阅读进度管理 — 2026-07-09 ✅

**目标**：对应 `docs/improvements.md` 第 6 点「阅读工作流不完整」的第 3 个子项：增加真正的阅读进度管理（阅读时长、进度百分比、当前页），继续复用现有原生 PDF 预览器，避免引入 PDF.js 重写。

**完成内容**：

1. **数据模型**
   - `paper` 表新增 4 列：`page_count`、`current_page`、`read_seconds`、`last_read_at`。
   - `Paper` 实体新增对应字段及手写 getter/setter，`@TableField` 映射 snake_case。
   - `PaperMapper` 的 `selectById` 与 `selectPageWithFilters` 显式加入新列。
   - 提供 `schema-upgrade-6.3.6a.sql` 供旧库迁移。

2. **PDF 页数提取**
   - `PdfParser` 接口新增 `countPages(File)`。
   - `PdfBoxPdfParser` 使用 `Loader.loadPDF(file).getNumberOfPages()`，不提取文本。
   - `ExternalCommandPdfParser` 的 `countPages` 回退到 PDFBox fallback。
   - `PdfExtractor` 新增 `countPages(String pdfPath)`。
   - `PaperServiceImpl.uploadPdf` 保存 PDF 后自动提取并写入 `page_count`。

3. **阅读进度业务**
   - 新建 `ReadingProgressService`：
     - `getProgress(paperId)`：查询进度，`page_count` 缺失时懒加载并回写。
     - `updateProgress(paperId, currentPage)`：校验页码范围，计算百分比，更新 `last_read_at`，并按规则推进 `reading_status`（`UNREAD`→`READING`，读到尾页→`READ`，`READ` 可回到 `READING`）。
     - `addReadSeconds(paperId, seconds)`：累加阅读时长，不自动改状态。
   - 新建 DTO：`ReadingProgressDto`、`ReadingProgressUpdateRequest`、`ReadingTimeRequest`（带参数校验）。
   - `PaperController` 新增接口：
     - `GET /api/papers/{id}/reading-progress`
     - `POST /api/papers/{id}/reading-progress`
     - `POST /api/papers/{id}/reading-time`

4. **前端**
   - 新建 `ReadingProgressPanel.vue`：
     - 展示已读时长、进度条、当前页输入、标记已读完按钮。
     - 1 秒 interval 计时，`visibilitychange` 暂停/恢复，每 30 秒同步一次，关闭/卸载时 flush。
   - 新建 `frontend/src/api/readingProgress.js` 封装三个 API。
   - `LibraryView.vue`：
     - PDF 预览 overlay 工具栏嵌入进度面板。
     - 右侧详情面板显示简要进度（当前页/总页数、已读时长）。

5. **测试**
   - 新增 `ReadingProgressServiceTest`：覆盖懒加载页数、百分比计算、状态转换、越界校验、时长累加。

**验证结果**：

- `./mvnw test` 全部通过（97 项）。
- `npm run build` 前端构建成功。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/service/ReadingProgressService.java`
  - `backend/src/main/java/com/research/assistant/dto/ReadingProgressDto.java`
  - `backend/src/main/java/com/research/assistant/dto/ReadingProgressUpdateRequest.java`
  - `backend/src/main/java/com/research/assistant/dto/ReadingTimeRequest.java`
  - `backend/src/test/java/com/research/assistant/service/ReadingProgressServiceTest.java`
  - `backend/src/main/resources/schema-upgrade-6.3.6a.sql`
  - `frontend/src/components/ReadingProgressPanel.vue`
  - `frontend/src/api/readingProgress.js`
- 修改：
  - `backend/src/main/resources/schema.sql`
  - `backend/src/main/java/com/research/assistant/entity/Paper.java`
  - `backend/src/main/java/com/research/assistant/mapper/PaperMapper.java`
  - `backend/src/main/java/com/research/assistant/service/pdf/PdfParser.java`
  - `backend/src/main/java/com/research/assistant/service/pdf/PdfBoxPdfParser.java`
  - `backend/src/main/java/com/research/assistant/service/pdf/ExternalCommandPdfParser.java`
  - `backend/src/main/java/com/research/assistant/service/PdfExtractor.java`
  - `backend/src/main/java/com/research/assistant/service/impl/PaperServiceImpl.java`
  - `backend/src/main/java/com/research/assistant/controller/PaperController.java`
  - `frontend/src/views/LibraryView.vue`

**待后续**：

- 接入 PDF.js 后实现自动翻页同步，替代手动输入当前页。
- 将阅读进度数据用于首页数据看板（本月阅读时长、阅读完成率）。

---

## 阶段 4.28：RAG 召回 + LLM 重排序贯穿问答 / 推荐 / Gap（improvements.md 4.3）— 2026-07-10 ✅

**目标**：让 Chat、标签/文件夹/阅读状态推荐、Gap 分析/验证都优先走 RAG 召回，并用 LLM 对召回片段重排序后再进入生成 prompt。

**完成内容**：

1. **新增 `LlmReranker`**
   - 对候选片段一次性让 LLM 打分 1–10，返回按分数重排的子集。
   - LLM 调用失败或返回非法 JSON 时回退到向量排序，不阻断主流程。

2. **扩展 `RagRetrievalService`**
   - 新增 `retrieveAndRerank(query, retrieveK, minScore)` 与 `retrieveAndRerankAsContext(...)`。
   - 通过 `settings` 键控制：`rag_rerank_enabled`（默认 `false`）、`rag_rerank_top_k`、`rag_answer_top_k`、`rag_rerank_min_chunks`。
   - 重排序关闭 / 失败 / 召回数不足时自动回退到向量排序。

3. **Skill 接入 RAG + 重排序**
   - `ChatSkill`：对话追问使用 `retrieveAndRerankAsContext(question, 10, 0.65)`。
   - `VerifyGapsSkill`：本地证据收集改用 `retrieveAndRerank(...)`。
   - `SuggestTagsSkill` / `SuggestFolderSkill` / `SuggestReadingStatusSkill`：以 title+abstract 为 query 召回相关片段，作为 `relatedSnippets` 传入 `ResearchToolAgent` 与 fallback prompt。
   - `AnalyzeGapsSkill`：以每篇论文 title+coreContribution 为 query 召回片段，追加到 Gap 分析上下文。

4. **更新 `ResearchToolAgent` 推荐接口**
   - `suggestTags` / `suggestFolder` / `suggestReadingStatus` 增加 `relatedSnippets` 变量，prompt 明确可参考用户论文库片段。

5. **测试**
   - 新增 `LlmRerankerTest`：覆盖正常重排序、LLM 失败回退、非法响应回退、空候选。
   - 更新 `VerifyGapsSkillTest`：mock 由 `retrieve` 改为 `retrieveAndRerank`。

**验证结果**：

- `./mvnw test` 全部通过（129 项）。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/service/rag/LlmReranker.java`
  - `backend/src/test/java/com/research/assistant/service/rag/LlmRerankerTest.java`
- 修改：
  - `backend/src/main/java/com/research/assistant/service/rag/RagRetrievalService.java`
  - `backend/src/main/java/com/research/assistant/service/ai/ResearchToolAgent.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/ChatSkill.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/VerifyGapsSkill.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/SuggestTagsSkill.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/SuggestFolderSkill.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/SuggestReadingStatusSkill.java`
  - `backend/src/main/java/com/research/assistant/service/ai/skill/AnalyzeGapsSkill.java`
  - `backend/src/test/java/com/research/assistant/service/ai/skill/VerifyGapsSkillTest.java`
  - `docs/improvements.md`

---

## 阶段 4.29：扩展学术来源 OpenAlex + IEEE Xplore + ACM DL（improvements.md 2.3）— 2026-07-10 ✅

**目标**：在现有 arXiv / Crossref / Semantic Scholar 之外，增加 OpenAlex（免费）和 IEEE Xplore / ACM DL（机构授权）作为可选来源。

**完成内容**：

1. **新增 Fetcher（位于 `service`）**
   - `OpenAlexFetcher`：调用 `https://api.openalex.org/works?search=...`，无需 API Key。
   - `IeeeXploreFetcher`：调用 IEEE Xplore API；未配置 `ieee_xplore_api_key` 时返回空列表。
   - `AcmDlFetcher`：通过可配置的 `acm_dl_api_url` + `acm_dl_api_key` 接入；未配置时返回空列表。

2. **新增 Source 适配器（位于 `service.source`）**
   - `OpenAlexSource`、`IeeeXploreSource`、`AcmDlSource`。
   - 均实现 `LiteratureSource`，Spring 自动注入 `LiteratureSearchService`。
   - 失败或未启用时返回空列表，不影响其他来源。

3. **前端设置页**
   - `SettingsView.vue` 新增 "学术来源" 卡片：
     - OpenAlex 开关。
     - IEEE Xplore 开关 + API Key。
     - ACM DL 开关 + API URL + API Key。

4. **测试**
   - 新增 `OpenAlexSourceTest`、`IeeeXploreSourceTest`、`AcmDlSourceTest`：验证正常返回、空查询、fetcher 异常回退。

**验证结果**：

- `./mvnw test` 全部通过（138 项）。
- `npm run build` 前端构建成功。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/service/OpenAlexFetcher.java`
  - `backend/src/main/java/com/research/assistant/service/IeeeXploreFetcher.java`
  - `backend/src/main/java/com/research/assistant/service/AcmDlFetcher.java`
  - `backend/src/main/java/com/research/assistant/service/source/OpenAlexSource.java`
  - `backend/src/main/java/com/research/assistant/service/source/IeeeXploreSource.java`
  - `backend/src/main/java/com/research/assistant/service/source/AcmDlSource.java`
  - `backend/src/test/java/com/research/assistant/service/source/OpenAlexSourceTest.java`
  - `backend/src/test/java/com/research/assistant/service/source/IeeeXploreSourceTest.java`
  - `backend/src/test/java/com/research/assistant/service/source/AcmDlSourceTest.java`
- 修改：
  - `frontend/src/views/SettingsView.vue`
  - `docs/improvements.md`

**已知限制**：

- ACM DL 公开 API 访问受限，当前实现依赖用户自行提供代理/授权端点；未配置时不会报错，仅返回空结果。

---

## 阶段 4.30：Gap 验证引入引用网络 + 时间加权（improvements.md 2.4）— 2026-07-10 ✅

**目标**：让 `VerifyGapsSkill` 在验证 Gap 时，不仅搜索关键词，还对语义相关候选做前向/后向引用扩展，并按发表时间加权判断 Gap 是否已被核心工作覆盖。

**完成内容**：

1. **新增 `GapEvidenceScorer`**
   - `weightByYear(String)`：近 3 年权重 1.0，3–6 年 0.6，更早 0.3，无法解析 0.5。
   - `score(List<Map>)`：对证据列表按年份加权求和。
   - `determineLevel(double)`：总分 ≥ 1.5 为 green，≥ 0.5 为 yellow，否则 red。

2. **改造 `VerifyGapsSkill`**
   - 注入 `CitationNetworkExpansionService` 与 `LiteratureSearchService`。
   - 收集外部候选 + 本地 RAG 片段后，**仅对带有 `externalId` 的候选**调用 `expandByS2Id(forward, backward)`。
   - 扩展结果与原候选合并去重，扩展出的论文作为 `networkCoverage` 返回。
   - `determineLevel` 与 `buildReason` 改为基于加权总分，并使用 `Locale.US` 格式化分数。

3. **测试**
   - 新增 `GapEvidenceScorerTest`：覆盖年份权重、非法年份、总分计算、等级映射。
   - 更新 `VerifyGapsSkillTest`：
     - 补充网络扩展测试，验证 `networkCoverage` 包含扩展论文。
     - 补充时间加权测试，验证 2010 年证据得分 0.3 并判 red。

**验证结果**：

- `./mvnw test` 全部通过（144 项）。
- `npm run build` 前端构建成功（本阶段无前端改动）。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/service/analysis/GapEvidenceScorer.java`
  - `backend/src/test/java/com/research/assistant/service/analysis/GapEvidenceScorerTest.java`
- 修改：
  - `backend/src/main/java/com/research/assistant/service/ai/skill/VerifyGapsSkill.java`
  - `backend/src/test/java/com/research/assistant/service/ai/skill/VerifyGapsSkillTest.java`
  - `docs/improvements.md`

---

## 阶段 4.31：默认优先使用版面恢复 PDF 解析器（improvements.md 5.2）— 2026-07-10 ✅

**目标**：当用户本地配置了 Marker / MinerU / Grobid 等外部命令时，系统默认优先使用它们解析 PDF；未配置或失败时无缝回退 PDFBox。

**完成内容**：

1. **扩展 `ExternalCommandPdfParser` 选择策略**
   - 新增设置键 `pdf_parser_provider`（`PDFBOX` / `EXTERNAL`）。
   - 优先级：显式 provider > 旧开关 `pdf_parser_external_enabled` > 是否已配置外部命令。
   - 当未设置 provider 但配置了外部命令时，默认走 EXTERNAL（布局恢复优先）。
   - 外部命令未配置、执行失败或超时时，自动回退到 PDFBox。

2. **前端设置页**
   - `SettingsView.vue` 新增 "PDF 解析器" 卡片：
     - provider 下拉选择（PDFBox / 外部命令）。
     - 外部命令输入框，支持 Marker、MinerU、Grobid 等命令模板。
     - 提示命令失败会自动回退 PDFBox。

3. **测试**
   - 新增 `ExternalCommandPdfParserTest`：
     - provider=PDFBOX 时强制使用 fallback。
     - provider=EXTERNAL 但命令未配置时回退。
     - provider=EXTERNAL 且命令有效时调用外部脚本并返回其输出。
     - 未设置 provider 但配置了命令时默认走 EXTERNAL。
     - 旧 `pdf_parser_external_enabled=true` 仍生效。
     - 外部命令执行失败时回退 PDFBox。
     - `countPages` 始终使用 PDFBox。

**验证结果**：

- `./mvnw test` 全部通过（150 项）。
- `npm run build` 前端构建成功。

**文件清单**：

- 新增：
  - `backend/src/test/java/com/research/assistant/service/pdf/ExternalCommandPdfParserTest.java`
- 修改：
  - `backend/src/main/java/com/research/assistant/service/pdf/ExternalCommandPdfParser.java`
  - `frontend/src/views/SettingsView.vue`
  - `docs/improvements.md`

**已知限制**：

- 外部命令按空白字符拆分，命令路径或参数中包含空格时需用脚本包装；后续可考虑支持引号参数解析。
- 外部解析器不返回可靠页数，页数仍由 PDFBox 获取。

---

## 阶段 4.32：LaTeX-OCR + 图表类型区分与前端展示（improvements.md 5.3）— 2026-07-10 ✅

**目标**：让后端图表区域支持 FIGURE/TABLE 类型区分，外部图表命令可返回带 type 的 JSON；前端设置页开放公式与图表提取开关，分析结果页展示公式与图表/表格列表。

**完成内容**：

1. **新增 `FigureRegionType` 枚举**
   - `FIGURE`、`TABLE`。
   - 提供 `@JsonCreator from(String)`，支持大小写不敏感解析，缺省时默认 `FIGURE`。
   - `FigureRegion` 增加 `type` 字段；紧凑构造函数确保反序列化缺省字段时也不会为 null。

2. **适配现有提取器**
   - `PdfBoxFigureExtractor` 创建的嵌入图片统一标记为 `FIGURE`。
   - `ExternalCommandFigureExtractor` 的 JSON 契约支持 `type` 字段；注释更新为带 type 的示例。

3. **前端设置页**
   - `SettingsView.vue` 新增 "公式与图表" 卡片：
     - LaTeX-OCR 开关 + 命令输入。
     - 图表提取开关 + 命令输入。

4. **前端分析结果展示**
   - `StructuredAnalysis.vue` 新增：
     - "识别公式" 区域，以 `<pre>` 展示每条 LaTeX。
     - "图表与表格" 区域，按 `type` 区分标签（表格为 warning，图表为 info），显示 caption、页码与图片保存路径。

5. **测试**
   - 新增 `FigureRegionTypeTest`：验证大小写解析、默认值、JSON 序列化/反序列化。
   - 新增 `ExternalCommandFigureExtractorTest`：验证未启用/命令空白返回空、带 type 的 JSON 解析、缺省 type 回退为 FIGURE、非法 JSON 返回空。
   - 更新 `PdfExtractorTest`：适配 `FigureRegion` 新构造函数。

**验证结果**：

- `./mvnw test` 全部通过（156 项）。
- `npm run build` 前端构建成功。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/service/pdf/FigureRegionType.java`
  - `backend/src/test/java/com/research/assistant/service/pdf/FigureRegionTypeTest.java`
  - `backend/src/test/java/com/research/assistant/service/pdf/figure/ExternalCommandFigureExtractorTest.java`
- 修改：
  - `backend/src/main/java/com/research/assistant/service/pdf/FigureRegion.java`
  - `backend/src/main/java/com/research/assistant/service/pdf/figure/PdfBoxFigureExtractor.java`
  - `backend/src/main/java/com/research/assistant/service/pdf/figure/ExternalCommandFigureExtractor.java`
  - `backend/src/test/java/com/research/assistant/service/PdfExtractorTest.java`
  - `frontend/src/views/SettingsView.vue`
  - `frontend/src/components/StructuredAnalysis.vue`
  - `docs/improvements.md`

**已知限制**：

- 当前仅展示图表/表格的元信息（caption、page、imagePath），尚未提供图片文件 HTTP 访问端点；图片路径为后端本地路径，前端无法直接预览。
- 公式未做 MathJax/KaTeX 渲染，仅展示原始 LaTeX 字符串。

---

## 阶段 4.33：PDF 批注持久化 + PDF.js 阅读器（improvements.md 6.1）— 2026-07-10 ✅

**目标**：替换 LibraryView 的 iframe PDF 预览为基于 PDF.js 的阅读器，支持高亮、下划线、便签、手写圈注，并持久化到数据库。

**完成内容**：

1. **后端批注 API**
   - 新增 `paper_annotation` 表，字段：paper_id、type、page、color、note、coordinates_json。
   - 新增实体 `PaperAnnotation`、Mapper、Service、Controller。
   - DTO：`AnnotationDto`（含 coordinates Map）、`AnnotationRequest`。
   - 接口：
     - `GET /api/papers/{paperId}/annotations`
     - `POST /api/papers/{paperId}/annotations`
     - `PUT /api/annotations/{annotationId}`
     - `DELETE /api/annotations/{annotationId}`

2. **前端 PDF.js 阅读器**
   - 安装 `pdfjs-dist@4`，配置 worker。
   - 新增 `PdfViewer.vue`：
     - 基于 `canvas` 渲染页面，虚拟滚动按需渲染可见页。
     - 文本层（`TextLayer`）支持选中文字创建高亮/下划线。
     - SVG 覆盖层展示已有批注（HIGHLIGHT/UNDERLINE/NOTE/FREEHAND）。
     - 工具栏切换工具、选择颜色、保存、删除。
     - 便签点击添加并弹窗输入内容。
     - 手写圈注通过 pointer 事件采集点并转换为归一化坐标。
   - 坐标存储：归一化 PDF 页面坐标 + 页面宽度/高度/rotation/scale，便于不同视口还原。

3. **集成与回退**
   - `LibraryView` 的 PDF 预览 overlay 默认使用 `PdfViewer`。
   - 新增设置 `pdf_js_viewer_enabled`；关闭时回退到原生 iframe。
   - `SettingsView` 增加 "PDF 阅读器" 开关。

4. **测试**
   - 新增 `AnnotationServiceTest`：验证列表、创建、更新、删除、异常更新。

**验证结果**：

- `./mvnw test` 全部通过（160 项）。
- `npm run build` 前端构建成功。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/entity/PaperAnnotation.java`
  - `backend/src/main/java/com/research/assistant/mapper/PaperAnnotationMapper.java`
  - `backend/src/main/java/com/research/assistant/service/annotation/AnnotationService.java`
  - `backend/src/main/java/com/research/assistant/controller/AnnotationController.java`
  - `backend/src/main/java/com/research/assistant/dto/AnnotationDto.java`
  - `backend/src/main/java/com/research/assistant/dto/AnnotationRequest.java`
  - `backend/src/test/java/com/research/assistant/service/annotation/AnnotationServiceTest.java`
  - `backend/src/main/resources/schema-upgrade-6.6.1.sql`
  - `frontend/src/components/pdf/PdfViewer.vue`
  - `frontend/src/api/annotation.js`
- 修改：
  - `backend/src/main/resources/schema.sql`
  - `frontend/src/views/LibraryView.vue`
  - `frontend/src/views/SettingsView.vue`
  - `frontend/package.json`
  - `docs/improvements.md`

**已知限制**：

- 当前未实现批注列表侧边栏、批注按页分组过滤、拖拽修改便签位置。
- 文本层依赖 PDF 本身包含文本；扫描版 PDF 无法选中文本生成高亮。
- 手写圈注使用简单 polyline，不支持压感、橡皮擦。

---

## 阶段 4.34：AI 自动批注（improvements.md 6.2）— 2026-07-10 ✅

**目标**：基于论文 AI 分析结果自动生成批注（核心方法、创新点、实验结论、潜在问题），持久化并可在 PDF.js 阅读器中查看。

**完成内容**：

1. **扩展 `paper_annotation` 表与实体**
   - 新增 `ai_generated` 字段（默认 0）。
   - `PaperAnnotation`、`AnnotationDto` 增加 `aiGenerated` 属性。
   - `AnnotationService.create(..., boolean aiGenerated)` 支持标记 AI 批注。

2. **新增 `AiAnnotationService`**
   - 读取 `PaperAnalysis` 的分析结果。
   - 构造 prompt，要求 LLM 返回 JSON 数组：`{category, anchorText, note}`。
   - 分类映射：METHOD→HIGHLIGHT（蓝色），INNOVATION→HIGHLIGHT（黄色），EXPERIMENT→UNDERLINE（绿色），ISSUE→NOTE（红色）。
   - 通过 PDFBox 逐页文本匹配 `anchorText` 定位页码；匹配失败默认第 1 页。
   - 调用 `AnnotationService.create(..., true)` 持久化。

3. **后端接口**
   - `POST /api/papers/{paperId}/annotations/ai-generate`

4. **前端集成**
   - `PdfViewer.vue` 工具栏新增 "AI 批注" 按钮。
   - 调用 `generateAiAnnotations(paperId)`，将返回的批注追加到当前列表。
   - 用户可继续编辑/删除/保存。

5. **测试**
   - 新增 `AiAnnotationServiceTest`：验证无分析时抛异常、正常解析 LLM 输出、非法 JSON 返回空。

**验证结果**：

- `./mvnw test` 全部通过（164 项）。
- `npm run build` 前端构建成功。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/service/annotation/AiAnnotationService.java`
  - `backend/src/test/java/com/research/assistant/service/annotation/AiAnnotationServiceTest.java`
  - `backend/src/main/resources/schema-upgrade-6.6.2.sql`
- 修改：
  - `backend/src/main/java/com/research/assistant/entity/PaperAnnotation.java`
  - `backend/src/main/java/com/research/assistant/dto/AnnotationDto.java`
  - `backend/src/main/java/com/research/assistant/service/annotation/AnnotationService.java`
  - `backend/src/main/java/com/research/assistant/controller/AnnotationController.java`
  - `backend/src/main/resources/schema.sql`
  - `frontend/src/api/annotation.js`
  - `frontend/src/components/pdf/PdfViewer.vue`
  - `docs/improvements.md`

**已知限制**：

- AI 批注的页码依赖 anchorText 在 PDF 文本中精确匹配；若 LLM 生成的 anchorText 与原文不完全一致，会回退到第 1 页。
- 未实现 AI 批注的高亮框精确坐标，当前仅记录页码与空 quads。

---

## 阶段 4.35：笔记与论文段落的双向链接（improvements.md 6.4）— 2026-07-10 ✅

**目标**：允许用户在 PDF 上选中区域创建笔记，并在笔记侧栏反向定位回论文区域，实现笔记与论文的双向链接。

**完成内容**：

1. **后端数据模型**
   - 新增 `note` 表（id, title, content, created_at, updated_at）。
   - 新增 `paper_note_link` 表（id, paper_id, note_id, page, coordinates_json, anchor_text）。
   - 实体：`Note`、`PaperNoteLink`；Mapper：`NoteMapper`、`PaperNoteLinkMapper`。

2. **后端服务与接口**
   - `NoteService`：
     - 创建笔记并建立链接；
     - 查询某论文的所有笔记（含链接坐标、原文片段）；
     - 查询某笔记关联的所有论文 ID；
     - 删除笔记级联删除链接；解除链接时若笔记无关联论文则自动删除笔记。
   - `NoteController`：
     - `GET /api/papers/{paperId}/notes`
     - `POST /api/papers/{paperId}/notes`
     - `PUT /api/notes/{noteId}`
     - `DELETE /api/notes/{noteId}`
     - `DELETE /api/papers/{paperId}/notes/{noteId}`
     - `GET /api/notes/{noteId}/papers`

3. **前端组件**
   - `NoteEditor.vue`：编辑笔记标题、内容，展示选中的原文片段。
   - `NoteLinkPanel.vue`：显示当前论文的笔记列表，支持新建、编辑、删除、点击跳转对应页。
   - `PdfViewer.vue` 集成：
     - 工具栏 "笔记" 按钮开关笔记侧栏。
     - 右键菜单 "新建笔记"：基于当前文本选区生成 anchorText、页码、归一化坐标。
     - 点击笔记列表项滚动到对应页面。

4. **测试**
   - 新增 `NoteServiceTest`：验证创建笔记+链接、删除级联。

**验证结果**：

- `./mvnw test` 全部通过（168 项）。
- `npm run build` 前端构建成功。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/entity/Note.java`
  - `backend/src/main/java/com/research/assistant/entity/PaperNoteLink.java`
  - `backend/src/main/java/com/research/assistant/mapper/NoteMapper.java`
  - `backend/src/main/java/com/research/assistant/mapper/PaperNoteLinkMapper.java`
  - `backend/src/main/java/com/research/assistant/service/note/NoteService.java`
  - `backend/src/main/java/com/research/assistant/controller/NoteController.java`
  - `backend/src/main/java/com/research/assistant/dto/NoteDto.java`
  - `backend/src/main/java/com/research/assistant/dto/NoteRequest.java`
  - `backend/src/test/java/com/research/assistant/service/note/NoteServiceTest.java`
  - `backend/src/main/resources/schema-upgrade-6.6.3.sql`
  - `frontend/src/api/notes.js`
  - `frontend/src/components/notes/NoteEditor.vue`
  - `frontend/src/components/notes/NoteLinkPanel.vue`
- 修改：
  - `backend/src/main/resources/schema.sql`
  - `frontend/src/components/pdf/PdfViewer.vue`
  - `docs/improvements.md`

**已知限制**：

- 笔记仅在 PDF.js 阅读器内查看；尚未在论文库右栏或独立笔记列表页展示。
- 反向定位目前只到页，未在页面上高亮具体 anchor 区域。

---

## 阶段 4.36：BibTeX 导出 + Zotero / Obsidian 同步（improvements.md 6.5）— 2026-07-10 ✅

**目标**：支持单篇/批量 BibTeX 导出，以及 Obsidian Markdown 同步、Zotero Web API 同步。

**完成内容**：

1. **BibTeX 导出**
   - 新增 `BibTeXExporter`：根据 source 字段判断 entry 类型（article / inproceedings / misc）。
   - 解析 `authors` JSON 数组，生成 `author = {First and Second}`。
   - 优先输出 DOI，否则输出 arXiv eprint 与 archivePrefix。
   - 生成稳定的 citation key：`LastName + Year + TitleFirstWord`。

2. **Obsidian 同步**
   - 新增 `ObsidianSyncService`：读取 `obsidian_vault_path` 设置，写入 `{vault}/research-assistant/{title}.md`。
   - Markdown 内容包含标题、作者、年份、来源、DOI、摘要以及 BibTeX 代码块。

3. **Zotero 同步**
   - 新增 `ZoteroSyncService`：通过 Web API v3 `POST /users/{userId}/items` 推送条目。
   - 需要 `zotero_user_id`、`zotero_api_key`；可选 `zotero_collection_key`。
   - 作者按 `firstName/lastName` 拆分。

4. **后端接口**
   - `GET /api/papers/{id}/export/bibtex`
   - `POST /api/papers/export/bibtex`
   - `POST /api/export/obsidian`
   - `POST /api/export/zotero`

5. **前端**
   - `LibraryView` 工具栏新增 "导出" 下拉菜单：导出当前/选中 BibTeX、同步 Obsidian、同步 Zotero。
   - `SettingsView` 新增 "导出与同步" 卡片，配置 Obsidian vault 路径与 Zotero 凭据。

6. **测试**
   - 新增 `BibTeXExporterTest`：验证 article、arXiv、inproceedings 导出。
   - 新增 `ObsidianSyncServiceTest`：验证临时目录下 Markdown 文件写入。
   - 新增 `ZoteroSyncServiceTest`：mock HttpClient 验证请求发送。

**验证结果**：

- `./mvnw test` 全部通过（176 项）。
- `npm run build` 前端构建成功。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/service/export/BibTeXExporter.java`
  - `backend/src/main/java/com/research/assistant/service/export/ObsidianSyncService.java`
  - `backend/src/main/java/com/research/assistant/service/export/ZoteroSyncService.java`
  - `backend/src/main/java/com/research/assistant/controller/ExportController.java`
  - `backend/src/main/java/com/research/assistant/dto/BibTeXExportRequest.java`
  - `backend/src/test/java/com/research/assistant/service/export/BibTeXExporterTest.java`
  - `backend/src/test/java/com/research/assistant/service/export/ObsidianSyncServiceTest.java`
  - `backend/src/test/java/com/research/assistant/service/export/ZoteroSyncServiceTest.java`
  - `frontend/src/api/export.js`
- 修改：
  - `frontend/src/views/LibraryView.vue`
  - `frontend/src/views/SettingsView.vue`
  - `docs/improvements.md`

**已知限制**：

- Zotero 同步未做本地失败回退为 BibTeX 文件；失败时仅返回错误提示，需用户手动导出 BibTeX 再导入 Zotero。
- Obsidian 同步会覆盖同名文件（按论文标题命名）。

---

## 阶段 5：写作辅助模块 — 2026-07-10 ✅

**目标**：实现 `improvements.md` 第 7 点——新增写作辅助模块，让系统对论文写作产生直接帮助。

**完成内容**：

1. **数据模型**
   - 新增 `writing_project` 与 `writing_project_paper` 表（`schema.sql` + `schema-upgrade-7.0.sql`）。
   - 实体：`WritingProject`、`WritingProjectPaper`。
   - DTO：`WritingProjectDto`、`WritingProjectRequest`、`OutlineDto`、`OutlineRequest`、`RelatedWorkDto`、`RelatedWorkRequest`、`CitationCheckDto`、`CitationCheckRequest`。

2. **后端服务与接口**
   - `WritingProjectService`：写作项目 CRUD、论文关联管理、聚合阅读笔记。
   - `WritingAssistantService`：
     - `generateOutline`：根据选题生成结构化大纲。
     - `generateRelatedWork`：基于已选论文分析数据生成 Related Work 段落。
     - `checkCitations`：对用户段落做 RAG 召回 + LLM 判断，推荐引用并检测重复/冲突。
   - `WritingController`：所有接口返回统一 `Result<T>`。
     - `GET/POST/PUT/DELETE /api/writing/projects`
     - `POST/DELETE /api/writing/projects/{id}/papers`
     - `GET /api/writing/projects/{id}/notes`
     - `POST /api/writing/outline`
     - `POST /api/writing/related-work`
     - `POST /api/writing/citation-check`

3. **前端页面**
   - 新增 `WritingView.vue`：左栏项目/论文/笔记/生成结果，右栏 Markdown 编辑器 + 预览。
   - 新增 `frontend/src/api/writing.js`。
   - `App.vue` 导航新增「写作助手」入口。
   - 支持一键插入大纲、Related Work 和阅读笔记到编辑器。

4. **测试**
   - 新增 `WritingAssistantServiceTest`（Mockito）：大纲解析、Related Work 生成、引用检查。
   - 新增 `WritingProjectServiceTest`（Spring Boot 集成）：项目 CRUD、论文关联、笔记聚合、级联删除、重复校验。

**验证结果**：

- `./mvnw test` 全部通过（191 项）。
- `npm run build` 前端构建成功。
- 数据库升级脚本 `schema-upgrade-7.0.sql` 已在本地 MySQL 执行。
- Playwright 手动验证：
  - 导航 `/writing` 页面正常加载，导航栏新增「写作助手」入口。
  - 调整 `App.vue` 导航栏样式（移除 `max-width: 50%`、缩小字号与 padding），使 7 个菜单项完整显示。
  - 修复 `frontend/src/api/writing.js` 响应解包，与 `api/index.js` 拦截器保持一致，避免新建项目时 `created.data.id` 报错。
  - 创建写作项目后可正常选中并展示左侧论文/笔记/生成结果 tabs。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/entity/WritingProject.java`
  - `backend/src/main/java/com/research/assistant/entity/WritingProjectPaper.java`
  - `backend/src/main/java/com/research/assistant/mapper/WritingProjectMapper.java`
  - `backend/src/main/java/com/research/assistant/mapper/WritingProjectPaperMapper.java`
  - `backend/src/main/java/com/research/assistant/service/writing/WritingProjectService.java`
  - `backend/src/main/java/com/research/assistant/service/writing/WritingAssistantService.java`
  - `backend/src/main/java/com/research/assistant/controller/WritingController.java`
  - `backend/src/main/java/com/research/assistant/dto/WritingProjectDto.java`
  - `backend/src/main/java/com/research/assistant/dto/WritingProjectRequest.java`
  - `backend/src/main/java/com/research/assistant/dto/OutlineDto.java`
  - `backend/src/main/java/com/research/assistant/dto/OutlineRequest.java`
  - `backend/src/main/java/com/research/assistant/dto/RelatedWorkDto.java`
  - `backend/src/main/java/com/research/assistant/dto/RelatedWorkRequest.java`
  - `backend/src/main/java/com/research/assistant/dto/CitationCheckDto.java`
  - `backend/src/main/java/com/research/assistant/dto/CitationCheckRequest.java`
  - `backend/src/test/java/com/research/assistant/service/writing/WritingAssistantServiceTest.java`
  - `backend/src/test/java/com/research/assistant/service/writing/WritingProjectServiceTest.java`
  - `backend/src/main/resources/schema-upgrade-7.0.sql`
  - `frontend/src/views/WritingView.vue`
  - `frontend/src/api/writing.js`
- 修改：
  - `backend/src/main/resources/schema.sql`
  - `frontend/src/router/index.js`
  - `frontend/src/App.vue`
  - `frontend/src/api/writing.js`
  - `docs/improvements.md`

**已知限制**：

- 编辑器为 Markdown 纯文本输入，尚未接入富文本编辑器。
- 大纲/Related Work/引用检查依赖 LLM JSON 输出稳定性；失败时会有错误提示。
- Related Work 生成依赖论文已完成精读分析；无分析数据的论文会被跳过。
- 引用检查的 RAG 召回依赖本地向量库已建立索引；未启用 RAG 时降级为基于已选论文分析做判断。


**目标**：实现 `improvements.md` 6.6 最后一项——阅读计划管理、本周清单与逾期提醒。

**完成内容**：

1. **后端数据模型**
   - 新增 `reading_plan` 与 `reading_plan_item` 表（`schema.sql` + `schema-upgrade-6.6.6.sql`）。
   - 实体：`ReadingPlan`、`ReadingPlanItem`。
   - DTO：`ReadingPlanDto`、`ReadingPlanItemDto`。
   - 请求：`ReadingPlanRequest`、`ReadingPlanItemRequest`。

2. **后端服务与接口**
   - `ReadingPlanService`：计划 CRUD、条目 CRUD、本周清单、提醒过滤。
   - `ReadingPlanController`：所有接口返回统一 `Result<T>`，与前端的 `api/index.js` 响应拦截器兼容。
     - `GET /api/reading-plans`
     - `POST /api/reading-plans`
     - `GET/PUT/DELETE /api/reading-plans/{id}`
     - `POST/PUT/DELETE /api/reading-plans/{id}/items`
     - `GET /api/reading-plans/weekly`
     - `GET /api/reading-plans/reminders`

3. **前端页面**
   - 新增 `ReadingPlanView.vue`：左栏计划列表 + 本周要读，右栏条目表格与状态管理。
   - 新增 `frontend/src/api/readingPlan.js`。
   - `App.vue` 导航新增「阅读计划」入口；启动时拉取 `/api/reading-plans/reminders` 并弹窗提示。
   - `LibraryView` 导出下拉菜单新增「加入阅读计划」动作。

4. **启动时修复**
   - 为多个 Fetcher/Service 的公共构造函数添加 `@Autowired`，解决 Spring 在多构造函数时的默认实例化失败（`No default constructor found`）。
   - 在 `AsyncTaskService`、`Planner`、`PlanExecutor` 的相关依赖上加 `@Lazy`，打破启动期循环依赖。
   - 将 `PdfBoxFigureExtractor` 标记为 `@Primary`，避免 `FigureExtractor` 双 Bean 冲突。

5. **测试**
   - 新增 `ReadingPlanServiceTest`：创建计划、添加条目、本周查询、逾期过滤、状态更新、重复添加校验、级联删除。

**验证结果**：

- `./mvnw test` 全部通过（181 项）。
- `npm run build` 前端构建成功。
- 数据库升级脚本已在本地 MySQL 执行。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/entity/ReadingPlan.java`
  - `backend/src/main/java/com/research/assistant/entity/ReadingPlanItem.java`
  - `backend/src/main/java/com/research/assistant/mapper/ReadingPlanMapper.java`
  - `backend/src/main/java/com/research/assistant/mapper/ReadingPlanItemMapper.java`
  - `backend/src/main/java/com/research/assistant/service/reading/ReadingPlanService.java`
  - `backend/src/main/java/com/research/assistant/controller/ReadingPlanController.java`
  - `backend/src/main/java/com/research/assistant/dto/ReadingPlanDto.java`
  - `backend/src/main/java/com/research/assistant/dto/ReadingPlanItemDto.java`
  - `backend/src/main/java/com/research/assistant/dto/ReadingPlanRequest.java`
  - `backend/src/main/java/com/research/assistant/dto/ReadingPlanItemRequest.java`
  - `backend/src/test/java/com/research/assistant/service/reading/ReadingPlanServiceTest.java`
  - `backend/src/main/resources/schema-upgrade-6.6.6.sql`
  - `frontend/src/views/ReadingPlanView.vue`
  - `frontend/src/api/readingPlan.js`
  - `frontend/src/api/paper.js`
- 修改：
  - `backend/src/main/resources/schema.sql`
  - `frontend/src/router/index.js`
  - `frontend/src/App.vue`
  - `frontend/src/views/LibraryView.vue`
  - `docs/improvements.md`

**已知限制**：

- 提醒为 on-demand 查询，仅页面刷新/启动时触发；后续可补 `@Scheduled` 每日任务或系统通知。
- 阅读计划条目仅支持单篇论文，暂不支持批量导入。
- 前端联调中发现并修复：
  - `App.vue` 遗漏 `loadReminders` 函数定义，已补齐。
  - `ReadingPlanView.vue` 中 `loadPapers` 直接使用 `listPapers()` 返回的数组（`api/index.js` 已解包），不再读取 `res.data`。
  - `LibraryView` 的「加入阅读计划」仅在已选中当前论文（详情面板展开）时可用。

## 阶段 6：UI/UX 基础 + 安全数据保护 — 2026-07-10 ✅

**目标**：对应 `docs/improvements.md` 第 8 点（UI/UX）和第 10 点（安全与数据保护）中可快速落地的前 3 个子项：清理 mvnw、暗色模式、API Key 加密/环境变量、首页数据看板。

**完成内容**：

1. **mvnw 脚本清理（10.2）**：
   - 移除 `backend/mvnw` 与 `backend/mvnw.cmd` 中硬编码的 `JAVA_HOME` / `MAVEN_HOME`。
   - 恢复标准 Maven Wrapper 行为，未设置 `JAVA_HOME` 时给出友好提示。

2. **暗色模式（8.2）**：
   - 新增 `frontend/src/stores/themeStore.js`，持久化 `dark` 状态到 `localStorage`。
   - `main.js` 引入 Element Plus dark css-vars；`App.vue` 切换 `html.dark` 并增加主题按钮。
   - 定义 CSS 变量 `--ra-bg`、`--ra-header-bg`、`--ra-text` 等，覆盖 App.vue 与 DashboardView。

3. **API Key 加密 / 环境变量（10.1）**：
   - 后端新增 `Encryptor`（AES-256-GCM）与 `EncryptionConfig`。
   - `SettingsServiceImpl` 对以 `api_key` 结尾的 key 自动加解密，密文前缀 `enc:`，兼容历史明文。
   - 支持环境变量覆盖：`RA_API_KEY`、`RA_BASE_URL`、`RA_MODEL` 及对应 `EMBEDDING_*`。
   - `application.yml` 中的 datasource / pdf-dir / master-key 均改为 `${...}` 可覆盖。

4. **首页数据看板（8.4）**：
   - 路由 `/` 改为 `DashboardView.vue`；原 LibraryView 移到 `/library`。
   - 后端新增 `DashboardController`（`GET /api/dashboard`）+ `DashboardServiceImpl`。
   - 聚合指标：论文总数/阅读状态/置顶/本月新增、文件夹堆积 Top 5、阅读计划逾期/即将到期/本周待读、任务状态计数+最近任务、最近笔记/批注。
   - Mapper 新增轻量统计方法：`PaperMapper.countByReadingStatus/countPinned/countCreatedSince`、`AsyncTaskRecordMapper.countByStatus`、`ReadingPlanItemMapper.countOverdue/countDueBetween/countThisWeek`。

**验证结果**：

- 后端 `./mvnw test` 全部通过（新增 `DashboardServiceImplTest`）。
- 前端 `npm run build` 通过。
- Playwright 实测：
  - 首页 `/` 正确加载看板，论文统计、文件夹堆积、任务中心卡片均显示正确数值。
  - 点击导航「文库管理」进入 `/library`，表格与文件夹树正常。
  - 点击标题回到 `/`。
  - Console 无新增 error。

**文件清单**：

- 新增：
  - `backend/src/main/java/com/research/assistant/dto/DashboardDto.java`
  - `backend/src/main/java/com/research/assistant/service/DashboardService.java`
  - `backend/src/main/java/com/research/assistant/service/impl/DashboardServiceImpl.java`
  - `backend/src/main/java/com/research/assistant/controller/DashboardController.java`
  - `backend/src/test/java/com/research/assistant/service/impl/DashboardServiceImplTest.java`
  - `frontend/src/views/DashboardView.vue`
  - `frontend/src/api/dashboard.js`
  - `frontend/src/stores/themeStore.js`
  - `backend/src/main/java/com/research/assistant/service/Encryptor.java`
  - `backend/src/main/java/com/research/assistant/config/EncryptionConfig.java`
  - `backend/src/test/java/com/research/assistant/service/EncryptorTest.java`
  - `backend/src/test/java/com/research/assistant/service/impl/SettingsServiceImplEncryptionTest.java`
- 修改：
  - `backend/mvnw`、`backend/mvnw.cmd`
  - `frontend/src/main.js`、`frontend/src/App.vue`、`frontend/src/router/index.js`
  - `backend/src/main/java/com/research/assistant/service/impl/SettingsServiceImpl.java`
  - `backend/src/main/resources/application.yml`
  - `backend/src/main/java/com/research/assistant/mapper/PaperMapper.java`
  - `backend/src/main/java/com/research/assistant/mapper/AsyncTaskRecordMapper.java`
  - `backend/src/main/java/com/research/assistant/mapper/ReadingPlanItemMapper.java`

## 阶段 6.1：LibraryView 表格升级为 vxe-table — 2026-07-10 ✅

**目标**：对应 `docs/improvements.md` 第 8.1 项，用专业表格组件替换手写 `<table>`，保留现有交互。

**完成内容**：

1. **安装 vxe-table 生态**：
   - `frontend/package.json` 新增 `vxe-table` + `vxe-pc-ui`。
   - `main.js` 全局注册 `VxeUI` 与 `VXETable` 并引入样式。

2. **封装 `PaperTable.vue`**：
   - 基于 `vxe-grid` 实现：复选框、可排序列、可拖拽调整列宽、分页。
   - 列：标题、类目、标签、状态、期刊/会议、出版年份、导入年份、操作。
   - 行样式保留 `row-active`（当前查看）与 `row-pinned`（置顶）。
   - 事件：页码/排序/选择变化、标签点击、状态下拉、分析/信息/更多操作。

3. **改造 `LibraryView.vue`**：
   - 移除手写 `<table>`、列宽对象 `colWidths`、列拖动逻辑 `startColResize/onResize/stopResize`。
   - 移除 `isAllSelected/toggleSelectAll/togglePaperSelection`，改由 `vxe-grid` 选择事件。
   - 新增 `tableLoading`、`onTablePageChange`、`onTableSortChange`。

**验证结果**：

- `npm run build` 通过。
- Playwright 实测：
  - `/library` 表格正常渲染 4 条论文。
  - 点击标题单元格打开右侧详情面板。
  - Console 无 error。

**注意事项**：

- vxe-table 4.19 将 loading/pager 等 UI 拆分到 `vxe-pc-ui`，已一并安装。
- 打包体积增加约 300KB gzip（index chunk 1.6MB → 2.2MB），后续可通过动态导入 `PaperTable.vue` 拆分。

**文件清单**：

- 新增：`frontend/src/components/PaperTable.vue`
- 修改：`frontend/package.json`、`frontend/src/main.js`、`frontend/src/views/LibraryView.vue`

---

## 阶段 6.2：全局快捷键与命令面板 — 2026-07-10 ✅

**目标**：对应 `docs/improvements.md` 第 8.3 项，通过键盘快速切换视图和触发命令。

**完成内容**：

1. **全局快捷键 composable**：
   - 新增 `frontend/src/composables/useKeyboardShortcuts.js`。
   - 支持 `key` / `ctrl` / `shift` / `alt` 组合，可设置是否在输入框内禁用。

2. **命令面板组件**：
   - 新增 `frontend/src/components/CommandPalette.vue`。
   - `Ctrl/Cmd+K` 呼出，支持搜索、上下选择、Enter 执行、Esc 关闭。
   - 命令来源：路由列表（看板/文库/检索/分析/Gap/任务/阅读计划/写作助手）、全局搜索、打开设置、切换主题。

3. **App.vue 接入**：
   - 注册 `Ctrl/Cmd+K`、`Ctrl/Cmd+Shift+F`、`Ctrl/Cmd+1~8`。
   - 命令面板内展示每个命令的快捷键提示。
   - 顶部导航菜单增加 `title` 提示（悬停显示快捷键）。
   - 右上角新增「快捷键帮助」按钮，点击弹出完整快捷键列表表格。

**验证结果**：

- `npm run build` 通过。
- Playwright 实测：
  - 在首页按 `Ctrl+K` 呼出命令面板。
  - 点击「打开文库管理」跳转到 `/library`。
  - 按 `Ctrl+3` 跳转到 `/search`。
  - 点击右上角「快捷键帮助」按钮，弹出包含所有快捷键的对话框。
  - Console 无 error。

**文件清单**：

- 新增：`frontend/src/composables/useKeyboardShortcuts.js`、`frontend/src/components/CommandPalette.vue`
- 修改：`frontend/src/App.vue`

---

## 阶段 6.3：UI/UX 细节修复 — 2026-07-10 ✅

**目标**：处理 improvements.md 中剩余的 UI/UX 小问题，提升暗色模式覆盖、表格对齐、弹窗排版与设置说明。

**完成内容**：

1. **暗色模式覆盖与调色板优化（#112）**：
   - `App.vue` 扩展 CSS 变量体系（`--ra-bg`、`--ra-panel-bg`、`--ra-header-bg`、`--ra-text`、`--ra-text-secondary`、`--ra-text-tertiary`、`--ra-border`、`--ra-border-light`、`--ra-link`、`--ra-hover-bg`、`--ra-active-bg`、`--ra-active-text`）。
   - 暗色模式改为更柔和的蓝灰调色板（`#1a1b1e` / `#232428`），替代原本纯黑方案。
   - 全局覆盖 Element Plus 标签在暗色下的语义色，确保 success/danger/info/primary 标签保持辨识度。
   - 全局覆盖 vxe-table 表头、单元格、hover、当前行、分页按钮的暗色样式。
   - 将 `LibraryView.vue`、`SearchView.vue`、`WritingView.vue`、`ReadingPlanView.vue`、`TaskCenterView.vue`、`SettingsView.vue`、`AnalysisView.vue`、`GapView.vue`、`StructuredAnalysis.vue`、`NoteEditor.vue`、`NoteLinkPanel.vue`、`PdfViewer.vue` 中的硬编码背景/边框/文字色替换为 CSS 变量。

2. **批量移动对话框标签换行（#113）**：
   - `LibraryView.vue` 中批量移动表单项 `label-width` 由 `80px` 调整为 `96px`，避免「目标文件夹」五字换行。

3. **表格表头居中、内容左对齐（#114）**：
   - `PaperTable.vue` 所有列新增 `headerAlign: 'center'` 与 `align: 'left'`（复选框除外）。
   - 操作列按钮容器增加 `justify-content: center`，在左对齐单元格内保持按钮居中。

4. **研究主题设置说明（#115）**：
   - `App.vue` 设置弹窗与 `SettingsView.vue` 中「研究主题」输入框下方新增说明：用于分析论文与本研究方向的匹配度，影响入库时的相关性评分与推荐理由。

**验证结果**：

- `npm run build` 通过。
- Playwright 实测：
  - 首页、文库、检索、阅读计划、设置页在暗色模式下背景/文字/表格/标签均正常。
  - 批量移动对话框「目标文件夹」标签单行显示。
  - 文库表头居中、内容左对齐。
  - 设置页「研究主题」下方出现用途说明。

**文件清单**：

- 修改：`frontend/src/App.vue`、`frontend/src/components/PaperTable.vue`、`frontend/src/views/LibraryView.vue`、`frontend/src/views/SearchView.vue`、`frontend/src/views/WritingView.vue`、`frontend/src/views/ReadingPlanView.vue`、`frontend/src/views/TaskCenterView.vue`、`frontend/src/views/SettingsView.vue`、`frontend/src/views/AnalysisView.vue`、`frontend/src/views/GapView.vue`、`frontend/src/components/StructuredAnalysis.vue`、`frontend/src/components/notes/NoteEditor.vue`、`frontend/src/components/notes/NoteLinkPanel.vue`、`frontend/src/components/pdf/PdfViewer.vue`

---

## 阶段 6.4：阅读计划增强、任务中心说明与删除、文库表格效果修复 — 2026-07-10 ✅

**目标**：落实用户提出的三类 UI/UX 与功能反馈：阅读计划支持备注与标签导入、用状态灯条直观展示计划进度；任务中心说明其作用并允许删除终态任务；修复 vxe-table 迁移导致的文库斑马纹与置顶灯条丢失。

**完成内容**：

1. **阅读计划增强**：
   - 数据库：`reading_plan_item` 新增 `notes TEXT` 字段；`schema.sql` 已更新。
   - 后端：`ReadingPlanItem` 实体、`ReadingPlanItemRequest` / `ReadingPlanItemDto` 增加 `notes` 与 `paperTags`；`ReadingPlanService` 在增改条目时保存备注，并在查询时批量加载论文原始标签。
   - 后端：`ReadingPlanDto` 增加 `totalItems`、`doneItems`、`inProgressItems`，列表接口即返回进度统计。
   - 前端：`ReadingPlanView.vue`
     - 添加论文弹窗新增「备注」多行输入框。
     - 条目表格新增「标签」列（展示该论文在文库中的标签）和「备注」列。
     - 左侧计划卡片左侧增加 4px 状态灯条：全部完成绿色、有进行中蓝色、全部待读灰色、空计划浅灰。
     - 保留「本周要读」区域作为近期待读快捷入口。

2. **任务中心说明与删除**：
   - 后端：`AsyncTaskManager` 新增 `delete(taskId)`，仅允许删除 `COMPLETED/FAILED/CANCELLED` 终态任务；同步清理 `workflow_step` 步骤记录（对未建表场景做降级处理）。
   - 后端：`AsyncTaskService` 暴露 `deleteTask`；`AgentController` 新增 `DELETE /api/agent/task/{taskId}`。
   - 前端：`TaskCenterView.vue`
     - 页面顶部增加可关闭的「任务中心说明」提示：解释任务由 AI Agent / 工作流自动创建，不需要手动新建，可查看进度、取消、重试、确认或删除。
     - 终态任务操作列新增「删除」按钮，点击二次确认后调用删除接口。
     - 说明关闭状态持久化到 `localStorage`。

3. **文库表格效果修复**：
   - `PaperTable.vue`：启用 `vxe-grid` 斑马纹 `stripe`；将置顶行左侧深蓝色灯条样式放到非 scoped style，避免 vxe-table 渲染的 DOM 丢失 scoped 属性。
   - `LibraryView.vue`：移除整行点击展开详情；仅操作列「信息」按钮打开右侧详情面板；清理迁移后失效的 `.paper-table` CSS。

4. **工程清理**：
   - `.gitignore` 增加 `*.pid`，删除误跟踪的 `backend/backend.pid` 与 `backend/frontend.pid`。

**数据库迁移**：

```sql
ALTER TABLE reading_plan_item ADD COLUMN notes TEXT COMMENT '阅读备注';
```

**验证结果**：

- 后端 `./mvnw compile` 通过；Spring Boot 重启后新接口可用。
- 前端 `npm run build` 通过。
- Playwright 实测：
  - 阅读计划页：计划卡片左侧出现灰色状态灯条；打开计划后条目表格包含「标签」「备注」列；添加论文弹窗包含「备注」输入框。
  - 任务中心页：顶部说明可见；终态任务显示「删除」按钮；点击删除并确认后该行消失，列表进入空状态。
  - 文库表格：斑马纹正常、置顶行左侧深蓝竖条可见、点击行不展开详情、点击「信息」按钮展开详情。

**文件清单**：

- 新增：无
- 修改：
  - `backend/src/main/resources/schema.sql`
  - `backend/src/main/java/com/research/assistant/entity/ReadingPlanItem.java`
  - `backend/src/main/java/com/research/assistant/dto/ReadingPlanItemRequest.java`
  - `backend/src/main/java/com/research/assistant/dto/ReadingPlanItemDto.java`
  - `backend/src/main/java/com/research/assistant/dto/ReadingPlanDto.java`
  - `backend/src/main/java/com/research/assistant/service/reading/ReadingPlanService.java`
  - `backend/src/main/java/com/research/assistant/service/async/AsyncTaskManager.java`
  - `backend/src/main/java/com/research/assistant/service/AsyncTaskService.java`
  - `backend/src/main/java/com/research/assistant/mapper/WorkflowStepMapper.java`
  - `backend/src/main/java/com/research/assistant/controller/AgentController.java`
  - `frontend/src/views/ReadingPlanView.vue`
  - `frontend/src/views/TaskCenterView.vue`
  - `frontend/src/components/PaperTable.vue`
  - `frontend/src/views/LibraryView.vue`
  - `.gitignore`

