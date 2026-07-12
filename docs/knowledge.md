# 知识总结（精简版）

> 本文保留可复用的设计决策和踩坑结论；逐次开发过程和旧代码片段不再重复保存。

## 1. 前后端基础

- 前端通过 Vite 将 `/api` 代理到 Spring Boot；生产环境需要配置 SPA 路由回退。
- 后端统一返回 `{ code, message, data }`，由 Axios 响应拦截器转换业务错误。
- Controller 只负责协议，业务放在 Service，数据库访问放在 Mapper；跨模块流程由 Workflow / Skill 编排。
- Vue 页面状态主要由 `ref/reactive` 和 composable 管理；长任务状态放入全局 reactive store，避免切页丢失。

## 2. MySQL 与 MyBatis Plus

- 嵌套文件夹使用 `parent_id` 自引用，多对多标签使用 `paper_tag` 桥接表。
- MySQL `abstract` 是保留字，SQL 中使用反引号，Java 字段通过别名映射为 `abstractText`。
- `updateById` 默认跳过 null；需要清空字段时使用 UpdateWrapper 显式写入 NULL。
- 新环境使用 `schema.sql`，旧环境使用版本升级脚本；长期应迁移到 Flyway / Liquibase。
- 测试不能依赖开发库。SpringBootTest 使用 `test` profile 和 H2 内存 schema。

## 3. 文件与 PDF

- 上传文件通过 `MultipartFile` 接收，大小由 `spring.servlet.multipart` 控制。
- PDF 路径必须基于 `user.dir` 或配置目录解析，不能依赖 Tomcat 工作目录。
- PDF 解析通过 `PdfParser` 接口抽象；PDFBox 是可靠 fallback，Marker/MinerU 等外部解析器必须有超时和失败回退。
- 扫描版 PDF 可能没有文本，需要在界面提示 OCR/外部解析；公式和图表应作为独立扩展点处理。
- 下载中文文件名使用 RFC 5987 的 `filename*=UTF-8''...` 编码。

## 4. LangChain4j 与模型配置

- API Key、Base URL、模型名保存在 settings 表，也支持 `RA_API_KEY`、`RA_BASE_URL`、`RA_MODEL` 环境变量覆盖。
- `ResearchAiConfig` 使用编程式 `AiServices.builder`，因为配置来自数据库；Bean 采用 `@Lazy`，保证首次启动可以先进入设置页。
- 结构化输出优先映射 POJO，失败时保留旧 JSON fallback；所有 LLM 输出都应有默认值和字段归一化。
- ChatMemory 使用数据库存储，工具型 Agent 不应共享普通对话记忆，避免上下文污染。
- API Key 只有配置 `RA_MASTER_KEY` 时才会 AES-GCM 加密；没有主密钥必须明确提示用户。

## 5. Skill、Planner 与 Workflow

- `Skill<I,O>` 统一名称、描述、输入类型和执行入口；Spring 自动收集后放入 `SkillRegistry`。
- Planner 只负责从自然语言生成 JSON 计划，PlanExecutor 负责参数解析、步骤引用和顺序执行。
- WorkflowEngine 持久化每一步的输入、输出、状态和错误，支持失败点重试。
- `PENDING_USER` 是显式的人机确认状态；确认数据应与原上下文合并后继续执行。
- 当前引擎只支持顺序步骤、重试和确认，不支持并行、分支、循环、补偿和可靠队列。

## 6. 异步任务与流式输出

