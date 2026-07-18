# PDF 精确选取与论文工作台规格

状态：P0 至 P3-B2 已完成并通过最终全链路审计（2026-07-16）；P4-A、P4-B 已完成并通过用户体验确认，P4-C 已提交为 `446ac36`，P4-D 与研究任务语义拆分已完成并纳入稳定提交。P4-E、P4-F 已完成实现与全量/真实链路验证，待用户体验确认。P0–P3 的验收证据与复现命令见 [PDF 工作台最终验收](pdf-workbench-acceptance.md)。

## 1. 产品目标：做“有证据的论文 Agent”，不是再做一个 PDF 编辑器

论文工作台要解决的不是把 PDF 放在聊天框旁边，而是让每个 AI 结论都能回答三个问题：它基于哪篇论文、哪一页、哪一段，以及这段内容的解析是否可信。

首要目标：

- 用户可在当前论文中选中正文或区域直接提问，把有用的助手输出编辑为批注或笔记，做全文分析，必要时加入多篇对比；
- AI 的回答必须带可跳回 PDF 的证据，不把页眉、页脚、参考文献或双栏串读内容伪装成正文；
- 对复杂 PDF 允许降级为“视觉区域上下文”，而不是制造看似精确的错误文本；
- 工作流可测试、可观测、可恢复，形成项目可写入简历的 Agent 编排亮点。

明确不做：

- 不复制 Acrobat 的完整编辑器能力；
- 不承诺公式逐字符可选或所有扫描件可解析；
- 不引入没有边界的多 Agent 自由讨论；
- 不让模型直接执行删除批注、移动文件夹等确定性、有副作用的 UI 操作。

## 2. 可落地的产品形态

PDF 页面仍是论文阅读入口，左侧为 PDF，右侧为“论文助手”。右侧只有四类清晰入口：

| 用户上下文 | 可做的事 | 默认范围 |
| --- | --- | --- |
| 选中正文 | 解释、翻译、提问、把结果加入批注 | 精确选区；解释/问答可在后台使用有界邻近证据 |
| 框选区域 | 解释公式、图或表 | 区域 + 相交块 |
| 未选内容 | 总结、方法拆解、局限、全文问答 | 当前论文正文 |
| 已选多篇论文 | 对比方法、假设、指标、结论 | 指定论文集合 |

P4 将“论文分析”“研究空白/对比阅读”和 PDF 内问答统一为同一个论文工作台，以 `SELECTION / PAPER / COMPARISON` 范围和具体任务区分能力，不再维护重复入口。写作助手继续作为独立页面，阅读计划已经从产品与数据模型中移除。旧前端页面已移除，`/analysis` 与 `/gap` 保留为兼容跳转；历史后端接口暂不删除，避免破坏已有调用方。

## 3. 最小文档数据模型

不在第一期直接建设庞大的通用 Document Graph。先为每篇已解析论文持久化一个可版本化的 `PaperLayoutArtifact`，它足以支撑证据、检索和回链。

```text
PaperLayoutArtifact {
  paperId, documentHash, parserVersion,
  layoutConfidence, generatedAt,
  blocks[]
}

DocumentBlock {
  id, page, bbox(normalized), role,
  readingOrder, sectionPath,
  text, latex?, tableText?, confidence
}

SelectionAnchor {
  paperId, page, boxes[], anchorText,
  blockIds[], tokenRange?,
  kind: TEXT | REGION | FORMULA | TABLE,
  confidence, documentHash, parserVersion
}
```

`role` 仅保留实际需要的类别：`TITLE`、`AUTHOR`、`ABSTRACT`、`HEADING`、`BODY`、`FIGURE`、`CAPTION`、`FORMULA`、`TABLE`、`REFERENCE`、`HEADER`、`FOOTER`、`MARGIN_METADATA`。

默认 AI 上下文只使用 `ABSTRACT`、`HEADING`、`BODY`、`CAPTION`、`FORMULA`、`TABLE`。出版信息可用于元数据，但不得混入正文证据。

## 4. 解析策略：先快后准，低置信度才回退

### 4.1 默认本地解析

1. PDFBox 提取字符及坐标，按 y 轴聚合为行、按 x 轴空隙识别单栏或双栏；
2. 对双栏页按“左栏从上到下，再右栏从上到下”生成 `readingOrder`；
3. 利用跨页重复、页边缘位置和字号识别页眉、页脚、页码与左侧出版信息；
4. 将块、坐标、章节层级和置信度写入 `PaperLayoutArtifact`，后续请求复用而非重复解析。

这一步可以基于现有 PDFBox/PDF.js 能力渐进实现，不要求马上替换整个 PDF viewer。

### 4.2 回退条件

仅在下列情况调用可选外部解析器（GROBID 或 MinerU 适配器）：

- 单双栏判定不稳定；
- block 明显跨列或正文顺序不连续；
- 公式/表格区域无法得到可信文本；
- 扫描件或字符覆盖率过低。

外部解析必须有超时、任务状态、失败回退和结果缓存。失败时保留视觉区域模式，不把低质量 OCR 当成可引用正文。

## 5. 选取与批注的边界

### 5.1 选取映射

原生浏览器 selection 负责“用户到底圈了哪里”，不是 AI 的最终阅读顺序：

1. 将 selection 的 client rect 按页转成归一化 boxes；
2. 用 boxes 与 `DocumentBlock` 的 IoU、文本前后缀匹配；
3. 命中成功时生成 `SelectionAnchor(TEXT)`；
4. 命中不足时生成 `SelectionAnchor(REGION)`，在 UI 标记“按区域理解”。

标题、作者与复杂公式不能可靠映射时，宁可进入区域模式。公式/表格优先使用 block 中的 LaTeX 或表格文本；没有时只传截图区域和置信度说明。

### 5.2 批注交互

