# Research Assistant

中文说明；English version: [README.en.md](README.en.md)

Research Assistant 是一个面向 CS / AI / EE 研究者的本地科研助手。它把论文管理、PDF 阅读、论文理解、连续研究对话和研究档案连接起来，并通过可恢复的 Agent 运行记录和论文来源回链，让回答能够追溯到当前 PDF 版本。

## 主要能力

- **论文库**：文件夹、标签、筛选、批量操作、PDF 导入，以及 DOI / arXiv 元数据补全。
- **PDF 阅读**：搜索、缩放、高亮、下划线、文本选区、公式选区、选区笔记和批注跳转。
- **论文理解**：基于 PDF 版面和版本化来源生成论文画像、提取证据，并支持连续研究对话。
- **Agent 工作台**：使用 LangChain4j 的原生 Tool Calling 循环，支持持久化 Turn、Run、ToolCall、澄清和客户端操作等待。
- **页面操作**：在用户明确提出请求后执行跳转、高亮、下划线、笔记和评论；页面修改通过服务端 ActionTicket 和前端回执完成。
- **研究档案与任务**：保存研究消息、阅读进度、标注和异步任务状态，支持取消、重试、超时和重启恢复。

项目包含三个论文 Agent Skill：

| Skill | 职责 |
| --- | --- |
| paper-profile | 组织论文的研究问题、整体方法、贡献、实验和局限等全文视角信息 |
| paper-evidence | 查找公式、图表、算法、实验数值、页码和可引用原文证据 |
| paper-action | 将明确的页面操作意图转换为可信的跳转、高亮、下划线、笔记或评论操作 |

## 技术栈与架构

| 层 | 技术 |
| --- | --- |
| 前端 | Vue 3、Vite、Element Plus、vxe-table、PDF.js、PDFium/WASM 交互层 |
| 后端 | Java 17、Spring Boot 3.2.6、Maven、MyBatis-Plus |
| AI | LangChain4j 1.15.1，OpenAI-compatible API 与 Gemini Native |
| 数据库 | MySQL 8、Flyway |
| PDF | Apache PDFBox 版面与文本事实，PDFium 负责浏览器选择、搜索和精确回链 |
| 持久化文件 | 本地 PDF、图片、Agent 附件和运行日志 |

~~~text
Vue 3 / Vite
      │ REST / SSE
      ▼
Spring Boot / LangChain4j
      ├─ 论文库、阅读进度、标注与研究档案
      ├─ Agent Turn / Run / ToolCall 与异步任务
      ├─ 论文结构、画像、来源、引用和页面目标
      └─ 三个按需激活的论文 Skill
      │
      ├─ MySQL + Flyway：结构化数据与任务状态
      └─ 本地文件系统：PDF、图片与附件
~~~

## Windows 本地启动

### 环境要求

- Windows 10/11 x64；
- JDK 17 或更高版本，必须包含 javac；
- Node.js 18 或更高版本，包含 npm；
- MySQL 8，并准备一个可访问的本机实例；
- Git（仅在克隆仓库时需要）。

### 一键启动

在项目根目录执行：

~~~bat
scripts\start-all.cmd
~~~

脚本会依次：

1. 查找 JDK 17；
2. 检查并启动项目配置的 MySQL 实例；
3. 创建缺失的 research_assistant 数据库（账号有权限时）；
4. 通过 Flyway 自动初始化或升级表结构；
5. 启动 Spring Boot 后端和 Vite 前端；
6. 等待数据库、后端健康检查和前端全部就绪。

默认地址为：

~~~text
前端：http://127.0.0.1:5173
后端健康检查：http://127.0.0.1:8080/actuator/health
数据库：127.0.0.1:3306
~~~

### 数据库与密钥配置

可以通过环境变量覆盖数据库配置：

~~~bat
set "SPRING_DATASOURCE_USERNAME=root"
set "SPRING_DATASOURCE_PASSWORD=your-local-password"
set "RA_MASTER_KEY=your-stable-local-key"
~~~

RA_MASTER_KEY 应生成一次后保持稳定。设置后，设置页保存的 API Key 会使用 AES-GCM 加密；更换密钥会导致此前保存的 API Key 无法解密。密码、API Key 和主密钥都不要写入 Git。

模型供应商、通道、Base URL、模型名和 API Key 在网页设置页配置。Agent 调用需要可用的 OpenAI-compatible 或 Gemini API 以及对应额度。

## 测试与构建验证

后端测试使用独立的 H2 数据库，不读取本机开发库：

~~~bat
cd backend
mvnw.cmd clean test
~~~

前端单元测试和生产构建：

~~~bat
cd frontend
npm.cmd ci
npm.cmd run test:unit
npm.cmd run build
~~~

项目的源码测试、论文 Skill 路由测试和评测数据位于 backend/src/test。

## Skill 测评

当前冻结测评集包含 30 篇论文、每篇 10 个案例，共 300 个多标签案例。每个案例只运行一次，三个 Skill 分别计算 Precision、Recall 和 F1：

| Skill | Precision | Recall | F1 |
| --- | ---: | ---: | ---: |
| paper-profile | 100.00% | 94.87% | 97.37% |
| paper-evidence | 100.00% | 98.18% | 99.08% |
| paper-action | 100.00% | 100.00% | 100.00% |

完整指标口径和结果有效性见 [docs/agent-skill-metrics.md](docs/agent-skill-metrics.md)，测评集设计和金标规则见 [docs/skill-evaluation-plan.md](docs/skill-evaluation-plan.md)。

## 数据目录

默认本地数据目录为：

~~~text
data/papers/              # 导入的论文 PDF
data/figures/             # 论文图像或派生图像
data/agent-attachments/   # Agent 对话附件
~~~

## 项目结构

~~~text
backend/                  # Spring Boot、Controller、Service、Mapper、Agent
frontend/                 # Vue 3、PDF 阅读器和工作台
skills/                   # paper-profile、paper-evidence、paper-action
scripts/                  # Windows 启动、停止和评测脚本
docs/                     # 产品规格、测评方案和指标
data/                     # 本机论文与附件
~~~