- Spring `ThreadPoolTaskExecutor` 负责进程内任务，任务记录写入 `async_task`，工作流步骤写入 `workflow_step`。
- 取消必须同时中断 Future、更新状态并释放引用；客户端断开时 SSE 回调不能继续写已关闭的 response。
- 流式输出每次写入后 flush；Provider 返回非法 SSE JSON 时保留手动解析 fallback。
- 未声明 `task_type` 的旧版闭包任务在重启时标记为 FAILED；声明处理器的任务由 MySQL 调度器通过租约回收并继续执行。
- 测试环境关闭孤儿任务恢复，避免测试启动修改任务状态。
- 外部文献检索使用独立线程池，避免网络限速和等待占满 AI 任务线程；阶段文案相同则不重复写任务记录。
- 对外继续使用 `PROCESSING/COMPLETED`（分别表示 RUNNING/SUCCESS）以保持 API 兼容；所有转换由状态机校验，取消、超时等终态不能被迟到的阶段或完成回调覆盖。
- `PENDING_USER` 按 TTL 转为 `EXPIRED`，排队/运行任务按执行时限转为 `FAILED` 并中断 Future；同一 taskId 只有失败、取消、过期或待确认状态允许重新提交。
- Actuator 暴露 health/info/metrics；Micrometer 只记录低基数任务类型、结果、耗时、token 和分片数，日志不写提示词、论文正文或凭据，错误文本入库前需脱敏和限长。
- 可恢复任务只持久化 `task_type + context_json`，由 `AsyncTaskHandlerRegistry` 重建执行逻辑；数据库条件更新负责 claim，`lease_until` 防止进程崩溃后永久占用，`attempt_count/next_run_at` 实现指数退避和死信结算。幂等键配合请求哈希，避免同一键复用到不同参数。
- 可恢复任务提交前检查持久化队列深度，调度器同时受本地 in-flight 和数据库 PROCESSING 数量限制；超过容量返回 429，避免线程池和 MySQL 无限堆积。队列深度、in-flight、容量拒绝通过低基数 Micrometer 指标暴露。

## 7. RAG

- 论文分析结果按原文、贡献、方法、发现、局限、数据集等类型分块。
- Embedding 服务失败时返回空召回，不阻断论文管理；向量存储可在内存和 Qdrant 间切换。
- 标准链路是：向量粗召回 → 可选 LLM 重排序 → 拼接带 paperId/source 的上下文 → 交给 Agent。
- Qdrant 写入失败时可降级到内存，但生产环境应增加一致性检查和重建索引入口。
- `paper_chunk` 持久化由独立组件统一处理；Qdrant 失败降级只更新内存，不能再次写 MySQL。
- Embedding 批量接口应使用 Provider 原生 `embedAll`；重建索引先完成 Embedding，再替换旧索引。
- 内存向量条目预计算数组并使用读写锁，避免每次检索重复分配向量数组。
- RAG 重建采用 `rag_index_state + rag_index_version + paper_chunk.index_version`：新分片先写 BUILDING 版本，短事务内将其标记 READY、回收旧 ACTIVE 并切换 active_version；运行时向量存储再做 copy-on-write。Embedding、持久化或向量库失败不会先删除旧版本，过期 RETIRED/FAILED 版本由定时任务清理。
- Qdrant 点 ID 包含论文、索引版本和分片序号；版本切换失败时路由层回退到内存 active 版本，避免外部向量库短暂不可用阻断检索。

## 8. 多源学术检索

- `LiteratureSource` 抽象来源差异；各来源并行查询，统一按 DOI、arXiv ID、外部 ID 或规范化标题去重。
- API 超时、授权缺失和返回字段不完整都应转换为空结果，而不是阻断其他来源。
- Gap 验证应同时参考语义相似度、引用网络、发表时间和来源证据，不能只统计命中数量。

## 9. Vue 与 PDF.js 交互

- Element Plus 的内部 DOM 受 scoped CSS 限制，需要 `:deep()` 或非 scoped 样式覆盖。
- PDF.js 批注坐标必须保存为页面归一化坐标，阅读器缩放时再换算回 viewport。
- 长任务 composable 统一处理 loading、阶段文案、取消、重试和错误；全局任务 composable 在组件卸载时不自动取消。
- vxe-table 的行样式和斑马纹可能受 scoped CSS 影响，关键样式放在全局选择器。
- PDF 查看器和论文表格使用异步组件；VXE 表格组件改为局部加载，避免进入首页加载整套表格依赖。
- `useGlobalTask` 与 `useCancellableTask` 共享任务执行器，取消、重试和错误处理保持一致。