- 高亮、下划线和便签都是独立、自动保存的对象；
- 编辑模式中点击批注选中，删除直接作用于该对象；
- 高亮/下划线用左右手柄调整首个/末个矩形的可视范围，拖动结束后自动保存；这是一期的几何编辑，真正的 token 级重锚定留给 `SelectionAnchor` 完成后实现；
- 自由便签保存 `notePosition`；锚定便签保存 `anchorQuads + anchorText + notePosition`；
- 默认不提供自由画笔，避免把“圈注”做成难以检索、难以回链的涂鸦。

## 6. Agent 编排：受限计划，而不是自由代理

工作台的核心技术亮点应是“layout-aware、evidence-grounded、bounded agent loop”。模型只决定语义任务和答案组织；范围、工具权限、证据校验和副作用全部由后端约束。

### 6.1 统一调用上下文

```text
WorkbenchInvocation {
  userId, paperIds[], question, intent?,
  scope: SELECTION | REGION | PAPER | COMPARISON,
  selectionAnchor?, parserVersion,
  maxSteps, tokenBudget, evidenceRequired
}
```

`scope` 由 UI 和规则优先确定：有选区时默认 `SELECTION`；用户明确说“全文”才扩大到 `PAPER`；只有明确选择多篇论文才进入 `COMPARISON`。不清楚时先让用户选择范围，不能静默检索整个文库。

### 6.2 Skill 契约

| Skill | 类型 | 输入 | 输出 | 约束 |
| --- | --- | --- | --- | --- |
| `resolveSelectionContext` | 确定性 | `SelectionAnchor` | block、邻近文本、置信度 | 不调用模型 |
| `ensureLayoutArtifact` | 确定性/异步 | paper + parser policy | artifact 状态 | 可缓存、可回退 |
| `retrievePaperEvidence` | 确定性 | paper、query、scope | 有页码/块 ID 的证据 | 过滤 HEADER/FOOTER/REFERENCE |
| `synthesizeEvidenceAnswer` | LLM | 问题 + 受限证据 | 结构化答案草稿 | 不得自造引用 |
| `validateEvidenceAnswer` | 确定性 | 草稿 + evidence | pass / repair reason | 检查证据 ID、覆盖率、范围 |
| `analyzePaper` | LLM workflow | artifact + 模板 | 结构化报告 | 单篇、可持久化 |
| `compareEvidenceSet` | LLM workflow | 多篇统一证据 | 对比矩阵 | 只读指定集合 |
| `identifyResearchGaps` | LLM workflow | 3–8 篇分论文证据 | 候选 Gap + 可检验问题 + 验证方案 | 不得把“未检索到”写成“领域不存在” |
| `proposeAnchoredAnnotation` | LLM | anchor + 指令 | 批注建议 | 用户确认后才创建 |

Skill 必须有清晰 I/O DTO、超时、token 预算和可单测的纯逻辑部分。`create/update/delete annotation` 仍是确定性 API，由用户点击确认，不交给 Agent。

### 6.3 Bounded Plan–Execute–Ground Loop

每次工作台请求最多经历一个受限循环，而不是让 Planner 无限调用工具：

```text
Preflight
  → Rule Router
  → WorkflowPlan（最多 3 个只读 Skill）
  → Execute
  → Evidence Gate
  → [一次 Repair 或 Final Answer]
```

1. **Preflight**：校验论文、artifact 版本、选区置信度、预算和用户范围；
2. **Rule Router**：把明显意图直接路由，例如“解释选区”“全文总结”“比较两篇”；仅模糊自然语言才调用轻量 LLM 分类器；
3. **WorkflowPlan**：输出受 JSON Schema 约束的 `route / allowedSkills / maxSteps / evidenceRequired`，后端拒绝计划外 Skill；
4. **Execute**：顺序运行固定 Workflow，记录每步输入摘要、耗时、模型消耗和 artifact 版本；
5. **Evidence Gate**：校验回答中的每个引用是否来自本次 evidence set；不足时只允许一次基于同一证据集的 repair；
6. **Final**：返回答案、证据锚点、置信度和“查看完整报告/加入对比”的明确下一步。

这套 loop 可直接复用项目已有的 Skill Registry、Planner、PlanExecutor、质量门禁、异步任务和任务持久化能力，不额外引入复杂编排框架。

## 7. 固定 Workflow

### 7.1 选区提问

`resolveSelectionContext → retrievePaperEvidence(局部) → synthesizeEvidenceAnswer → validateEvidenceAnswer`

若 `SelectionAnchor.confidence` 低，只把任务标记为区域解释，不输出“原文第 X 句”的伪精确引用。

### 7.2 全文分析

`ensureLayoutArtifact → retrievePaperEvidence(全文分段) → analyzePaper → validateEvidenceAnswer → persist report`

报告沿用现有论文分析实体，增加 artifact/parser 版本与证据列表，避免一份报告在 PDF 重新解析后无法回链。

### 7.3 多篇对比

`ensureLayoutArtifact(all) → retrievePaperEvidence(per paper) → compareEvidenceSet → validateEvidenceAnswer`

对比维度由用户选择或由受限 Schema 提议，例如问题、假设、方法、数据集、指标、结论与局限；不得把不同论文的证据混为一条来源。

### 7.4 手动选区批注（P4 替换目标）

`user selects PDF text → write comment → confirm → deterministic Annotation API`

P4 不再让模型额外猜测用户想写什么批注，也不在助手输出中提供二次“添加内容”编辑器；助手结果保持原生选择和复制。用户在 PDF 中精确选中文字后点击“批注”，弹窗显示关联原文并只要求填写自己的批注；确认后以 `NOTE` 保存，选区几何、原文和 emoji 位置一并持久化。页面任意位置的自由便签保留独立入口，二者共享已有自动保存、拖动、编辑和删除链路。

### 7.5 选区翻译与结果语言（P4）

`resolve exact selection → TranslationService → DeepLTranslationProvider → preserve formula/citation → cache result`

翻译属于确定性外部服务调用，不经过论文问答 LLM，也不触发新一轮全文检索或 Evidence Gate。默认只翻译用户精确选中的内容；长文本分块时必须保持 LaTeX、引用编号、数值、缩写和段落顺序。工作台回答默认中文，术语首次出现按“中文名称（English Full Name, ABBR）”展示；用户切换英文时复用同一个翻译服务转换既有答案，不重新执行论文分析。

