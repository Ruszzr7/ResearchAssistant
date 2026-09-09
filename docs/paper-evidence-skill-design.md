# 论文证据 Skill 重构方案（v3）

## 1. 目标与边界

`paper-evidence` 为当前论文问答提供可引用的原文来源和必要的局部视觉内容。论文进入问答前已经由版面解析、整篇理解和错误恢复链路生成版本化 artifact、画像 claim、source object 与 locator；本 Skill 不负责重新解析 PDF，也不负责替 Agent 生成最终答案。

本轮目标：

1. 让 Agent 从最终答案所需事实中生成合格、稳定且可补检索的 Evidence Need。
2. 让工具只返回确定性检索结果和逐 Need 的客观来源状态。
3. 让 Agent 只补检索明确缺口，并在重复或无新来源时停止。
4. 保证最终引用只绑定当前 Run 实际读取的来源。

本轮不引入向量模型、embedding、向量数据库、重排模型、查询改写模型或第二证据判断模型；不修改已经通过四论文基线的词法检索排序算法；不新增 Need 状态数据库表。

## 2. 职责边界

```text
Skill：说明如何拆分 Need、如何补检索和何时停止
Agent：生成 Need、阅读原文、判断语义充分性、提交答案
Java：校验参数、执行确定性检索、去重、记录逐 Need 的客观来源状态
```

完整流程：

```text
用户问题
  -> Agent 列出最终答案需要成立的事实
  -> 拆成 1～4 个 Need 并一次提交
  -> Java 校验输入
       |-- invalid_request：Agent 修正参数
       `-- 合法：执行确定性检索
  -> 返回来源、targetCoverage 和逐 Need progress
  -> Agent 阅读原文并判断
       |-- 充分：submit_answer
       |-- 具体缺口：只补检索未解决 Need
       `-- 重复或无新来源：停止并说明限制
```

## 3. Evidence Need 契约

```json
{
  "id": "worst-user-result",
  "objective": "确认论文是否报告了最差用户的公平性改善结果",
  "query": "worst user fairness performance baseline",
  "keywords": ["worst user", "fairness", "baseline"],
  "targets": ["poorest channel conditions", "fairness", "91.48%"],
  "sectionHint": "Simulation Results",
  "pageHints": [4],
  "contentTypes": ["TEXT", "TABLE", "FIGURE"],
  "includeVisual": false
}
```

- `id`：必填、非空、批次内唯一；同一事实补检索时保持不变。
- `objective`：必填；用中文、中立地描述要确认的一个事实；补检索时原样保留。
- `query`：可选；使用论文原文术语、变量、数值、指标或公式编号。
- `keywords`：可选；只用于首次无结果时的受限 fallback。
- `targets`：可选；必须是可在论文原文中做词面核对的对象，不填写中文语义结论。
- `sourceObjectIds/profileClaimRefs`：可选的直接来源锚点。
- `sectionHint/pageHints/contentTypes`：可选过滤条件，不能单独构成 Need。
- `includeVisual`：只在文本不足以理解二维布局时使用。
- `refinementReason`：首次调用省略；同一 Need 改变检索条件时必填。

可选字符串和数组没有内容时直接省略，不传空字符串、空数组或 `null`。

每个 Need 至少提供非空 `query`、`sourceObjectIds` 或 `profileClaimRefs` 之一。`pageRanges` 是独立的直接读页模式，可以不构造 Need。

模型可见 JSON Schema 提供 LangChain4j 支持的基础字段、类型、长度和数量约束；“至少一种锚点”等跨字段条件由 Java 运行时强制执行。未知 source ID 或 claimRef 不使整个批次失败，而是在对应 Need 中返回诊断。

## 4. 输入错误语义

模型可以修正的错误返回：

```json
{
  "status": "invalid_request",
  "sources": [],
  "evidenceNeeds": [],
  "issues": [{
    "needId": "result",
    "field": "query",
    "code": "MISSING_RETRIEVAL_ANCHOR",
    "message": "该证据需求至少需要 query、sourceObjectIds 或 profileClaimRefs 之一"
  }]
}
```

参数错误不能包装成 `unavailable`，也不能解释为论文没有证据。只有来源目录、数据库或其他执行故障才返回 `unavailable`。

同一 Run 内再次使用已有 Need ID 时，运行时还检查：

- `objective` 是否保持不变；
- 检索指纹发生变化时是否提供 `refinementReason`。

## 5. 确定性检索流程

一次工具调用按以下顺序处理当前所有 Need：

1. 直接读取当前 catalog 中存在的 `sourceObjectIds`；
2. 读取 `profileClaimRefs` 在当前版本画像中绑定的来源；
3. 使用 query、章节、页码和内容类型执行现有词法检索；
4. 单个 Need 首次完全无结果时执行一次 keywords/section fallback；
5. 对公式族和结构相邻来源做确定性扩展；
6. 每个 Need 先保留候选，再做全局排序、去重和响应裁剪；
7. 返回不超过来源数量、字符数和 16 KiB 负载边界的结果。

画像引用必须在空 query 时仍可直接读取。工具不进行生成式查询改写，也不判断来源是否语义上足以支持答案。

## 6. 返回结果与进展协议

顶层保留 `status`、`sources`、`evidenceNeeds` 和普通统计，不再向模型返回顶层 `coverage`、`noProgress`、`stopRecommended` 或 `stopReason`。

每个 Need 返回：

