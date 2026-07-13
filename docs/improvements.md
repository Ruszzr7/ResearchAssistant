# 技术债与优化路线图（精简版）

> 已完成能力以 `docs/progress.md` 和 Git 历史为准；本文只保留仍有价值的后续事项。

## 当前待办

- 在真实部署环境完成多节点压力测试，并接入组织现有告警平台。
- 按真实数据继续优化 vxe-table、PDF.js 和 Markdown 依赖的首屏体积。
- 评估多模型 fallback；当前 LangChain4j 已支持 OpenAI 兼容模型，但只保留一个有效配置。
- 只有当 Workflow 出现并行、分支、循环或补偿需求时，才评估 Temporal/Camunda。

## 已完成工程基线

- AI 输出质量门禁、repair、Golden Eval、Token/耗时/失败指标。
- RAG 版本化、证据链、候选校验、Qdrant 降级和一致性巡检。
- MySQL 可恢复任务、租约、重试、幂等、容量保护和结构化日志。
- 设置脱敏、生产加密 fail-closed、API DTO/MockMvc 契约测试。
- Flyway、Docker Compose、Nginx/SSE、健康检查、备份恢复和前端单测/smoke E2E 配置。

## 技术边界

- 当前保持单机 Spring 线程池 + MySQL，不引入 Redis。
- 当前 Workflow 只承诺顺序执行、重试和人机确认，不承诺分布式调度。
- 当前 Docker Compose 已完成静态配置和脚本，真实容器压测应在具备 Docker 的部署环境执行。

## 面试定位

这是一个以 Java 17/Spring Boot 为工程骨架、以 LangChain4j 为 AI 接入层的本地科研 Agent。核心亮点是把 LLM、RAG、异步任务、可暂停 Workflow、人机确认、证据链和可运维部署串成可运行系统。