## 8. 持久化、可观测性与评估

建议新增或扩展以下持久化对象：

```text
paper_layout_artifact: paper_id, document_hash, parser_version, status,
                       layout_confidence, blocks_json, created_at
paper_workbench_run:  run_id, paper_ids, scope, workflow, status,
                       artifact_versions, token_usage, latency_ms, result_json
paper_workbench_step: run_id, step_name, skill_name, status,
                       evidence_count, retry_count, error_code
```

核心指标：

- 选区映射成功率与低置信度降级率；
- 双栏样本的阅读顺序正确率；
- 回答证据覆盖率、无效引用率、repair 率；
- 每个 Workflow 的耗时、token 和失败原因；
- 外部解析回退率及其相对收益。

建立真实测试集：单栏、IEEE 双栏、左侧出版信息、密集公式、扫描页。每个样本标记正文顺序、应排除的页眉页脚、代表性选区和预期证据页码。

### 8.1 P1 前端版面索引验收样例

真实论文仅在本机验收，不提交到仓库。当前使用以下本地样例的首页/前几页验证 PDF.js 文字层的前端索引：

| 本地样例别名 | 关键版面 | 本阶段验收点 |
| --- | --- | --- |
| `RSMA_AoI_Related_Work/2` | 大标题、多作者居中、左侧竖排 IEEE 出版信息、摘要与正文双栏 | 同一基线左右栏拆为不同视觉行；双栏数为 2；竖排边注保留为非正文 text run |
| `RSMA_AoI_Related_Work/5` | 会议论文首页、双栏正文与页眉出版信息 | 标题/页眉不误判为第二正文栏；正文保持左栏后右栏的候选顺序 |
| `RSMA_其余指标/能量效率/ee` | 密集公式和双栏技术正文 | 公式/不稳定文字层不强行归为精确文本；后续应降级为区域选取 |

本阶段浏览器验收（2026-07-15）：第一样例在阅读器首页及后续可见页均生成 `layoutIndexed=true` 的文字层，前三页识别为双栏；从 100% 切换到 125% 后索引重新生成。该索引现已驱动受控选择：起止点必须命中水平文字 run 的 2px 范围，选择限定在同一页、同一栏；空白、全宽标题、竖排边注、跨栏和跨页拖拽都会拒绝扩张或保留最近一次有效选区。它仍是纯前端、临时的 viewport 索引，尚未持久化为后端 `PaperLayoutArtifact`。

### 8.2 P1-B1 后端版面解析验收

后端已定义 `PaperLayoutArtifact`、`DocumentBlock`、`DocumentBlockRole` 和左上角归一化坐标契约。`pdfbox-layout-v1` 从 `TextPosition` 建立行级块：用跨多行稳定中缝判定双栏，以跨栏块为纵向分段锚点，并在每段内按左栏、右栏生成阅读顺序；旋转页边文字使用真实页面坐标并单独标记为 `MARGIN_METADATA`。

自动化样例覆盖单栏、双栏、页眉页脚和旋转边注。WY 真实论文本地验收（2026-07-16）生成 16 页、2236 个视觉行，整体置信度 0.900，首页与第 13 页抽查未发生左右栏同基线串读。该几何解析内核已完成并作为 B2 的输入，不直接暴露为最终检索证据。

### 8.3 P1-B2 语义制品与缓存验收

`semantic-v1` 在 B1 视觉行之上确定性识别标题、作者、摘要、章节、正文、公式、表格、图注、参考文献、页眉页脚和页边元数据；段落仅在同页、同栏、角色兼容且几何连续时合并，并只清理字母断行产生的软连字符。正文证据采用白名单：`ABSTRACT/HEADING/BODY/CAPTION/FORMULA/TABLE`，其他角色不可进入局部检索上下文。

Flyway V14 新增 `paper_layout_artifact`，唯一键由 `paper_id + document_hash + parser_version` 构成，其中解析器版本已组合几何与语义版本。`GET /api/papers/{id}/layout-artifact?refresh=false` 在 PDF SHA-256 或版本变化时重建，否则直接读取缓存；写入后回读数据库中的规范化制品，保证首次与缓存响应完全一致。

WY 真实论文验收结果：16 页、2236 个视觉行收敛为 1079 个语义块，其中 841 个满足 evidence 白名单；角色分布为 `TITLE 1 / AUTHOR 1 / ABSTRACT 1 / HEADING 30 / BODY 627 / CAPTION 12 / FORMULA 171 / REFERENCE 79 / HEADER 71 / FOOTER 54 / MARGIN_METADATA 32`。首次强制重建 1289 ms，第二次缓存读取 82 ms，PDF hash、组合版本、生成时间和块数严格一致；后端健康检查为 `UP`，Flyway 已迁移至 v14。

### 8.4 P1-B3a SelectionAnchor 与局部证据验收

前端选区请求只包含页码、左上角归一化 boxes、选中文本和可选区域类型。`SelectionAnchorResolver` 使用选区覆盖率、文本 token 覆盖率和块置信度重新匹配当前 artifact；可靠正文、公式和表格分别生成 `TEXT/FORMULA/TABLE`，非证据角色或匹配不足时生成 `REGION`。客户端不能指定可信块 ID，越界 boxes 和超量锚点会被拒绝。

`PaperLayoutEvidenceService` 校验论文、PDF hash 与组合解析版本，从已命中块建立最多四个 reading-order 距离的有界邻域，只返回 `ABSTRACT/HEADING/BODY/CAPTION/FORMULA/TABLE`。每条 evidence 由论文、hash、版本和块 ID 生成稳定 `lay_*` 身份，并保留页码、bbox、章节路径、角色、分数与块置信度；旧锚点返回 HTTP 409，页眉等非法区域没有相交正文时返回空 evidence，而不是抓取附近文字。

