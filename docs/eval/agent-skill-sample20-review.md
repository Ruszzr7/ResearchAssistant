# Sample20 案例与金标复核

本文件记录 2026-09-15 旧版 Sample20 暴露的问题，以及已经落实到当前 300 条数据集和运行器的修正。旧结果只用于回归分析，不作为当前版本指标。

## 已确认的问题

1. 第 2 类案例把局部实验结果统一标成 `paper-profile + paper-evidence`，要求过严。只要问题可以通过局部论文证据回答，金标应为 `paper-evidence`。
2. 第 7 类案例声称会话已加载画像和证据，但 API 运行器没有创建这种上下文，因此这些无 Skill 金标与真实请求不一致。
3. 第 8 类案例在 JSONL 中写了选区夹具，但运行器始终发送 `selectedContent=null`。这不能测试“真实选区只调用 action”的路径。
4. 运行器把 `WAITING_CLIENT` 当成终态，导致浏览器尚未执行高亮就结束观察。用户重新进入论文页面后动作继续完成，说明当时是测试等待口径错误。
5. 部分复合操作问题同时要求总结、定位、跳转、高亮和备注，增加了与 Skill 路由无关的歧义和执行负担。

## 当前修正

| 案例编号 | 当前问题类型 | 当前金标 |
|---:|---|---|
| 1 | 全文问题、方法、结果和局限 | profile + evidence |
| 2～6 | 局部实验、方法、公式、事实范围 | evidence |
| 7 | 只改写用户提供的文字，明确不核对论文 | 无 Skill |
| 8 | 浏览器真实选区的简单黄色高亮 | action |
| 9 | 先定位明确原文，再黄色高亮 | evidence + action |
| 10 | 轮换普通知识、含糊高亮、全文总结并高亮 | 无 Skill / action / profile + evidence + action |

当前 300 条金标正例数为：`paper-profile=40`、`paper-evidence=220`、`paper-action=80`。`AgentSkillEvalDatasetTest` 固定校验这些数量和每类案例的最小 Skill 合同，防止后续又把局部证据题标成全文画像题。

## 操作测试口径

- 第 8 类必须从浏览器的真实 PDF 选区发起，确保请求含有效 `documentHash`、页码、原文和来源对象。纯 API 脚本不得用虚构选区代替。
- API Sample20 使用第 9 类操作案例，由 Agent 先读取证据并定位目标。
- `WAITING_CLIENT` 只代表等待浏览器执行并回传回执。Skill 激活已经持久化时可以计算路由指标，但动作只有进入 `COMPLETED` 后才算执行成功。
- `scripts/eval/run-agent-skill-evaluation.ps1 -Sample20 -WaitForClientAction` 会等待浏览器回执；不加该参数时会把动作记录为 `PENDING_CLIENT_RECEIPT`，不会误报完成。

本轮正式指标仍只报告三个 Skill 各自的 Precision、Recall 和 F1。回答质量与页面动作是否完成作为运行有效性检查，不另行包装成简历指标。
