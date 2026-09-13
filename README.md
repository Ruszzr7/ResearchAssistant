# Research Assistant

本地运行的 AI 科研助手，面向 CS / AI / EE 研究生，提供论文管理、PDF 阅读、论文理解、连续问答和研究档案。

## 本地启动

当前提供 Windows 原生启动脚本，不要求 Docker。首次运行前请安装：

- 完整的 JDK 17（必须包含 `javac`，仅安装 JRE 不够）；
- Node.js 18 或更高版本（包含 npm）；
- MySQL 8，并准备一个可用的本机实例（Windows 服务或已初始化的独立实例均可）；
- Git（仅克隆项目时需要）。

克隆仓库后，在项目根目录执行：

```text
scripts\start-all.cmd
```

脚本会自动完成以下工作：

1. 从 `JAVA17_HOME`、`JAVA_HOME`、`PATH` 和常见安装目录中寻找 JDK 17 或更高版本，仅在本次后端进程内使用它；
2. 识别并按需启动本机 MySQL Windows 服务或独立实例；
3. 验证数据库账号，缺少 `research_assistant` 数据库时尝试自动创建；表结构由 Flyway 自动初始化或升级；
4. 通过 Maven Wrapper 下载后端依赖；前端首次运行时根据 `package-lock.json` 执行 `npm ci`；
5. 等待数据库、后端健康检查和前端全部就绪后再报告成功。

脚本和项目目录的位置没有写死，仓库放在任意目录均可运行。脚本不会安装软件，也不会永久修改系统级 `PATH`、`JAVA_HOME` 或其他项目的配置。

### 本机环境变量

默认使用 `127.0.0.1:3306`、数据库 `research_assistant`、账号 `root` 和空密码。如果本机配置不同，请在启动当前终端前设置环境变量：

```bat
set "SPRING_DATASOURCE_USERNAME=root"
set "SPRING_DATASOURCE_PASSWORD=your-local-password"
set "RA_DB_HOST=127.0.0.1"
set "RA_DB_PORT=3306"
set "JAVA17_HOME=C:\path\to\jdk-17"
set "MYSQL_HOME=C:\path\to\mysql"
set "MYSQL_SERVICE_NAME=MySQL80"
set "RA_MASTER_KEY=your-stable-local-key"
scripts\start-all.cmd
```

路径覆盖不是必需的；脚本会优先使用显式环境变量，再尝试从 `PATH` 和常见安装目录中寻找依赖。如果本机同时安装了多个 MySQL 实例，建议显式设置 `MYSQL_HOME` 或 `MYSQL_SERVICE_NAME`。密码和 `RA_MASTER_KEY` 不应写入仓库或提交到 Git；`RA_MASTER_KEY` 应生成一次后保持稳定，更换它会使此前用旧密钥加密的 API Key 无法解密。

若 MySQL 用户没有建库权限，请先由管理员创建 UTF-8 数据库 `research_assistant`，再给配置的用户授予该库权限。应用启动后 Flyway 会自行建表，不需要手工执行第二套 SQL。

也可以分别启动或终止：

```text
scripts\start-database.cmd
scripts\start-backend.cmd
scripts\start-frontend.cmd
scripts\stop-all.cmd
```

`start-backend.cmd` 在后端未运行时启动后端，在后端已运行时重启后端。默认使用数据库 `3306`、后端 `8080`、前端 `5173`。脚本会等待端口和健康检查就绪；若端口被其他项目占用，会拒绝启动且不会结束不属于本项目的进程。因此不同项目不同时占用这些端口即可互不影响。

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
