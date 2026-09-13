# AGENTS.md — Research Assistant

## 项目定位

本地运行的 AI 工科研发助手，面向 CS / AI / EE 研究生，覆盖论文管理、论文分析、阅读工作流和连续研究对话。

Spring Boot 负责业务编排、持久化和异步任务；LangChain4j 接入 OpenAI 兼容模型；前端通过 REST/SSE 调用后端。

## 当前技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + Vite + Element Plus + vxe-table + PDF.js |
| 后端 | Java 17 + Spring Boot 3.2.6 + Maven |
| ORM / 数据库 | MyBatis Plus + MySQL 8 |
| AI | LangChain4j 1.15.1 + OpenAI 兼容 API |
| 检索 | PDF 版面混合检索；MySQL 保存兼容文本分片 |
| PDF | PDFBox 默认实现，可接入 Marker / MinerU / Grobid 外部命令 |

项目当前不依赖 Redis，也不使用 Pinia；异步任务由 Spring 线程池执行并将状态持久化到 MySQL。

## 工程原则：必要优化，不重复造轮子

- 优先调研、验证并复用成熟的开源库、平台原生能力和行业工具；不得在没有比较试验和明确收益的情况下自行重写通用基础能力。
- 自研代码只应解决项目特有的差异，或填补现成工具经真实样本验证后仍然存在的缺口。优先通过适配层、组合和小范围增强解决，避免替换整套已成熟机制。
- 允许引入对项目有明确价值的现成工具和依赖，不以“纯自研”为目标；引入前必须评估准确性、维护状态、许可证、安全性、体积、性能和跨平台部署成本，并优先选择可随项目本地打包、无按次调用费的方案。
- 对现成能力做扩展或替换前，先用项目真实失败样本建立可重复的对照测试；只在测试证明现成工具不满足需求时，实施最小必要的补充。
- `AGENTS.md` 只记录长期稳定的原则和约束，不锁定可能随验证结果变更的具体技术实现；库、模型、服务和架构选型应写入相应的方案或架构文档。

## 目录速览

```text
frontend/  Vue 页面、组件、API 与 composables
backend/   Controller、Service、Skill、Workflow、Mapper、Entity
scripts/   Windows 启动与环境检查脚本
docs/      产品规格与专项验收资料
```

## 开发流程

Plan → Code → Test → Review。每个实质功能完成后同步更新相关规格或验收文档。Git commit、分支和推送前必须先征得用户同意。

## 常用命令

```text
前端开发：cd frontend && npm.cmd run dev
前端构建：cd frontend && npm.cmd run build
后端启动：cd backend && mvnw.cmd spring-boot:run
后端测试：cd backend && mvnw.cmd test
```

首次启动由 Flyway 自动初始化/升级 MySQL；不要手工维护第二套建表脚本。请在设置页或环境变量中配置模型。设置 `RA_MASTER_KEY` 后 API Key 才会使用 AES-GCM 加密保存。

源码和 Markdown 等文本统一使用 UTF-8；PowerShell 读取中文文件使用 `Get-Content -Encoding utf8`，Bash/WSL 使用 UTF-8 locale，Windows `.bat` 保留 `chcp 65001`。
