# 三类论文 Agent Skill 测评集建设计划

## 1. 评测对象与唯一指标

测评对象是论文 Agent 的三个可按需激活 Skill：

| Skill | 应激活的条件 | 不应激活的条件 |
|---|---|---|
| `paper-profile` | 问题需要全文视角，且当前上下文尚未加载有效论文画像 | 精确事实、已有画像可复用、普通知识问题 |
| `paper-evidence` | 回答依赖论文事实、公式、图表、算法、指标、页码或需要解析操作目标 | 普通知识、闲聊、仅复述当前上下文中已读且仍可信的证据 |
| `paper-action` | 用户明确要求跳转、高亮、下划线、笔记或评论，包括目标含糊而需要澄清的操作意图 | 只阅读、解释或用户自行点击引用 |

每个 Skill 独立视为一个二分类任务。正式报告只包含 Precision、Recall 和 F1：

```text
Precision = TP / (TP + FP)
Recall    = TP / (TP + FN)
F1        = 2 × Precision × Recall / (Precision + Recall)
```

一个案例可同时标注多个 Skill。某 Skill 在同一案例中激活多次，只按“已激活”计一次；重复激活本身不改变 TP/FP/FN。

## 2. 单次运行口径

每个案例只运行一次，不对三次结果取平均。运行时冻结以下条件：

- 模型名称、API 服务和模型参数；
- 三个 `SKILL.md` 的版本；
- 系统提示词和 Agent 代码版本；
- PDF 文件哈希、解析器版本和论文理解产物版本；
- 案例给定的会话上下文、已加载画像、已读证据和可信选区。

模型即使在低随机性设置下仍可能有轻微波动，但多次运行属于稳定性研究，不纳入本次三项指标。发现偶发失败时，可以单独复跑该案例帮助诊断，复跑结果不得替换冻结测试集的第一次结果。

## 3. 数据规模

首版使用 30 篇论文，每篇 10 个案例，共 300 个案例。这个规模足以用于项目迭代和简历中的第一版量化结果，前提是案例按规则设计，而不是把同一种问题简单改写十次。

这 30 篇全部作为测试语料，300 条案例全部参与最终三个 Skill 的指标统计，不再拆成开发集、验证集和冻结测试集。第一轮先从每篇抽一条形成 30 条先导案例；链路确认并修复明显问题后，再补齐其余 270 条，最后在固定版本上完整运行 300 条。

这套口径适合衡量当前项目在自建评测集上的工程表现。后续如果需要发表论文或严格证明对未见论文的泛化能力，再另建一批从未参与规则和提示词调整的盲测论文，不影响当前 30 篇测试计划。

## 4. 每篇论文的 10 类案例

每篇论文使用相同的能力框架，但具体问题必须根据原文人工设计：

| 每篇编号 | 案例类型 | 常见金标 Skill |
|---:|---|---|
| 1 | 核心贡献、整体方法或全文结论 | profile + evidence |
| 2 | 某项实验结果、比较条件与适用范围 | evidence |
| 3 | 精确事实或方法机制 | evidence |
| 4 | 实验数值、基线比较或适用条件 | evidence |
| 5 | 公式、图、表、算法或页面定位 | evidence |
| 6 | 否定性或证据不足问题 | evidence |
| 7 | 只改写用户明确给出的文字，不核对论文 | 无 Skill |
| 8 | 已有可信选区的明确页面操作 | action |
| 9 | 目标明确但尚无可信来源 ID 的页面操作 | evidence + action |
| 10 | 全文问题并要求对依据执行操作，或无关普通知识负例 | profile + evidence + action，或无 Skill |

不同论文轮换第 10 类，使完整 300 条中每个 Skill 都有足够正例和负例。当前冻结金标分布为：

| Skill | 正例目标 | 负例目标 |
|---|---:|---:|
| `paper-profile` | 40 | 260 |
| `paper-evidence` | 220 | 80 |
| `paper-action` | 80 | 220 |

这能避免只用少量正例计算出看似很高、实际波动很大的 Precision 或 Recall。

## 5. 30 条先导集

第一轮每篇论文只抽一条，共 30 条。它的目的不是产出简历指标，而是验证整条测评链路是否可用。

