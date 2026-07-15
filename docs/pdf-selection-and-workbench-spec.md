# PDF 精确选取与论文工作台规格

状态：P0、P1-A、P1-B1 已完成；P1-B2 版本化语义版面制品已实现并待用户验收（2026-07-16）。下一小步是 P1-B3 `SelectionAnchor` 与当前论文局部证据检索；工作台 Agent 尚未开始。

## 1. 产品目标：做“有证据的论文 Agent”，不是再做一个 PDF 编辑器

论文工作台要解决的不是把 PDF 放在聊天框旁边，而是让每个 AI 结论都能回答三个问题：它基于哪篇论文、哪一页、哪一段，以及这段内容的解析是否可信。

首要目标：

- 用户可在当前论文中选中正文或区域，直接提问、生成批注、做全文分析，必要时加入多篇对比；
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
| 选中正文 | 解释、翻译、提问、建议批注 | 选区 + 相邻段落 |
| 框选区域 | 解释公式、图或表 | 区域 + 相交块 |
| 未选内容 | 总结、方法拆解、局限、全文问答 | 当前论文正文 |
| 已选多篇论文 | 对比方法、假设、指标、结论 | 指定论文集合 |

“论文分析”页面继续承载完整报告和历史结果；“研究空白/对比”页面继续承载跨论文任务。PDF 工作台只负责携带当前上下文进入这些能力，避免重复建设两套页面。

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

### 7.4 生成批注建议

`resolveSelectionContext → synthesizeEvidenceAnswer/explain → proposeAnchoredAnnotation → user confirm → deterministic create API`

模型只提供建议内容，用户决定是否落库。

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

## 9. 分阶段实施

| 阶段 | 交付 | 验收 |
| --- | --- | --- |
| P0（已完成） | PDF.js 文字层缩放对齐、归一化批注、缩放、自动保存、批注编辑与几何范围调整 | 选择不被 SVG 遮挡；缩放后批注对齐；批注可编辑/删除 |
| P1-A（已完成） | 前端 viewport 版面索引、单双栏/视觉行/候选段落、同页同栏受控选取 | 真实双栏样本不串栏；页边空白、竖排边注和全宽标题不触发文本扩张 |
| P1-B1（已完成） | 后端版面契约、PDFBox 坐标提取、单双栏行级 reading order | 合成样例与 WY 真实双栏正文顺序稳定；旋转边注不混入正文 |
| P1-B2（待验收） | `PaperLayoutArtifact` 版本化持久化、段落合并与正文角色识别 | 同一 PDF/解析器命中缓存；页眉/页脚/参考文献可确定性排除 |
| P1-B3（下一步） | `SelectionAnchor` 解析与当前论文局部证据检索 | 选区可映射块并回链页码/坐标；evidence 只含允许角色 |
| P2 | 右侧论文助手、四条固定 Workflow、Evidence Gate、run trace | 每个回答均可跳回页码/块；不合格回答只 repair 一次 |
| P3 | 多论文对比、外部解析适配器、评测集和指标面板 | 复杂页安全降级；比较结果保留每篇证据归属 |

## 10. 可提炼为简历亮点的技术叙事

项目不应只描述为“接入大模型的论文管理工具”，而应强调可验证的 Agent 系统设计：

> 设计并实现面向科研论文的 evidence-grounded Agent 工作台：将 PDF 版面解析结果版本化为可回链的 layout artifact，使用受 Schema 约束的 Skill 编排与有界 Plan–Execute–Ground loop 完成选区问答、全文分析和跨论文对比；通过证据门禁、一次 repair 上限、异步可恢复任务及真实 PDF 评测集，控制双栏串读、无依据引用和工具失控问题。

这段能力的前提是指标、测试样本、任务 trace 和失败降级都真实落地；不要为了“Agent”标签堆叠不可验证的多智能体概念。