```json
{
  "needId": "worst-user-result",
  "retrievalStatus": "found",
  "sourceObjectIds": ["source-191-42"],
  "targetCoverage": {
    "matchedTargets": ["fairness", "91.48%"],
    "missingTargets": ["poorest channel conditions"]
  },
  "progress": {
    "outcome": "new_sources",
    "attempt": 1,
    "refinementCount": 0,
    "newSourceObjectIds": ["source-191-42"],
    "recommendedAction": "judge",
    "reason": "返回了该 Need 尚未读取的候选来源；请阅读原文并判断语义充分性。"
  }
}
```

`retrievalStatus` 只说明是否返回候选来源，`targetCoverage` 只说明词面匹配；二者都不是语义结论。

逐 Need 的 `progress` 状态：

| outcome | recommendedAction | 含义 |
|---|---|---|
| `new_sources` | `judge` | 有该 Need 尚未读取的候选来源 |
| `no_match` | `refine_once` | 首次无候选来源，可针对明确缺口改写一次 |
| `no_match` | `stop` | 补检索仍无候选来源 |
| `same_sources` | `stop` | 补检索只返回该 Need 已读来源 |
| `duplicate_request` | `stop` | 请求与上一请求在检索意义上相同 |
| `need_stopped` | `answer` | 请求中的 Need 已进入停止态，未再次执行底层检索 |

来源是否支持 `objective` 仍由当前 Agent 阅读原文后判断，不调用第二个模型。

## 7. 请求指纹与状态范围

请求指纹只包含影响检索的字段：query、keywords、targets、章节、页码、内容类型、source IDs、claim refs 和 includeVisual。字符串统一空白和大小写；无序数组去重并排序；JSON 属性顺序不影响指纹。`objective` 单独检查身份一致性，`refinementReason` 不参与检索指纹。

`EvidenceReadState` 只在当前 Agent Run 内保存：

```text
objectiveByNeed
plannedNeedIds
lastFingerprintByNeed
seenSourceObjectIdsByNeed
requestCount
refinementCount
```

新 Run 重新规划 Need，不继承旧 Run 的停止状态。未来只有在支持服务重启后恢复同一 Run 时，才考虑从已持久化工具调用重建客观状态；不保存 Agent 的语义判断。

## 8. Agent 补检索与停止规则

首次有效检索会冻结本 Run 的 `plannedNeedIds`。Agent 收到候选来源后必须先阅读。只有明确指出缺口、保持原 `id/objective`、填写 `refinementReason` 并改变有效检索条件时，才能补检索该 Need。已解决 Need 不得重复提交；首次检索后新增 ID 会得到 `NEW_NEED_NOT_ALLOWED` 和 `need_stopped / answer`，且不执行底层检索。

相同请求、一次有效改写仍无来源、或改写后只返回旧来源时停止该方向。停止后回答有证据的部分，并如实说明剩余限制；`not_found` 不能表述为论文证明某对象不存在。

不设置整个 Agent 的固定工具轮数。真实 Agent 回归已经发现收到 `stop` 后继续提交同一组 Need、改用新 ID 重述同一目标，以及连续提交不合法补检索的轨迹，因此加入三项最小运行时保护：首次有效检索后冻结 Need ID 集合；当一次请求中的 Need 均已进入停止态时直接返回 `need_stopped / answer`；同一 Need 连续两次未通过输入契约时也进入停止态。三种情况均不再执行底层检索，但不把局部停止扩大为整个 Agent 的固定轮数限制。

## 9. 验收标准

### 9.1 契约与状态机

- query-only、source-only、claim-only 和 pageRanges-only 合法路径通过；
- 缺失字段、无锚点、重复 ID、空数组、超长字段和错误枚举返回结构化 `invalid_request`；
- 同一 ID 改变 objective 或补检索缺少 refinementReason 返回可修正错误；
- 首次有效检索后新增 Need ID 返回 `NEW_NEED_NOT_ALLOWED`，且不执行检索；
- 同一 Need 连续两次动态或静态校验失败后返回 `need_stopped / answer`；
- 字段换序、数组换序、重复元素和大小写变化不能绕过重复识别；
- 混合批次逐 Need 状态正确，不再出现顶层与 Need 层状态矛盾；
- 事件流暴露 outcome、recommendedAction、reason 和输入错误 code，不暴露论文原文。

### 9.2 确定性证据回归

- 四论文现有 15 条样本 Recall@4 保持 15/15；
- locator 有效率 100%；
- JSON 来源与 Agent 可引用来源集合一致；
- 响应不超过 16 KiB，本地 p95 不超过 1.5 秒；
- 后端全量测试无失败，Skill quick_validate 通过。

### 9.3 真实 Agent 闭环

真实评测只给原始用户问题，不提供人工 Need。覆盖单事实、公式、多 Need、中文问题到英文论文术语、部分命中、无答案、画像/来源直读和视觉证据。固定模型、Thinking、Skill 版本与论文 artifact 版本，每题重复三次。

硬性标准：首次 Need 合法率、补检索 ID/objective 稳定率、refinementReason 完整率、引用属于实际读取来源、locator 有效和引用支持回答块均为 100%；语义等价重复请求、已解决 Need 重检索、收到 stop 后继续同方向检索、无证据精确结论均为 0；每个 Need 最多一次无产出补检索。关键事实首轮 Need 覆盖率不低于 90%。供应商超时和限流单独标记并重跑，不计作检索算法结果。

## 10. 变更范围

实现只修改证据 Skill、证据工具契约、Agent Run 内逐 Need 状态、事件诊断、对应测试和必要文档。当前论文均为测试数据，不保留旧 Need 协议或旧评测快照兼容逻辑；完成后保留未提交 diff 供审查。
