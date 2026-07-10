# CLAUDE.md — Research Assistant

## 项目简介

本地运行的 AI 驱动工科科研 Agent，帮助研究生进行文献检索、论文分析、Gap 识别、创新点推演、辅助建模与代码编写。前端 Vue 3 + Element Plus，后端 Java Spring Boot + MySQL + Redis，使用Langchain4j框架。

## 目标用户

工科研究生（CS / AI / EE），个人使用。

## 开发目标

1. 产出真正有用的科研辅助工具
2. 在实践中系统性学习后端开发与 Agent 开发
3. 作为简历上的「后端开发 / Agent 开发」项目

## 技术栈

| 层 | 技术 |
|----|------|
| 前端 | Vue 3 + Vite + Pinia + Element Plus |
| 后端 | Java 17 + Spring Boot 3 + Maven |
| ORM | MyBatis Plus |
| 数据库 | MySQL 8.0 |
| 缓存/队列 | Redis |
| LLM API | DeepSeek（用户自配） |
| PDF 解析 | Apache PDFBox（V2 引入） |
| 向量检索 | MVP：MySQL 持久化 + 内存向量存储（LangChain4j Embedding），后续可替换 pgvector / Qdrant |

## 项目结构

```
ResearchAssistant/
├── frontend/          # Vue 3 前端
├── backend/           # Spring Boot 后端
├── docs/              # 文档
│   ├── Spec.md        # 技术规格
│   ├── progress.md    # 阶段性功能完成记录
│   └── knowledge.md   # 学习知识总结
└── CLAUDE.md
```

## 开发流程（每次功能模块严格执行）

```
Plan（计划） → Explain（凝炼讲解） → Code（写代码） → Test（测试功能） → Review（审查代码）
```

| 阶段 | 说明 |
|------|------|
| **Plan** | 进入 Plan 模式，分析需求、设计方案、列出要改动的文件 |
| **Explain** | 将计划凝炼为通俗易懂的语言向用户讲解，确认理解后再动手 |
| **Code** | 用户确认后开始写代码 |
| **Test** | 测试代码是否完成目标功能 |
| **Review** | 审查、精简代码，并作必要的注释 |

## 协作规范

- 技术细节与功能设计写入 `Spec.md`，不在 CLAUDE.md 中重复
- 功能审查时可以调用playwright等mcp进行辅助
- 每个功能模块完成后，进行代码审查，精简代码
- 功能完成情况写入 `docs/progress.md`
- 重点知识总结写入 `docs/knowledge.md`
- AI 能力规划、阶段化实施路线与待补齐功能统一记录在 `docs/ai-features-roadmap.md`，每次 AI 层重构前优先阅读
- 代码编写时加入**必要注释**，方便后续系统性查阅代码

## 用户环境

- OS：Windows 10 Pro
- 项目路径：`C:\Users\PC\OneDrive\桌面\Research Assistant`
- Shell：Git Bash
- 可用工具：Node.js v24.18.0、Python 3、MATLAB
