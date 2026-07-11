# AGENTS.md — Research Assistant

## 项目定位

本地运行的 AI 工科研发助手，面向 CS / AI / EE 研究生，覆盖论文管理、文献检索、论文分析、Gap 验证、阅读工作流和写作辅助。

Spring Boot 负责业务编排、持久化和异步任务；LangChain4j 接入 OpenAI 兼容模型；前端通过 REST/SSE 调用后端。

## 当前技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + Vite + Element Plus + vxe-table + PDF.js |
| 后端 | Java 17 + Spring Boot 3.2.6 + Maven |
| ORM / 数据库 | MyBatis Plus + MySQL 8 |
| AI | LangChain4j 1.0 + OpenAI 兼容 API |
| 检索 | 内存向量存储（默认）/ 可选 Qdrant；MySQL 保存分片 |
| PDF | PDFBox 默认实现，可接入 Marker / MinerU / Grobid 外部命令 |

项目当前不依赖 Redis，也不使用 Pinia；异步任务由 Spring 线程池执行并将状态持久化到 MySQL。

## 目录速览

```text
frontend/  Vue 页面、组件、API 与 composables
backend/   Controller、Service、Skill、Workflow、Mapper、Entity
scripts/   Windows / Bash 启动与环境检查脚本
docs/      规格、进度、知识总结、技术债
```

## 开发流程

Plan → Explain → Code → Test → Review。每个实质功能完成后更新 `docs/progress.md`；稳定的技术结论写入 `docs/knowledge.md`。Git commit、分支和推送前必须先征得用户同意。

## 常用命令

```text
前端开发：cd frontend && npm.cmd run dev
前端构建：cd frontend && npm.cmd run build
后端启动：cd backend && mvnw.cmd spring-boot:run
后端测试：cd backend && mvnw.cmd test
```

首次启动前需执行 `backend/src/main/resources/schema.sql`，并在设置页或环境变量中配置模型。设置 `RA_MASTER_KEY` 后 API Key 才会使用 AES-GCM 加密保存。