WY 论文在线验收：正文块 `p1-b0063` 映射为 `TEXT`，置信度 0.964、hash/版本一致；局部检索返回 5 条证据，均为白名单角色且选中块可回链，伪造 `obsolete-parser` 被 409 拒绝。证据入口会忽略伪造 `blockIds` 并按 boxes/text 再解析。定向 13 项和后端全量 332 项通过；其中 3 项仅因全量测试未设置本机真实 PDF 环境变量而跳过，真实样例定向执行已通过。

### 8.5 P1-B3b 前端锚点与证据回链验收

受控选区完成后，前端把每条视觉行的 viewport quad 合并并转换为左上角归一化 boxes，只提交页码、boxes 和截断后的选中文本。证据面板在 `pointerup` 后才挂载，展示 `TEXT/FORMULA/TABLE/REGION` 映射状态、置信度和局部证据；点击证据会先挂载目标页，再把后端归一化 bbox 投影到当前 viewport，显示临时定位框。

WY 真实论文浏览器验收：选取首页 Introduction 正文后得到 `TEXT` 锚点、98% 置信度和 5 条有界证据，点击当前选中证据后页码保持第 1 页并出现回链定位框。窗口级 `pointerup` 兜底覆盖拖出文字层后松开的路径；前端 7 个测试文件共 30 项、生产构建和后端全量 335 项通过（3 项可选真实样例跳过）。

### 8.6 P2-A 可审计编排内核验收

Flyway V15 新增 `paper_workbench_run / paper_workbench_step`。run 保存论文集合、scope、固定计划、artifact hash/parser 版本、最大步骤、token 预算、repair/证据/token/耗时和终态；step 只保存输入输出摘要、Skill 白名单身份、状态与安全错误码。`POST /api/workbench/runs/plan` 在创建 trace 前确保每篇 artifact 已存在，并拒绝版本过期的 SelectionAnchor；`GET /api/workbench/runs/{runId}` 可在重启后回读完整计划和步骤状态。

规则路由只允许 `SELECTION_QA / PAPER_ANALYSIS / ANNOTATION_SUGGESTION / PAPER_COMPARISON`，最多 8 篇、6 步和一次 repair；allowed skills 必须与固定步骤集合严格相等。Evidence Gate 以结构化 claim 验证本次 evidence IDs 和 100% claim 覆盖率，未知引用首次返回 `REPAIR`，repairAttempt=1 后返回 `REJECT`。路由、门禁、持久化状态机和 Controller 定向 15 项通过，后端全量 350 项通过（3 项可选真实样例跳过）。真实 MySQL 已迁移至 v15；论文 175 的 `TEXT` anchor 在线生成并回读 `SELECTION_QA / SELECTION / 4 steps` trace，artifact hash 与 `pdfbox-layout-v1+semantic-v1` 一致。

### 8.7 P2-B 固定 Workflow 执行验收

四条计划已接入同一可恢复执行引擎：选区问答和批注建议使用锚点局部证据，全文分析按版面顺序检索论文证据，多篇对比为每篇论文保留独立证据配额。模型响应被约束为结构化 JSON；质量门禁先验证 Workflow 所需字段，再由 Evidence Gate 校验证据白名单、逐 claim 覆盖率、选区引用和对比论文覆盖，失败最多 repair 一次。

门禁通过的规范化结果先写入 run checkpoint，进程中断后可跳过重复模型调用；全文分析随后写入 `paper_analysis`，同时保存 evidence IDs、workbench run ID、PDF hash 和 parser version。外部模型与数据库短暂错误由可恢复任务重试，最后一次失败会把 run 同步置为安全终态。真实论文 175 已在线完成选区问答、批注建议和全文分析，其中全文分析使用 48 条可回链证据生成 11 条 claim，三条运行均不需要 repair；开发库仅有一篇论文，多篇对比以双论文集成测试覆盖。后端全量 375 项通过（3 项可选真实样例跳过），Flyway V16 在真实 MySQL 迁移成功。

### 8.8 P2-C 同页论文助手验收

PDF 右侧助手固定展示四个 Workflow 入口，并复用 P1-B3b 的选区锚点与证据跳转。创建计划后绑定可恢复任务，轮询阶段状态，同时以 run trace 展示每步状态、evidence、repair、token、耗时和安全错误；按论文读取最近运行可在刷新后恢复结果。模型报告先转义再按受限 Markdown 语法渲染，claim 证据按钮直接回链原页。

批注建议只生成预览，用户点击确认后才保存锚定 NOTE；批注坐标保存 `workbenchRunId`，因此刷新后对应建议显示“已添加批注”并拒绝重复写入。应用启动及每 30 秒对账活跃 run 与异步任务，旧任务终态不会让 UI 永久停留在运行中。论文 175 真实浏览器验收：历史全文分析恢复成功；首页选区以 98% 置信度完成 1 条证据、5 条 claim 的问答；批注建议确认后保存 3 个锚点框且刷新仍可见。后端全量 378 项、前端 35 项和生产构建通过。

### 8.9 P3-A 自适应解析与区域证据验收

`adaptive-layout-v1` 始终先执行 PDFBox，并以文本密度、乱码、坐标、块置信度、reading order 和解析器自报置信度组合质量分。分数低于 0.68 或出现空文本/明显乱码才有资格调用外部解析；回退命令必须由用户显式启用，支持 `{input}/{output}` 参数模板、120 秒超时和 25 MiB 输出上限，不经 shell。MinerU layout/content-list JSON 与带坐标 GROBID TEI 归一化为统一页码、左上角归一化 bbox 和角色；GROBID XML 禁止 DTD/外部实体。外部质量至少为 0.60 且比 PDFBox 高 0.04 才会被采用，否则安全保留快速路径。

Flyway V17 将 `primaryParser/selectedParser/fallbackAttempted/fallbackAccepted/primaryQuality/fallbackQuality/issues/failureCode` 与 artifact 一起持久化。`DocumentBlock` 和 `LayoutEvidence` 增加 `TEXT/STRUCTURED/REGION` 内容模式：公式没有可信 LaTeX、表格没有可信结构时强制生成 `REGION` anchor，返回可回链 bbox 与核对提示，不向模型暴露损坏文本；区域块可服务选区工作流，但从全文分析和多篇对比的文本采样中排除。WY 真实论文保持 16 页、2236 行，质量分 0.956、91,430 字符、坐标有效率 100%、无回退告警。后端全量 393 项（4 项可选样例跳过）、真实样例定向 12 项、前端 35 项及生产构建通过。

