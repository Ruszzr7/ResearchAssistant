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