## 10. 验证与排查

- 前端至少执行 `npm.cmd run build`，关注公共 chunk 体积和 Rollup warning。
- 后端至少执行 `mvnw.cmd test`；集成测试必须使用独立 profile / 数据库。
- 外部 AI、Embedding、Qdrant、学术 API 都应有 mock 单测和失败降级测试。
- 排查顺序：浏览器 Network → Controller 日志 → Service 阶段日志 → Mapper SQL → 外部服务响应。
- 质量缺口仍包括 compare/gap eval、统一 schema 校验、多模型 fallback、契约测试和 Docker 验证。
- 论文精读质量门禁：`PaperAnalysisQualityGate` 对 POJO 和 fallback JSON 做确定性归一化（空值列表、空白文本、方法类型白名单、评分 1-10、嵌套摘要裁剪），不编造内容；关键字段缺失时保留回退并记录质量失败。`PaperAnalysisQualityGate.PROMPT_VERSION` 与耗时/token/修复状态一起记录，Golden Eval 资源放在 `backend/src/main/resources/eval/`。
- 论文分析自动修复采用单独的 `PaperAnalysisRepairService`，最多调用一次 LLM，修复结果必须再次通过 `PaperAnalysisQualityGate`；`LlmCallPolicy` 先在业务层限制输入字符、输出 token 和尝试次数，避免无限重试与成本失控。
- AI 质量事件写入 `ai_quality_event`，按 POJO/REPAIR/FALLBACK 阶段记录状态、Prompt 版本、token、延迟和校验问题；观测写入失败只告警，不回滚论文分析。Golden Eval 运行器只执行本地门禁，不调用真实模型，避免评测产生外部 API 成本。
- Mission 9.3 的质量事件以 `run_id` 关联一次分析链路，`final_status` 只在终态统计，避免 POJO、REPAIR、FALLBACK 多阶段重复计数；成功事件在事务提交后异步写入，质量拒绝事件立即异步落库以保留回滚前证据。
- LLM repair 调用通过 `ChatRequest.maxOutputTokens` 设置请求级上限，并保留响应后的 token/字符二次校验；质量事件支持按论文、终态、阶段、时间范围分页查询，保留策略由 `AI_QUALITY_RETENTION_DAYS` 与定时批量删除任务控制。
- Golden Eval 只保留 `backend/src/main/resources/eval/paper-analysis-golden.json` 这一份 fixture，除自动门禁指标外可选记录人工评分，避免测试资源与运行资源漂移。
- Mission 11 安全基线：设置读取端只返回脱敏值和 `configured`，保存端使用白名单、长度和类型校验；生产 profile 缺少 `RA_MASTER_KEY` 时 fail-closed，本地开发保留显式兼容模式。
- API 错误响应通过 HTTP 状态和 `{code,message,data}` 传递，异常日志只记录类型、请求 ID 等低敏信息，不记录 provider 响应体、密钥或论文正文。
- 默认监听 `127.0.0.1`，CORS 通过 `RA_CORS_ALLOWED_ORIGINS` 配置；MyBatis 默认关闭 SQL 日志，调试时显式设置 `MYBATIS_LOG_IMPL`。
- API 契约继续采用现有字段名和 `{code,message,data}` 包络；Workflow confirm/Search execute 使用带忽略未知字段的 DTO，避免动态请求直接进入业务上下文。
- 单机 AI 并发保护和异步任务容量保护是两层边界：前者限制同步 AI/SSE 请求，后者限制持久化异步队列，均不依赖 Redis。
- Mission 11.3 uses write DTOs as the boundary for dynamic payloads: Paper maps only client-editable metadata, Search execute/import preserves the existing snake_case fields, and Workflow confirm accepts only selected candidates and folderId. Unknown JSON fields are ignored for forwards compatibility while server-managed state cannot be injected.