### 8.10 P3-B1 评测集与聚合指标验收

仓库内 `pdf-workbench-golden.json` 是无版权内容、无模型调用的确定性微型评测集。当前 7 个 case 覆盖 IEEE 风格语义角色和连续 reading order、双栏正文选区、页眉/参考文献证据隔离、非结构化公式/表格 `REGION` 降级，以及外部解析器结构化公式的 LaTeX 保留。`PdfWorkbenchEvalService` 直接运行生产使用的 semantic enricher、anchor resolver、evidence policy/service 和质量评估器；任何 case 的失败会返回稳定 case ID 与低敏 issue code。

真实论文不提交仓库。`pdf-workbench-real-manifest.json` 只记录环境变量别名、最小页数/质量、必需角色、禁止证据角色和代表性归一化选区；配置 `RA_LAYOUT_SAMPLE` 后会对 WY 论文执行 adaptive parser、语义增强、质量门禁、选区锚定与 evidence 回链。评测结果按环境配置和文件大小/修改时间缓存 10 分钟，API 只暴露 case 别名、状态、issue code 和耗时，不暴露本机路径或论文内容。当前 WY case 为 16 页、质量高于 0.90，页 13 结论段映射为 `TEXT` 并命中 `p13-b0003`。

`GET /api/workbench/metrics?days=30` 从持久化数据生成重启稳定的聚合快照：每篇论文仅统计最新 READY artifact，run 时间窗限制为 1–365 天且最多 5000 条。指标包含版面平均质量、低质量数、回退资格/尝试/采用和平均收益、`TEXT/STRUCTURED/REGION` 块数、选区锚点类型/平均置信度/区域降级率、claim 证据覆盖、无证据与门禁拒绝、repair、多篇逐论文引用覆盖，以及四条固定 Workflow 的完成率、token、证据和耗时。坏 JSON 与窗口截断会单独计数，论文文本、问题和路径不进入响应。确定性评测 7/7、WY 真实 manifest 1/1、后端全量 400 项通过。

### 8.11 P3-B2 产品面板与多篇对比验收

首页 `PDF 工作台质量` 面板消费 P3-B1 的聚合快照，支持 7/30/90 天窗口。首层卡片显示确定性与真实 PDF case、最新 artifact 平均质量、Workflow 终态成功率、claim 覆盖和多篇逐论文覆盖；下层拆出版面回退/内容精度/门禁失败，以及四条固定 Workflow 的完成、repair、平均耗时和 token。零样本显示“未配置/暂无运行”，不会把 0/0 伪装成 100%；面板尾部明确数据边界，不展示论文正文、提问或路径。真实数据库在线显示确定性 7/7、WY 1/1、平均版面质量 95.6% 和 claim 覆盖 34/34，7 天切换会重新请求对应窗口。

多篇对比把当前 PDF 固定为基准论文，额外论文去重后总数必须为 2–8；达到 8 篇时禁止继续选入，少于 2 篇时禁用执行。研究问题、核心方法、实验与指标、主要结论、局限和适用场景是显式可选维度，只拼入用户问题，不改变固定 Workflow/Skill 白名单。结果根据 claim 实际引用的 evidence ID 生成逐论文矩阵，显示标题、证据数、被引用 claim 数和页码；每篇至少有证据且被 claim 引用才算覆盖。点击当前论文证据会原地定位；其他论文证据通过 `open-paper-evidence` 切换详情和 PDF 实例，再以 initial evidence 跳到目标页/bbox，不能在当前 PDF 上复用另一个论文的页码。当前真实库仅有一篇，浏览器验证提示“1/8、暂无其他论文”且执行禁用；双论文覆盖矩阵由前端组件测试和后端 Evidence Gate 集成测试验证。前端 42 项及生产构建通过。

### 8.12 P4-A 问答可靠性与精简状态验收

选区局部证据由原先最多 8 条、reading-order 距离 4 的候选收敛为“全部直接选中块 + 前后各至多一个允许角色块”，并按直接选中优先执行上限裁剪。前端建立 `SelectionAnchor` 后不再重复请求局部证据；邻近上下文只在用户真正发起问答时由后端检索，页面“当前选区”只展示原始精确选中文字和锚点状态。

选区模型调用现在共享完整 run token 预算，但首次调用预留一次内部精简恢复额度。若 provider 返回空正文或 `LENGTH/MAX_TOKEN`，系统只用直接选中证据自动重试一次；两次调用严格共享总预算。成功与失败步骤都会累计 prompt/completion token、attempt count、是否使用空输出恢复和 provider `finishReason`，任务级重试不能再把失败调用成本误记为零。选区回答 prompt 约束为简洁中文、最多 4 条 claims，并禁止输出思考过程。

前端将持久化的底层步骤聚合为固定四个横向状态点：完成为绿、执行中为蓝色脉冲、失败为红、未执行为空心灰；常驻步骤名称与 token 文本移入 hover/focus 提示。真实论文 175 首页选区映射置信度 92%，在线 `SELECTION_QA` 使用 3 条证据、1781 tokens、约 23 秒一次完成，`attemptCount=1 / emptyOutputRecoveryUsed=false / finishReason=STOP`，四点全部变绿且无浏览器控制台错误。后端全量 403 项通过（5 个可选本机样本未配置时跳过），前端 44 项与生产构建通过；运行态评测为确定性 7/7、WY 真实样本 1/1。

### 8.13 P4-B 可恢复阅读工作区验收

顶层路由只缓存命名为 `LibraryView` 的文库页面，其他业务页面仍按普通路由卸载。打开 PDF 后切换到看板等页面时，PDF.js 文档、当前论文、页码、缩放、滚动位置、选区、助手输入和持久化 run 视图留在内存中；返回文库直接恢复，不重新加载 PDF。点击 PDF 工具栏“关闭”仍会销毁阅读器，因此重新打开从第 1 页、100% 和空白输入开始。工作区停用时会移除页面滚动锁、窗口级选区监听和文库快捷键，并暂停且结算阅读计时，避免在其他页面虚增阅读时长。

