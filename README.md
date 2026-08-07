# Research Assistant

本地运行的 AI 科研助手，面向 CS / AI / EE 研究生，提供论文管理、PDF 阅读、论文理解、连续问答、研究档案和写作辅助。

## 本地启动

需要 JDK 17、Node.js 和 MySQL 8。Windows 可直接运行：

```text
scripts\start-all.cmd
```

也可以分别启动或终止：

```text
scripts\start-database.cmd
scripts\start-backend.cmd
scripts\start-frontend.cmd
scripts\stop-all.cmd
```

`start-backend.cmd` 在后端未运行时启动后端，在后端已运行时重启后端。脚本会等待端口和健康检查就绪；若目标端口被项目外的未知进程占用，则拒绝误杀并提示人工处理。

前端地址为 `http://127.0.0.1:5173`，后端健康检查为 `http://127.0.0.1:8080/actuator/health`。模型供应商、通道、Base URL、模型和 API Key 在设置页配置。

本地导入的论文默认保存在项目根目录的 `data/papers`。文献库工具栏中的文件夹按钮可直接用系统文件管理器打开该目录；数据库只保存文件名，不保存绑定当前电脑的绝对路径。

## 部署

复制 `.env.example` 为 `.env`，配置数据库、模型和 `RA_MASTER_KEY` 后直接使用 Docker Compose：

```text
docker compose up -d --build
```

完整说明见 [部署与数据安全](docs/deployment.md)。数据库结构只由 Flyway 管理，旧库升级前请先阅读 [迁移说明](docs/migrations.md)。

## 开发验证

```text
cd backend  && mvnw.cmd clean test
cd frontend && npm.cmd run test:unit
cd frontend && npm.cmd run build
```

后端测试使用独立 H2 数据库，不读取本地开发数据。源码和文档统一使用 UTF-8；PowerShell 读取中文文件时使用 `Get-Content -Encoding utf8`。

## 文档

- [项目规格](docs/Spec.md)：当前产品、架构与边界。
- [API 契约](docs/api-contract.md)：请求、响应和工作台接口约束。
- [PDF 内容验收](docs/pdf-content-pipeline-acceptance.md)：搜索、选择、公式和证据回链标准。
- [部署与数据安全](docs/deployment.md)：Docker、备份和健康检查。
- [数据库迁移](docs/migrations.md)：Flyway 版本说明。
- [知识总结](docs/knowledge.md)：稳定设计决策和排查经验。
- [开发进度](docs/progress.md)：当前状态、里程碑和下一步。