建议覆盖：

| Skill 组合 | 数量 |
|---|---:|
| profile + evidence | 6 |
| evidence | 6 |
| action | 5 |
| evidence + action | 5 |
| profile + evidence + action | 4 |
| 无 Skill | 4 |

先导集通过条件：30 篇都能导入并完成所需预处理；30 个案例都能运行；每个 Agent trace 都能读取实际的 `activate_skill` 记录；评分器能按金标生成三个 Skill 的 TP、FP、FN、Precision、Recall 和 F1；失败案例能定位到导入、理解、模型调用、工具调用或评分阶段。

## 6. Skill 金标判定原则

金标依据“在给定上下文状态下完成用户请求所需的最小 Skill 集合”，不能依据问题中是否出现“论文”“高亮”等关键词，也不能在看到模型实际行为后反向修改。

### `paper-profile`

同时满足以下条件时标为正例：

1. 问题需要整篇论文的研究问题、方法、贡献、实验、结论或局限的整体认识；
2. 案例上下文没有可复用的当前版本画像。

如果问题只问公式（21）、图 3、某个数值或一段局部机制，`paper-profile` 为负例。画像已在案例上下文中加载时，也不应重复激活。

### `paper-evidence`

满足任一条件时标为正例：

- 最终回答将陈述当前论文中的事实性内容；
- 需要查公式、图、表、算法、指标、实验结果或页码；
- 用户询问论文是否包含、讨论或证明某内容；
- 页面操作目标明确，但当前上下文没有可信 `sourceObjectId`，必须先检索定位。

普通知识问题为负例。用户明确提供待改写文字并说明不要求核对论文时，也标为负例。测评脚本不得伪造实际上没有注入会话的“已读证据”。

### `paper-action`

用户明确表达跳转、高亮、下划线、添加笔记或评论的意图时标为正例。目标含糊时仍是 `paper-action` 正例，因为该 Skill 负责要求澄清并禁止编造目标；是否真的执行页面写操作不属于本轮 Precision/Recall/F1 的评分内容。

只解释论文、返回引用或用户自行点击引用时为负例。

## 7. 案例与金标格式

每个案例保存为一条 JSONL：

```json
{
  "id": "p001-case-01",
  "paperKey": "sha256:...",
  "context": {
    "profileAvailable": true,
    "profileAlreadyLoaded": false,
    "trustedSourceObjectIds": []
  },
  "question": "这篇论文的核心贡献是什么？",
  "goldSkills": ["paper-profile", "paper-evidence"],
  "requiredSkills": ["paper-profile", "paper-evidence"],
  "allowedSkills": ["paper-profile", "paper-evidence"],
  "rationale": {
    "paper-profile": "需要整篇论文的贡献视角，且上下文尚无画像",
    "paper-evidence": "最终答案会陈述论文贡献，必须读取可引用原文",
    "paper-action": "用户没有页面操作意图"
  }
}
```

`rationale` 必须逐个解释三个 Skill 的正负标签。它用于人工复核分歧，不参与自动评分。

`requiredSkills` 表示完成请求不可缺少的 Skill；`allowedSkills` 表示在当前问题下调用也合理的 Skill。
例如，比较论文实验结果时 `paper-evidence` 必需，而 `paper-profile` 可以用于先建立整体定位，因此允许但不强制。
精确公式、局部事实和真假核验仍只允许 `paper-evidence`，避免把画像调用全部免除误触发处罚。

## 8. 评分过程

1. 冻结案例、代码、模型和论文版本。
2. 为每个案例建立规定的干净会话状态并运行一次。
3. 从持久化 Agent trace 中读取成功请求的 `activate_skill` 参数，形成预测 Skill 集合。
4. 对三个 Skill 分别累计 TP、FP、FN：调用必需 Skill 记 TP，漏掉必需 Skill 记 FN，调用不在
   `allowedSkills` 中的 Skill 记 FP；调用“允许但非必需”的 Skill 不记 TP、FP 或 FN。
5. 输出三行结果，每行只报告 Precision、Recall、F1，同时保存案例级错误清单供修复。

### 运行失败与重试口径