PDF 与助手之间增加 8px 可访问分割线，桌面请求默认约为 60/40，但左移上限由当前 PDF 的真实 100% 页面宽度动态决定：页面宽度加滚动条占用必须完整留在左侧，助手不能继续挤压 PDF。助手常规下限 320px；当容器可同时容纳完整页但空间紧张时可压缩至 240px，窗口窄到两者无法兼得时才降级保留横向滚动。分割线支持鼠标/指针拖动、方向键 2% 微调、Shift 5% 微调和双击/Home 恢复默认。只把 20%–65% 的助手宽度偏好写入 `localStorage`，不把论文内容、选区或任务结果写入浏览器存储。

### 8.14 P4-C 统一论文分析工作台验收

顶层导航将重复入口统一为“论文分析”；`/workbench` 复用已缓存的 `LibraryView + PdfViewer + PaperWorkbenchPanel`，而不是新建另一套 PDF/任务状态。右侧窗口的四个顶层任务为选区问答、全文分析、跨论文对比和论文改进空间；领域研究空白只作为跨论文对比完成后的第二阶段。URL 同步 `mode/paperId/paperIds`，旧 `/analysis`、`/gap` 地址保留查询参数后分别跳转到全文分析和单篇论文改进空间。写作助手仍为独立页面，阅读计划及旧 `AnalysisView/GapView` 已删除。

后端保留多篇 `RESEARCH_GAP` Workflow 并新增单篇 `PAPER_IMPROVEMENT` Workflow。单篇改进空间只接受一篇论文，调用 `IDENTIFY_PAPER_IMPROVEMENTS`，要求至少三项有论文内 evidence、影响、验证方式的研究切入点，并禁止推断为整个领域的空白。领域研究空白接受 3–8 篇，逐篇读取版本绑定的本地 evidence，调用 `IDENTIFY_RESEARCH_GAPS`，要求每篇至少被 claim 实际引用；请求还必须携带已完成的 `PAPER_COMPARISON` 源 run，后端校验论文集合完全相同。输出只能称为“候选空白”，必须给出跨论文证据边界、可检验问题和外部检索/实验验证步骤。

语义拆分后的前端 59 项、后端 415 项（5 项可选本机样本跳过）及 Vite 生产构建通过。测试覆盖单篇范围拒绝、多篇来源 run/论文集合约束、两套 Prompt 与质量门禁、旧路由兼容和对比结果二阶段入口。

自动验证覆盖分栏边界/存储、路由 KeepAlive 恢复和停用计时，共新增 6 项测试；前端全量 50 项与生产构建通过。真实论文 175 的 100% 页面宽 918px，在 1280px 工作区中为 PDF 分配 938px（内容区 923px）、助手 334px；尝试把分割线继续左拖 100px 后仍停在 938px，`scrollWidth === clientWidth === 923px`，页面没有横向裁切。该论文从第 3 页、125% 和自定义问题切到看板后，`pdf-viewer-open` 正确释放；返回文库仍保持第 3 页、125%、问题文本和 4 个 trace 状态点。显式关闭再打开恢复为第 1 页、100% 和空白输入，浏览器无 error 日志。

### 8.15 P4-D 手动选区批注与产品入口收敛验收

PDF 工具栏“AI 批注”和论文助手“批注建议”产品入口已移除，页面保留选区问答、全文分析、跨论文对比、论文改进空间四个顶层任务；领域研究空白位于跨论文对比结果内。历史 `ANNOTATION_SUGGESTION` run 和后端接口继续兼容读取，但旧路由统一归入选区问答，不再发起新的建议任务。助手分析结果不再显示“添加内容/添加选中内容”，只保留浏览器原生选择与复制。

PDF 工具栏把“批注”和“便签”拆为两个明确入口：“批注”必须有当前文本选区，弹窗显示关联原文，由用户填写内容；“便签”只进入页面任意位置放置模式。选区批注复用 `NOTE` 存储和 emoji 呈现，并持久化 `anchorQuads/anchorText/anchorKind=SELECTION/notePosition`；取消弹窗不丢失选区，保存成功才清除，之后可直接点击 emoji 编辑、删除或拖动，编辑标题继续显示“批注”而非底层类型名。运行恢复读取后端有界的最近 20 条记录并按当前产品模式筛选，连续选区问答不会挤掉最新全文分析。Note Controller 与 Annotation Controller 均返回统一 `Result` 包络，避免数据库已写入但前端拦截器误报失败。前端全量 57 项、后端全量 410 项（5 项可选本机样本跳过）和 Vite 生产构建通过；真实论文 175 的标题选区已完成保存、刷新恢复、编辑和删除，临时数据清理后仅保留原有批注 32，页面无 error 日志。

### 8.16 重设计阶段 3 标注语义收口

本节取代 8.15 中旧“批注/便签/独立笔记”的产品命名，但保留该节作为历史实施记录。当前唯一语义为：选中文字后创建 `NOTE`，产品名“笔记”；在页面内容点创建 `COMMENT`，产品名“批注”。两者都有固定锚点、独立可拖动位置和虚线连接，并使用明显不同的 N 形笔记图标与对话气泡图标。右侧列表只管理 `COMMENT`，点击跳转到页面锚点，下方只提供“完成”和“删除”；完成状态写入 `completed/completed_at` 并将卡片、连线和图标显示为绿色。写作工作台只读取 `NOTE`，因此选区笔记成为阅读与写作之间的唯一笔记来源。

高亮和下划线创建后立即成为当前标记；再次点击任一文字标记也会直接显示首尾范围拖柄和右上角删除 ×，不再存在全局“调整”工具。V23 按用户确认的数据策略先清空无价值的旧测试标注，再增加批注完成字段并删除 `note / paper_note_link`；后端同时校验类型、几何锚点和论文归属，避免构造无来源笔记或跨论文修改标注。迁移已在独立临时 MySQL schema 运行真实 SQL 后确认自动清理；前端 83 项、后端 436 项（5 项可选样本跳过）及生产构建通过。

