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

一个案例可同时标注多个 Skill。

## 2. 数据规模

30 篇论文，每篇 10 个案例，共 300 个案例。每个案例只运行一次；只有确认测试驱动或案例上下文有问题时，才允许修正后用相同 `caseId` 覆盖原记录。

## 3. 每篇论文的 10 类案例

每篇论文使用相同的能力框架，但具体问题必须根据原文人工设计：

| 每篇编号 | 案例类型 | 常见金标 Skill |
|---:|---|---|
| 1 | 核心贡献、整体方法或全文结论 | profile + evidence |
| 2 | 某项实验结果、比较条件与适用范围 | evidence |
| 3 | 明确的核心机制、组件或方法贡献 | evidence；画像可辅助导航但不是必需 |
| 4 | 实验数值、基线比较或适用条件 | evidence |
| 5 | 公式、图、表、算法或页面定位 | evidence（画像可辅助） |
| 6 | 否定性或证据不足问题 | evidence（画像可辅助） |
| 7 | 只改写用户明确给出的文字，不核对论文 | 无 Skill |
| 8 | 已有可信选区的明确页面操作 | action |
| 9 | 目标明确但尚无可信来源 ID 的页面操作 | evidence + action |
| 10 | 全文问题并要求对依据执行操作、明确操作但目标含糊，或无关普通知识负例 | 按上下文为 profile + evidence + action、evidence + action、action 或无 Skill |

不同论文轮换第 10 类，使完整 300 条中每个 Skill 都有足够正例和负例。初始设计目标是画像 40 条正例；人工复核后，`paper-09-case-10` 的画像调整为可选调用，因此当前冻结金标分布为：

| Skill | 正例目标 | 负例目标 |
|---|---:|---:|
| `paper-profile` | 39 | 261 |
| `paper-evidence` | 220 | 80 |
| `paper-action` | 80 | 220 |

## 4. Skill 金标判定原则

金标依据“在给定上下文状态下完成用户请求所需的最小 Skill 集合”，不能依据问题中是否出现“论文”“高亮”等关键词，也不能在看到模型实际行为后反向修改。

### `paper-profile`

同时满足以下条件时标为正例：

1. 问题需要整篇论文的研究问题、方法、贡献、实验、结论或局限的整体认识；
2. 案例上下文没有可复用的当前版本画像。

如果问题只问公式（21）、图 3、某个数值或一段局部机制，`paper-profile` 为负例。画像已在案例上下文中加载时，也不应重复激活。

### `paper-evidence`

满足任一条件时标为正例；如果当前轮已经注入可信选区或仍然有效的已读证据，且回答只依赖这些已有内容，则不要求再次激活 `paper-evidence`：

- 最终回答将陈述当前论文中的事实性内容；
- 需要查公式、图、表、算法、指标、实验结果或页码；
- 用户询问论文是否包含、讨论或证明某内容；
- 页面操作目标明确，但当前上下文没有可信 `sourceObjectId`，必须先检索定位。

### `paper-action`

用户明确表达跳转、高亮、下划线、添加笔记或评论的意图时标为正例。目标含糊时仍是 `paper-action` 正例，因为该 Skill 负责要求澄清并禁止编造目标；是否真的执行页面写操作不属于本轮 Precision/Recall/F1 的评分内容。

只解释论文、返回引用或用户自行点击引用时为负例。

## 5. 案例与金标格式

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
精确公式、局部事实和明确的方法机制仍要求 `paper-evidence`；只有需要组织整篇论文的研究问题、方法、结果和局限时才把 `paper-profile` 列为必需，画像不能替代原文证据。

`paper-09-case-10` 是当前冻结集中的明确例外：它要求论文证据和页面操作，画像只作为辅助导航，因此其 `requiredSkills` 为 `paper-evidence`、`paper-action`，`allowedSkills` 仍包含三个 Skill。

## 6. 评分过程

1. 冻结案例、代码、模型和论文版本。
2. 为每个案例建立规定的干净会话状态并运行一次。
3. 从持久化 Agent trace 中读取成功请求的 `activate_skill` 参数，形成预测 Skill 集合。
4. 对三个 Skill 分别累计 TP、FP、FN：调用必需 Skill 记 TP，漏掉必需 Skill 记 FN，调用不在
   `allowedSkills` 中的 Skill 记 FP；调用“允许但非必需”的 Skill 不记 TP、FP 或 FN。
5. 输出三行结果，每行只报告 Precision、Recall、F1，同时保存案例级错误清单供修复。

## 7. 运行有效性与操作案例

- `COMPLETED`、`WAITING_USER` 和 `WAITING_CLIENT` 的请求，只要 `requestValid=true`，即可根据持久化的 `activate_skill` 事件参与 Skill 路由评分。
- `WAITING_CLIENT` 只表示后端已经生成操作票并等待浏览器回执；它可以用于判断 `paper-action` 是否被激活，但不代表页面操作已经完成。
- 运行失败、请求内容被测试驱动破坏、上下文超限、模型调用上限或工具协议失败，不计入 TP、FP、FN，必须单独记录原因。
- 只有服务商确认收到请求且明确属于瞬时错误时，才允许对同一案例做一次有界重试；测试驱动错误或选区未注入时，修正后覆盖原 `caseId`，不得把旧记录和修正记录同时计入。
- 当前完整结果保存在 `backend/target/eval/agent-skill-evaluation-final.jsonl`，汇总指标保存在 `docs/agent-skill-metrics.md`。