Skill 路由错误、回答内容错误和运行失败分开记录。评分器先读取 `run.failed` 与
`run.diagnostics`：

- 测试驱动在创建运行后回读持久化的用户问题，并与冻结案例逐字比较；出现 `????`、
  替换字符或其它不一致时标为 `REQUEST_HARNESS`，不进入模型或 Skill 评分。

- `failureCategory=PROVIDER_TRANSIENT` 且 `retryable=true`，并且失败的模型调用
  `responseKind` 不是 `NOT_SENT` 时，才允许对同一案例进行一次有界重试；重试要保留
  `retryOf`，不得覆盖第一次结果。
- `PROJECT_LIMIT`（上下文超限、模型调用上限、工具轮次上限等）、
  `MODEL_PROTOCOL`（结构化答案或证据校验失败）以及 `PROJECT_INTERNAL` 不重试，
  归入“不可评分运行失败”。最后一次请求为 `NOT_SENT` 时，服务商没有收到请求，
  也不能称为模型繁忙。
- `COMPLETED` 和 `WAITING_USER` 是业务终态。`WAITING_CLIENT` 是可恢复的中间状态，只表示
  后端已经生成操作票据、正在等待浏览器执行并回传回执；此时可以根据已持久化的激活事件评分
  Skill 路由，但不能记录为页面操作完成。

不可评分运行失败不计入 TP、FP、FN，但必须单独报告数量和原因，避免把项目稳定性问题
混入 Precision、Recall 和 F1。重试成功时，使用成功运行的 Skill 路由结果参与指标，
同时保留原始失败记录用于稳定性统计。

### Agent 链路收敛验收

先导测试中出现的上下文和调用次数超限按原失败案例回归。调用上限仍是异常保护，但需要覆盖
合法的画像、证据、一次补检索、操作和最终回答链路：最多 12 次物理模型请求（10 次研究决策、
1 次 `finish_research` 决策和 1 次无工具最终回答）、12 次工具调用，
单次 Agent 模型请求限时 80 秒并允许一次服务商瞬时失败重试。Agent 总墙钟限时为 900 秒，
前端最长观察 960 秒，避免前端先于后端把仍在运行的任务判为超时。链路采用以下确定性收敛规则：

- 证据结果在持久化审计中保留完整内容；发给模型的临时视图只保留一份相同正文，避免
  `content` 与 `fullText` 重复占用上下文。
- 组装请求时把工具定义计入输入预算。如果仍超过软限制，只从发送给模型的临时视图中删除
  最旧的完整历史问答；当前问题、论文身份、历史摘要和本轮工具调用/结果必须保留。数据库中的
  完整会话不被修改，因此重新进入长会话也不会直接报上下文过长。
- 证据工具始终保持可用。完全相同的读取请求采用幂等返回，不重复扫描；查询目标、Need、锚点或
  游标发生有效变化时继续执行，由模型根据已有证据是否充分决定何时结束研究。
  模型完成全部所需能力后调用无参数的 `finish_research`，随后服务端以 `tool_choice=NONE` 发起一次
  不携带任何工具定义的最终请求，模型直接输出 Markdown；
  不会为了答案 JSON 格式再次调用模型。
- Need ID 用于关联检索进度，不冻结后续研究范围。模型发现新的独立事实缺口时可以新增 Need；
  无效锚点仍按输入错误返回，完全相同的请求返回已有状态而不再次读取。
- 每个已读来源由服务端分配 `[S1]`、`[S2]` 等短标签。最终 Markdown 中的标签由服务端映射为
  内部来源 ID 并生成引用；模型漏写标签时，服务端把本轮已经读取且可定位的来源绑定到整段回答，
  保证回答可交付，同时保留原始 trace 供后续质量分析。
- 900 秒墙钟超时触发后，服务端除持久化失败状态外还会取消正在执行的 Future；即使底层 HTTP
  客户端未立即响应中断，状态机也会拒绝迟到结果覆盖超时终态。

原失败案例的通过条件为：运行到达业务终态；没有 `CONTEXT_BUDGET_EXCEEDED`、
`MODEL_CALL_LIMIT_EXCEEDED` 或 `TOOL_CALL_LIMIT_EXCEEDED`；没有重复执行完全相同的读取调用；
模型估算输入低于 16,000 token，并记录最大值用于观察余量。只有同时满足这些条件，案例才恢复
进入 Skill 指标评分。