### 8.17 重设计阶段 4 论文精读工作台

本节取代 8.15 及 P4-C 中“四个任务平铺在助手首屏”的前端布局，但暂不删除兼容路由、历史 run 或后端 Workflow。右侧助手最上方固定为“论文精读 / 缺陷分析 / 论文对比”三个横排选项卡；当前只实现论文精读，缺陷分析和论文对比显示后续阶段说明，避免把未按新记忆体系重做的旧任务伪装成已完成产品。

论文精读内部由“内容选取 / 公式框选”滑动选择器控制 PDF 的唯一鼠标工具。内容选取仍使用默认文字拖选，原文直接显示在内容框中；锚点解析完成后必须由用户点击“确认并固定”，对话输入才可用。公式框选复用可信服务端裁剪和识别链路：裁剪图、KaTeX 渲染结果和 LaTeX 编辑框同时显示，编辑 LaTeX 会实时更新渲染，确认后才生成可用于问答的公式锚点。切换或清除捕获内容会清空旧选区对话，防止上下文串联。

内容框下方为连续对话区：用户消息和助手回答分侧显示，回答继续携带可点击页码证据；底部输入框固定提供发送动作和快捷键提示，未确认内容时明确禁用。每轮仍调用独立、可审计的 `SELECTION_QA` run，并复用研究会话归档与有界最近对话上下文。本阶段不改变全文解析、结构化论文记忆或上下文检索策略，它们分别由后续阶段实现。

## 9. 分阶段实施

| 阶段 | 交付 | 验收 |
| --- | --- | --- |
| P0（已完成） | PDF.js 文字层缩放对齐、归一化批注、缩放、自动保存、批注编辑与几何范围调整 | 选择不被 SVG 遮挡；缩放后批注对齐；批注可编辑/删除 |
| P1-A（已完成） | 前端 viewport 版面索引、单双栏/视觉行/候选段落、同页同栏受控选取 | 真实双栏样本不串栏；页边空白、竖排边注和全宽标题不触发文本扩张 |
| P1-B1（已完成） | 后端版面契约、PDFBox 坐标提取、单双栏行级 reading order | 合成样例与 WY 真实双栏正文顺序稳定；旋转边注不混入正文 |
| P1-B2（已完成） | `PaperLayoutArtifact` 版本化持久化、段落合并与正文角色识别 | 同一 PDF/解析器命中缓存；页眉/页脚/参考文献可确定性排除 |
| P1-B3a（已完成） | `SelectionAnchor` 后端映射、区域降级与当前论文局部证据检索 | 真实选区可映射块；旧版本 409；evidence 只含允许角色 |
| P1-B3b（已完成） | 前端选区锚点接入、证据预览与页码/坐标跳转 | 真实 PDF 选区可显示映射状态并从 evidence 返回原文 |
| P2-A（已完成） | 工作台 run/step 持久化、规则路由、Evidence Gate 与一次 repair 上限 | 固定计划可审计；越权 Skill/未知引用被拒绝；终态可恢复读取 |
| P2-B（已完成） | 选区问答、全文分析、批注建议、多篇对比四条固定 Workflow | 每条 Workflow 只调用允许 Skill，并返回可回链 evidence |
| P2-C（已完成） | PDF 右侧论文助手、运行 trace 与证据跳转 | 用户可在同一页面发起固定 Workflow 并检查每步证据 |
| P3-A（已完成） | 低置信度外部解析适配器、公式/表格区域模式 | 复杂页显式回退或区域降级，不伪造精确文本 |
| P3-B1（已完成） | 可提交评测集、可选真实 PDF manifest、可重复评测器与聚合指标 API | 确定性 7/7、WY 真实 case 通过；指标不泄露论文内容 |
| P3-B2（已完成） | 产品指标面板与多论文对比选择/覆盖呈现 | 质量快照可切换窗口；对比前校验 2–8 篇并按论文展示实际引用覆盖 |
| P4-A（已完成） | 修复模型空结果与 token 截断；选区上下文收敛；四个横向状态点替换详细流程文字；页面只展示精确选区 | 真实选区问答一次完成；四点状态与 trace 一致；邻近证据不混入“当前选区” |
| P4-B（已完成） | PDF 与助手可拖动分栏、宽度记忆、路由返回后恢复工作区 | 分割线可拖动/复位；论文、页码、缩放、滚动位置和助手任务保持；显式关闭才清空 |
| P4-C（已完成并提交 `446ac36`，语义拆分待确认） | 统一单篇分析、论文改进空间、跨论文对比与领域研究空白的页面、路由和 Workflow 入口 | 单篇改进严格单论文；领域空白只能由完成的 3–8 篇对比继续且论文集合不变；旧入口兼容跳转 |
| P4-D（已完成，待联合确认） | 移除两套 AI 批注建议和助手结果二次添加入口；提供 PDF 精确选区手写批注 | 选区批注显示关联原文并自动保存为可拖动 emoji；自由便签独立；已有批注可编辑/删除 |
| 重设计 4（已完成） | 三个助手顶层选项卡；论文精读的内容/公式捕获、显式确认与连续对话 | 文字/公式未确认时禁止提问；LaTeX 编辑实时渲染；内容变化不串用旧对话 |
| P4-E（已完成实现，待确认） | 独立翻译服务，默认 DeepL；右侧选区翻译；连续、证据化的选区对话；中/英双语输出、懒翻译与缓存 | 选区翻译不调用问答 LLM；连续追问共享有界会话上下文但每轮独立留痕；中英切换不重新分析；LaTeX/引用/数值保持；密钥不进入源码、日志或 Git |
| P4-F（已完成实现，待确认） | 公式区域框选、多模态/公式 OCR、LaTeX 结果与 KaTeX 渲染 | 大型运算符和复杂公式可用区域识别；显示区域缩略图、渲染公式、LaTeX 源码与置信度；低置信度保留 REGION |

