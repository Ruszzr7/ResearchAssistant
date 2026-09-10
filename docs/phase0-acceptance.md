# 阶段 0：稳定验收基线

本文档是阶段 0 的执行与验收记录。浏览器链路使用确定性 API 适配层，不调用真实模型，也不写入日常 MySQL、论文目录或浏览器持久化数据；后端 Spring 测试可使用同一阶段的 `phase0` 配置，切换到独立 H2 和临时存储目录。

## 固定样本

| 样本 | 位置 | 结构特征 | 验证点 |
|---|---|---|---|
| `phase0-fixture.pdf` | `frontend/tests/e2e/fixtures/minimalPdf.js`（运行时生成，不落盘） | 单页、两行正文、一个带编号公式；正文证据对应两个物理框 | 完整证据文本、多个 locator、正文跳转、公式编号跳转和框选 |

固定样本的正文、公式和框坐标由同一文件导出，测试适配层返回完整 `fullText` 与全部 `contentRects/focusRects`，避免测试依赖当前用户论文数据。

## 确定性 Agent 轨迹

浏览器适配层在处理 Agent Turn 时记录并校验一条最小合法轨迹：加载 `paper-profile` → 使用稳定 `id/objective/query` 生成一个 Need 并调用 `paper-evidence` → 将实际返回的两个 `sourceObjectId` 绑定到 `submit_answer`。这不是对真实模型行为的断言，而是 UI 回归所依赖的固定协议夹具；真实 Agent 的 Need 质量和停止策略由后端 Agent 测试及后续阶段验证。

后端测试环境配置在 `backend/src/test/resources/application-phase0.yml`：H2 内存数据库、随机端口和 `${java.io.tmpdir}/research-assistant-phase0` 下的 PDF/图片/附件目录。现有 `test` profile 也已将派生文件改到 `${java.io.tmpdir}/research-assistant-test`。任何使用这两个测试 profile 的 Spring 测试都不会连接正式 MySQL 或 `data/` 目录。

## 执行命令

在项目根目录执行三次基线（脚本会自动检测系统 Chrome，并在连续三次通过后退出）：

```powershell
scripts\test-phase0.cmd
```

如需单次运行或指定浏览器：

```powershell
cd frontend
$env:PLAYWRIGHT_EXECUTABLE_PATH = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
npm.cmd run test:e2e -- paper-flow.spec.js
```

如果本机已安装 Playwright Chromium，可以不设置 `PLAYWRIGHT_EXECUTABLE_PATH`；否则使用系统 Chrome。失败时 Playwright 保留 trace、截图和视频，测试会附加 API 调用轨迹。

人工可见浏览器验收（只运行一次）：

```powershell
scripts\test-phase0-headed.cmd
```

后端全量回归前请确认 `JAVA_HOME` 指向完整 JDK 17（存在 `bin/javac.exe`、`bin/freetype.dll` 和 `bin/javajpeg.dll`），仅有裁剪版 JRE 会在图像/字体测试初始化时失败。

阶段 0 的退出验收要求连续执行三次上述命令均通过。每次测试都新建浏览器上下文并清空 `localStorage/sessionStorage`；所有 `/api/**` 请求均由适配层响应，未知请求直接阻断，因此不会污染正式数据库和存储目录。

## 自动化验收标准

- 可以通过文库导入固定 PDF，并在列表中看到论文。
- 导入返回确定性任务并轮询到“解析与论文理解已完成”，不会把完成态伪装成静态列表数据。
- 进入论文页后 PDF 页面和“论文理解已就绪”状态可见。
- 发送问题后出现回答和“查看依据（2）”。
- 同一回答同时展示正文依据和公式依据；公式依据显示公式本身，点击后优先框选打印编号。
- 依据显示完整两行文本，而不是只显示 `quote` 的第一行。
- 点击依据后仍停留/跳转到第 1 页，并绘制两个证据框。
- 点击公式依据后仍停留/跳转到第 1 页，并绘制公式编号框（PDFium 无法识别编号时允许使用公式区域回退框）。
- 刷新页面后回答和完整依据仍能从研究会话恢复。
- API 轨迹至少包含论文 PDF 请求和一次 Agent Turn 请求；没有真实模型请求；确定性 Agent 轨迹按“画像 → Need → 证据 → submit_answer”顺序完成。

## 阶段记录

| 项目 | 结果 |
|---|---|
| 基线提交 | `bcee258 feat: 完善证据检索与回答闭环` |
| 测试环境 | Windows + 系统 Chrome / Playwright + 完整 Temurin JDK 17（含 `freetype/javajpeg`） |
| 独立性 | 浏览器 API 适配层；不接正式 MySQL、PDF 目录和模型 |
| 后端阶段配置 | `application-phase0.yml` 已提供 H2 内存库、随机端口和临时存储目录 |
| 单次命令 | 通过（约 5 秒，含 1 个确定性 E2E） |
| 连续三次 | 通过（`scripts\\test-phase0.cmd`，3/3） |
| 可见浏览器 | 通过（`scripts\\test-phase0-headed.cmd`，1/1） |
| 后端定向测试 | 通过（44 项，0 失败、0 错误、0 跳过；使用证据/Agent 协议测试集合） |
| 前端单测、构建 | 通过（139 项；Vite production build） |
| 真实 API 调用 | 不执行（阶段 0 明确不依赖） |
| 后端全量测试 | 通过（507 项执行，0 失败、0 错误、18 项按条件跳过；使用完整 Temurin JDK 17） |
| 遗留问题 | 阶段 0 功能性验收无遗留；后端全量测试需要完整 JDK 17，不能使用被裁剪的 JRE。 |
