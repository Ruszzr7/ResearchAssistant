# PDF 工作台最终验收

验收日期：2026-07-16

验收基线：`5759b46` 及其之前的 P0–P3 阶段提交

结论：P0 至 P3-B2 规划闭环完成，当前未发现阻断交付的问题。

## 1. 阶段验收矩阵

| 阶段 | 主要实现证据 | 自动化 / 真实验收 | 结论 |
| --- | --- | --- | --- |
| P0 | `PdfViewer.vue`、`pdfAnnotation.js`、`pdfTextSelection.js`：PDF.js 渲染、50–300% 缩放、颜色、自动保存、编辑/删除、锚定便签、高亮/下划线范围调整 | `pdfAnnotation.spec.js`、`pdfTextSelection.spec.js`、`selection.spec.js` | 通过 |
| P1-A | `pdfLayoutIndex.js`、`pdfLayoutSelection.js`：文字 run、视觉行、单双栏、候选段落、同页同栏受控扩选 | `pdfLayoutIndex.spec.js` 5 项、`pdfLayoutSelection.spec.js` 10 项；WY 双栏论文浏览器拖选 | 通过 |
| P1-B1 | `PdfBoxPaperLayoutParser`：字形坐标、稳定中缝、列级 reading order、旋转页边块 | `PdfBoxPaperLayoutParserTest`；WY 16 页、2236 个行级块 | 通过 |
| P1-B2 | `PaperLayoutSemanticEnricher`、`PaperLayoutArtifactService`、Flyway V14：语义角色、段落、章节、版本化缓存 | 相关 service/mapper 测试；WY 1079 个语义块、841 个 evidence 候选 | 通过 |
| P1-B3a | `SelectionAnchorResolver`、`PaperLayoutEvidenceService`：服务端重建锚点、版本校验、局部证据 | 锚点/证据测试；旧解析版本返回 409，非法角色不进入 evidence | 通过 |
| P1-B3b | `pdfSelectionAnchor.js` 与 PDF 证据侧栏：归一化 line boxes、映射状态、页码/bbox 回链 | `pdfSelectionAnchor.spec.js`；WY 真实选区得到约 98% TEXT 锚点 | 通过 |
| P2-A | `WorkbenchRuleRouter`、`WorkbenchEvidenceGate`、Flyway V15：四条固定计划、Skill 白名单、一次 repair、run/step trace | 路由、门禁、trace 和 Controller 合约测试 | 通过 |
| P2-B | `WorkbenchExecutionEngine/Service`：选区问答、全文分析、批注建议、多篇对比的异步执行与恢复 | 执行引擎/服务/模型/报告测试；双论文 Evidence Gate 集成场景 | 通过 |
| P2-C | `PaperWorkbenchPanel.vue`、`usePaperWorkbench.js`：同页助手、运行恢复、步骤指标、受限 Markdown、批注确认、证据跳转 | 真实 WY 论文恢复并执行选区/全文/批注流程；刷新后批注不重复应用 | 通过 |
| P3-A | `AdaptivePaperLayoutParser`、外部适配器、V17、`TEXT/STRUCTURED/REGION`：低质量才回退，只有质量提升才采用 | adaptive/normalizer/region/quality 测试；WY 质量 0.9561，保持 PDFBox 快速路径 | 通过 |
| P3-B1 | `PdfWorkbenchEvalService/MetricsService`、确定性 golden set、可选真实 manifest、评测/指标 API | 确定性 7/7；WY 真实样本 1/1；隐私字段不进入响应 | 通过 |
| P3-B2 | `PdfWorkbenchMetricsPanel.vue` 与多篇对比 UI：7/30/90 天、2–8 篇边界、实际 claim 引用覆盖、跨论文跳转 | 前端指标/请求/组件测试；浏览器验证单论文 1/8 禁用与 Workflow 结果隔离 | 通过 |

## 2. 最终测试记录

| 检查 | 结果 |
| --- | --- |
| 后端全量测试 | 400 项通过，0 failure，0 error，0 skipped；启用 `RA_LAYOUT_SAMPLE` |
| 前端单元测试 | 10 个测试文件、42 项通过 |
| 前端生产构建 | Vite 构建通过；仅保留既有大 chunk 警告 |
| 后端运行状态 | `GET /actuator/health` 返回 `UP`；进程号由 `backend/backend.pid` 记录 |
| 在线版面评测 | 确定性 7/7；WY 1/1；其余 3 个可选真实样本因未配置而跳过 |
| 在线质量快照 | 1 份最新 artifact，平均质量 0.9561；34/34 claim 有证据回链 |
| 启动脚本 | `start-database.bat`、`start-backend.bat`、`start-frontend.bat`、`start-all.bat` 均以已运行状态正常返回 0 |
| 浏览器关键路径 | 真实 PDF 正常加载；论文助手恢复最近 Workflow；切换功能不串旧结果；单论文对比显示 1/8 并禁用；首页质量面板显示真实聚合值 |

## 3. 复现命令

后端全量测试（PowerShell）：

```powershell
$env:JAVA_HOME='C:\tools\jdk-17.0.19+10'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:RA_LAYOUT_SAMPLE='<本机真实 PDF 绝对路径>'
Set-Location backend
.\mvnw.cmd test
```

前端测试与构建：

```powershell
Set-Location frontend
npm.cmd run test:unit -- --run
npm.cmd run build
```

本地服务与在线检查：

```powershell
.\scripts\start-all.bat
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
Invoke-RestMethod http://127.0.0.1:8080/api/workbench/evaluation
Invoke-RestMethod 'http://127.0.0.1:8080/api/workbench/metrics?days=30'
```

## 4. 已知边界（非阻塞）

- 当前真实开发库只有一篇论文，因此没有为浏览器验收临时写入第二篇用户数据。双论文覆盖矩阵由前端组件测试验证，逐论文 Evidence Gate 由后端集成测试验证。
- GROBID/MinerU 回退适配器已实现，但真实运行仍需本机配置外部命令。此次只配置了 WY 样本；竖排页边、公式密集和扫描件三个可选真实样本明确显示为未配置，不伪装成已通过。
- 公式/表格只有在解析器提供可信 LaTeX 或单元格结构时才是精确证据；否则按 `REGION` 返回原页坐标，要求用户核对。
- 工作台不是 Acrobat 的完整替代品；复杂公式逐字符编辑、任意扫描件 OCR 和无限多论文上下文不在本阶段承诺内。
- 模型回答质量、延迟和 token 成本依赖设置页所选模型。固定 Workflow、Evidence Gate 与 trace 能约束过程，但不能消除供应商波动。
- Vite 仍报告主 bundle 大于 500 kB；当前已通过按页面异步加载隔离 PDF 组件，进一步 manual chunks 属于性能优化，不阻断本阶段功能验收。

## 5. 阶段提交链

```text
14ce7c8  后端 PDF 版面解析
f9fdb1a  语义版面制品持久化
4fdd21a  SelectionAnchor 与局部证据
47064f8  前端选区证据接入
71156ec  可审计工作台计划
b6ca550  固定 Workflow 执行
83acfc9  PDF 论文助手集成
a6f4e85  自适应解析回退
40e931a  评测集与质量指标
5759b46  指标面板与多篇对比
```

这些提交形成了从“可选 PDF 文本”到“有版本、有证据、有门禁、有 trace、有评测指标的论文 Agent 工作台”的完整演进链。