### 9.1 P4-E 翻译 Provider 约束

- 后端定义 `TranslationService` 与可替换的 `TranslationProvider`，默认 provider 为 `deepl`；论文问答模型不得作为默认翻译路径。
- DeepL 凭据只从本机环境变量或现有加密设置读取。使用 `RA_TRANSLATION_PROVIDER=deepl`、`DEEPL_AUTH_KEY=<local-secret>` 和可选 `DEEPL_API_BASE_URL=<account-endpoint>`；真实值不得写入 Markdown、源码、前端 bundle、测试快照或日志。当前本机真实值存入 Windows 用户环境变量，未写入数据库。
- 服务未配置、额度不足、限流或网络失败时返回明确可恢复错误，不静默改用 LLM 产生风格不一致的译文；将来如需备用翻译 API，必须由配置显式选择。
- 缓存键至少包含 `provider + sourceLanguage + targetLanguage + contentHash + glossaryVersion`。只在用户点击“翻译”或切换结果语言时调用，避免对隐藏邻近证据和未查看结果计费。
- 长文本按段落安全分块，公式、引用、数字和占位符先保护再还原；测试覆盖中英互译、LaTeX、IEEE 引用、缩写、超长文本、限流和密钥脱敏。

实现验收（2026-07-17）：基础翻译交付时后端全量 424 项（5 项可选真实样本跳过）、前端 62 项与生产构建通过；真实 DeepL Free 请求成功，二次请求命中缓存，LaTeX、引用和数值保持。浏览器从历史英文全文分析切换中文时只调用翻译接口，原 run、claims 的 evidenceIds 与证据跳转按钮保持不变。

交互补充验收（2026-07-17）：右侧当前选区提供明确翻译按钮和原位译文；选区问答以聊天形式连续追问，每轮独立持久化，最近 8 条/6000 字符上下文只用于理解追问，整篇召回同时使用问题、选区与历史，Evidence Gate 不放宽。后端 426 项、前端 64 项与生产构建通过；真实论文两轮问答共享同一 `conversationId`，第二轮携带 1128 字符上下文，两轮均一次成功、0 repair、`finishReason=STOP`。API 设置和快捷键弹窗经 portal 后计算层级 20020，高于 PDF 工作区 9999。

### 9.2 P4-F 公式区域识别约束

P4-F 不继续强迫 PDF 文字层表达二维公式，而是在工具栏提供独立的“公式”区域工具。用户在单页画出矩形后，前端只提交页码和左上角归一化坐标；后端必须从当前论文的原始 PDF 重新渲染并裁剪该区域，客户端截图不能作为可信识别输入。框选面积、页码、像素尺寸和输出长度均设置上限，避免一次区域操作退化为整页或整篇上传。

识别链按可信度分层：先复用当前 `PaperLayoutArtifact` 中与区域重叠的 `STRUCTURED` 公式 LaTeX；未命中时，才把服务端裁剪图交给当前配置的多模态模型或公式 OCR。模型输出仅是 `CANDIDATE`，即使自报高置信度也不能直接成为论文 evidence；界面必须同时展示区域缩略图、KaTeX 渲染、可编辑 LaTeX、来源和置信度。用户校正并确认后才持久化为 `CONFIRMED` 公式区域，并生成版本绑定的 `SelectionAnchor(FORMULA)`。模型不支持图片、输出无效或置信度不足时返回可回链的 `REGION`，不得伪造公式或以 500 错误中断阅读。

确认后的公式区域以独立表保存，绑定 `paperId + documentHash + parserVersion + page + regionKey`，不修改不可变的版面制品 JSON。局部证据检索优先返回该公式的稳定 evidence ID、坐标和 LaTeX，再检索整篇论文相关正文；未确认候选不能发起证据化问答。KaTeX 使用禁用信任命令和容错渲染，原始 LaTeX 始终可见、可复制、可再次校正。

实施与验收分四步：F1 完成区域框选、归一化坐标和服务端安全裁剪；F2 完成版面优先、多模态回退、候选持久化和公式 evidence；F3 完成 KaTeX 预览、编辑确认及确认后选区对话；F4 运行前后端全量测试，并在 WY 真实 PDF 上验证大型运算符、失败降级、缩放后坐标和证据回链。

实现验收（2026-07-17）：F1–F4 均已完成。V18 以独立表保存公式区域，服务端使用 PDFBox 200 DPI 重渲染裁剪，识别链实现 `STRUCTURED layout → multimodal CANDIDATE → REGION` 安全降级；确认后生成稳定 `frm_*` evidence 并接入既有连续选区对话。WY 真实论文第 6 页式（21）的根号与 `Q^{-1}` 已完成框选、图像识别、编号人工校正、KaTeX 预览、确认入库、证据化问答和原页矩形回链；125% 缩放时公式框坐标及尺寸严格按 1.25 倍换算且保持对齐。后端全量 439 项、前端 70 项及生产构建通过，真实 MySQL 已迁移至 V18，后端重启后健康状态为 `UP`。

## 10. 可提炼为简历亮点的技术叙事

项目不应只描述为“接入大模型的论文管理工具”，而应强调可验证的 Agent 系统设计：

> 设计并实现面向科研论文的 evidence-grounded Agent 工作台：将 PDF 版面解析结果版本化为可回链的 layout artifact，使用受 Schema 约束的 Skill 编排与有界 Plan–Execute–Ground loop 完成选区问答、全文分析和跨论文对比；通过证据门禁、一次 repair 上限、异步可恢复任务及真实 PDF 评测集，控制双栏串读、无依据引用和工具失控问题。

这段能力的前提是指标、测试样本、任务 trace 和失败降级都真实落地；不要为了“Agent”标签堆叠不可验证的多智能体概念。

## 11. 最终验收

P0 至 P3-B2 的代码、自动化测试、真实 WY 样本、在线接口、浏览器关键路径和四个本地启动脚本已完成统一审计。最终结论、阶段证据、已知边界及复现方法统一记录在 [PDF 工作台最终验收](pdf-workbench-acceptance.md)，本规格状态已收口。