结果表格式：

| Skill | Precision | Recall | F1 |
|---|---:|---:|---:|
| `paper-profile` | 待测 | 待测 | 待测 |
| `paper-evidence` | 待测 | 待测 | 待测 |
| `paper-action` | 待测 | 待测 | 待测 |

简历表述使用固定项目版本完整运行 300 条案例所得的真实结果，并写明语料和案例规模，例如：

```text
构建覆盖 30 篇论文、300 个多标签案例的 Agent Skill 路由评测集；
在完整测试集上，paper-profile、paper-evidence、paper-action
分别达到 Precision/Recall/F1：xx/xx/xx、xx/xx/xx、xx/xx/xx。
```

## 9. 当前执行顺序

1. 修复论文导入的字段长度错误、旧论文误判和复杂首页元数据提取。
2. 导入并理解 30 篇论文，冻结 PDF 哈希和论文版本。
3. 为每篇论文设计一条先导案例，完成 30 条链路测试。
4. 已生成 `backend/src/test/resources/eval/agent-skill-300.jsonl`，绑定当前入库的 30 个 `paperId`，每篇 10 条，共 300 条；每条包含上下文状态、问题、`requiredSkills`、`allowedSkills`、三个 Skill 的判定理由和证据提示。`goldSkills` 暂时保留为 `requiredSkills` 的兼容别名。
5. 固定项目、模型和数据版本，运行该文件中的 300 条测试案例并记录三个 Skill 的 Precision、Recall 和 F1；`AgentSkillEvalDatasetTest` 会先校验案例数量、唯一性和每篇论文的 10 条约束。

## 10. 20 条抽测记录（2026-09-15）

在进入完整 300 条测试前，旧版 Sample20 完成过一轮运行性抽测。该结果用于定位链路问题；由于
本次已合理化问题和金标，并修正操作测试方式，下面数据只作为历史基线，不作为当前版本指标。

| 运行状态 | 数量 | 说明 |
|---|---:|---|
| `COMPLETED` | 17 | 已生成最终回答 |
| `WAITING_CLIENT` | 3 | 已产生操作票据，测试脚本过早停止；后来重新进入页面后由浏览器执行完成 |
| `FAILED` | 0 | 无 |

按案例金标的多标签集合计算，Skill 指标如下。指标保留所有 20 条可评分案例，不剔除路由不匹配的结果。

| Skill | TP | FP | FN | Precision | Recall | F1 |
|---|---:|---:|---:|---:|---:|---:|
| `paper-profile` | 7 | 3 | 1 | 70.00% | 87.50% | 77.78% |
| `paper-evidence` | 13 | 5 | 0 | 72.22% | 100.00% | 83.87% |
| `paper-action` | 6 | 0 | 2 | 100.00% | 75.00% | 85.71% |

完整 Skill 路由集合与金标完全一致的案例为 9/20。主要偏差是部分证据问题额外触发 `paper-profile`，以及 `paper-action` 案例额外触发 `paper-evidence`；`paper-03` 漏触发 `paper-profile`，`paper-18` 和 `paper-24` 漏触发 `paper-action`。本轮只验证执行稳定性和路由指标，回答事实正确性仍需按金标逐条人工复核。

当前运行器为 `scripts/eval/run-agent-skill-evaluation.ps1`。默认会在 `WAITING_CLIENT` 后继续等待
打开的论文页面执行操作并提交回执；只有后端校验回执并进入 `COMPLETED`，才记录
`actionExecutionStatus=RECEIPT_RECORDED`。仅在诊断路由、不验收页面操作时使用
`-WaitForClientAction:$false`，此时记录 `PENDING_CLIENT_RECEIPT`，不会声称操作完成。使用
Sample20 不再使用无法由 API 脚本注入真实选区的第 8 类案例；真实选区案例应从浏览器发起。

旧版原始 JSONL 记录保存在本机忽略目录：`backend/target/eval/agent-skill-sample20-final.jsonl`。
