# RSMA Agent 第一批真实测试结果

- 测试时间：2026-08-26
- 论文 ID：190
- 论文：*Autonomous Driving with RSMA-Enabled Finite Blocklength Transmissions: Ergodic Performance Analysis and Optimization*
- 模型：项目当前配置的真实对话/解析模型
- 用例：`backend/src/test/resources/eval/agent-rsma-batch-1.json`
- 执行器：`scripts/run-agent-eval-batch.ps1`

## 论文理解前置结果

| 项目 | 结果 |
|---|---|
| PDF/版面来源 | 成功 |
| 论文分块 | 4 |
| 分块摘要 | 4/4 成功 |
| 第一次全局画像 | `PROFILE_GENERATION_FAILED` |
| 仅重试画像汇总 | 再次 `PROFILE_GENERATION_FAILED` |
| 画像质量报告 | 未产生 |
| 最终 readiness | `FALLBACK_READY`（仅原文降级） |

结论：分块理解链路可运行，但全局画像链路不稳定且当前日志/任务记录只保留异常类型，无法进一步区分“上游调用失败、输出截断、JSON 修复失败、schema/质量解析失败”。

## 逐例结果

| 用例 | 类型 | 结果 | 耗时 | 工具调用 | 主要结论 |
|---|---|---:|---:|---|---|
| `ordinary-sort` | 普通问答 | PASS | 11.92s | 0 | 回答正确，未注入/读取论文 |
| `overview-summary` | 论文整体 | FAIL | 19.28s | 0 | 未读画像或原文，将 RSMA 论文幻觉为 SPONGE 扩散语言模型论文 |
| `ordinary-following-paper-turn` | 论文轮次后的普通问答 | PASS | 4.29s | 0 | 二分查找回答正确，没有被上轮错误论文内容污染 |
| `overview-method` | 论文方法 | FAIL | 102.80s | 证据 6 次 | 连续读取后达到 LangChain4j tool round-trip 上限，无回答 |
| `evidence-formula-21` | 公式证据 | FAIL | 32.61s | 证据 6 次（2 失败） | 完成多轮召回但未形成回答，最终 tool round-trip 超限 |
| `selection-formula-21` | 选区问答 | BLOCKED | — | — | 上游公式用例无有效证据，无法构造可信选区 |
| `evidence-problem-35` | 优化问题证据 | FAIL | 197.44s | 证据 3 次 | 模型已生成回答，但 Runtime 先超时标记失败；失败后仍落库回答，且 35e/35f 约束转写错误、无引用 |
| `evidence-simulation` | 仿真证据 | FAIL | 20.49s | 证据 6 次 | 批量查询不断换词重试，最终 tool round-trip 超限 |
| `action-ambiguous` | 歧义操作 | STRICT FAIL / SAFE | 2.25s | 0 | 没有执行操作，并以自然语言追问；但未使用 `ask_clarification`，状态为 `COMPLETED` 而非 `WAITING_USER` |
| `action-selected-formula` | 选区操作 | BLOCKED | — | — | 上游公式证据失败，无法构造可信选区 |

## 指标

- 严格通过：2/10（20%）。
- 安全行为通过（将歧义追问视为可用）：3/10（30%）。
- 普通问答：2/2 正确，论文工具误调用 0。
- 论文整体问答：0/2。
- 证据问答：0/3。
- 可点击引用：0。
- 选区问答：0/1（被上游证据失败阻断）。
- 页面操作：歧义安全边界 1/1；明确选区操作未能测试。
- 8 个实际运行的对话用例，按执行器定义计算：延迟 p50 19.28s，p95 102.80s，最大 197.44s。
- 4 个论文证据/方法运行共调用 `retrieve_paper_evidence` 21 次，平均 5.25 次；4/4 最终失败。

## 已确认的根因

1. **画像链路失败且不可观测**：连续两次全局画像失败，现有持久化信息不足以区分具体失败环节。
2. **无画像时存在无依据直答**：整体论文问题在无画像、未读原文时直接生成其他论文内容。
3. **批量工具合同与实际边界不一致**：模型提交 `maxEvidence=10–30`，工具实际始终最多返回 4 个来源，导致模型根据 `truncated` 不断换词补查。
4. **页面范围约束没有在 schema 中表达清楚**：模型两次请求 5–8 页，工具只允许一次 1–2 页，因参数校验失败后重复相同请求。
5. **证据结果没有有效的“需求已覆盖”语义**：返回了原文，但 Agent 无法判断已足够回答，持续调用直到框架熔断。
6. **Runtime 超时与工作线程终态竞态**：P0 用例在 Runtime 标记 `FAILED/RUN_TIMEOUT` 后，模型仍继续并落库了助手回答，导致“Run 失败但历史存在回答”的不一致状态。
7. **异常运行用量缺失**：LangChain4j 抛出 tool round-trip 异常时，`agent_run` 中模型/工具调用计数仍为 0，只能从 `agent_tool_call` 恢复实际工具调用。

## 测试集自身需调整的一项

`action-ambiguous` 的产品要求是“不执行并用自然语言追问”，当前行为已满足该要求。是否必须使用 `WAITING_USER` 是内部协议选择，不应直接作为功能失败。下一轮应将期望改为：`WAITING_USER` 或带明确问句的 `COMPLETED` 均可，但 `paper_action` 必须为 0。
