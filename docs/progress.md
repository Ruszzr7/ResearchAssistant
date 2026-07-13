# 开发进度（精简版）

> 详细变更和历史讨论以 Git 为准；本文只保留阶段、交付和验证结果。

## 当前状态

- 最近一次交付：2026-07-13，Mission 13 旧库兼容修复与启动脚本修正。
- 当前主链路：论文入库 → PDF/元数据处理 → AI 分析 → RAG 索引 → 阅读、检索、Gap 与写作。
- 后端测试使用 `test` profile + H2；前端生产构建和单元测试已通过。
- 仓库当前记录到 Mission 13；部署与数据安全能力已并入 Mission 13，不再重复创建历史任务记录。

## 阶段索引

| 阶段 | 关键交付 | 验证 |
|---|---|---|
| 1–8 基础建设 | Vue 3、Spring Boot、MySQL、论文库、PDF、AI、RAG、阅读写作、性能与代码卫生 | 后端测试与前端构建持续通过 |
| 9 AI Quality Gate | 结构化输出门禁、repair、Prompt/Token 预算、质量事件、Golden Eval | Maven clean test：226 项通过 |
| 10 可靠任务与可观测性 | RAG 真实成功/失败、任务状态机、超时、TTL、MySQL 可恢复调度、幂等、指标与脱敏日志 | 后端 247 项通过；前端构建通过 |
| 11 安全与 API 契约 | 敏感配置脱敏、生产加密 fail-closed、请求 ID、异常安全响应、DTO 校验、MockMvc 契约测试 | 后端 266 项通过；前端构建通过 |
| 12 RAG 质量与证据 | 证据身份、版本、内容 hash、候选校验、Gap/写作引用、RAG Golden Eval、外部调用可靠性 | 后端 282 项通过；前端构建通过 |
| 13 交付与数据安全 | Flyway、Docker Compose、Nginx/SSE、健康检查、备份恢复、RAG 一致性巡检、前端产品化、旧库修复 | 后端 286 项通过；前端单测 5 项；前端构建通过 |

## 关键架构决策

1. Spring Boot 负责业务、持久化和 Agent 编排，LangChain4j 1.15.1 负责模型、工具、记忆和 RAG 接入。
2. Skill Registry、Planner 和顺序 Workflow Engine 保持业务编排可测试；复杂并行或补偿需求出现后再评估专用引擎。
3. RAG 默认使用内存向量存储，MySQL 保存分片和证据元数据，Qdrant 为可选 provider 并保留内存降级。
4. MySQL 由 Flyway 负责正式迁移；`schema.sql` 和 `schema-upgrade-*.sql` 仅作历史参考。
5. 任务状态、步骤、结果和可恢复上下文写入 MySQL；单机线程池负责执行，不引入 Redis。
6. Actuator/Micrometer、结构化日志和健康检查只暴露低基数、低敏信息，不记录密钥、提示词或论文正文。

## 当前待办

- 在真实部署环境执行多节点压力测试，并将指标接入告警平台。
- 继续按实际数据优化前端大 chunk 和复杂页面拆分。
- 如出现并行、分支、循环或补偿需求，再评估 Temporal/Camunda 等工作流引擎。
- 多模型 fallback 仍是可选增强，不影响当前单一有效配置。

## 文档维护

- 源码、Markdown、配置和脚本统一使用 UTF-8；PowerShell 读取中文文件显式使用 `-Encoding utf8`。
- 新功能只追加一行阶段记录；设计决策写入 `docs/knowledge.md`，不要复制完整 diff。
- 已完成事项不在 `docs/improvements.md` 重复维护。
