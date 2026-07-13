# CLAUDE.md — Research Assistant

本项目是面向工科研究生的本地 AI 科研助手，覆盖论文库、文献检索、论文分析、Gap 验证、阅读工作流和写作辅助。

## 技术基线

- 前端：Vue 3、Vite、Element Plus、vxe-table、PDF.js。
- 后端：Java 17、Spring Boot 3.2.6、MyBatis Plus、MySQL 8。
- AI：LangChain4j 1.15.1，调用 OpenAI 兼容 API。
- 检索：默认内存向量存储，可选 Qdrant；分片和元数据保存在 MySQL。
- PDF：PDFBox 默认解析器，可按设置调用 Marker / MinerU / Grobid。

Redis 和 Pinia 当前不在实际运行链路中；异步任务使用 Spring 线程池并持久化状态。

## 代码结构

`frontend/` 为 Vue 页面与组件；`backend/` 按 Controller → Service → Mapper / Entity 分层，并包含 `service/ai/skill`、`service/ai/workflow`、`service/rag`；`scripts/` 提供 Windows 与 Bash 启动脚本；`docs/` 保存规格、进度和知识总结。

## 工作约定

按 Plan → Explain → Code → Test → Review 推进。功能完成后更新进度文档，重要技术结论写入知识文档。Git 操作在执行前先向用户确认；不要覆盖用户已有改动。

源码、文档和配置统一使用 UTF-8；PowerShell 读取中文文件显式指定 `-Encoding utf8`，Bash/WSL 使用 UTF-8 locale。
