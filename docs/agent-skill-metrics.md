# Agent Skill 测评指标

## 测评范围

- 数据集：`backend/src/test/resources/eval/agent-skill-300.jsonl`
- 规模：30 篇论文，每篇 10 条案例，共 300 条多标签案例
- 结果：`backend/target/eval/agent-skill-evaluation-final.jsonl`
- 每个案例运行一次；修正测试驱动或金标后使用相同 `caseId` 覆盖原记录
- 测评对象：`paper-profile`、`paper-evidence`、`paper-action`

## 评分口径

每个 Skill 独立作为二分类任务：

```text
Precision = TP / (TP + FP)
Recall    = TP / (TP + FN)
F1        = 2 × Precision × Recall / (Precision + Recall)
```

- `requiredSkills` 中要求且被激活：TP
- `requiredSkills` 中要求但未激活：FN
- 被激活且不在 `allowedSkills` 中：FP
- 被激活但属于 `allowedSkills`、只是非必需：不计入 TP、FP 或 FN

## 最终结果

| Skill | TP | FP | FN | Precision | Recall | F1 |
|---|---:|---:|---:|---:|---:|---:|
| `paper-profile` | 37 | 0 | 2 | 100.00% | 94.87% | 97.37% |
| `paper-evidence` | 216 | 0 | 4 | 100.00% | 98.18% | 99.08% |
| `paper-action` | 80 | 0 | 0 | 100.00% | 100.00% | 100.00% |

画像 Skill 的两条漏调用案例为 `paper-25-case-01` 和 `paper-30-case-01`。`paper-09-case-10` 的画像已按人工复核结果调整为可选调用，因此不再计为漏调用。

## 结果有效性

- 数据集记录数：300
- 测试结果记录数：300
- 唯一 `caseId`：300 / 300
- 数据集与结果集合缺失或多余记录：0
- 结果元数据不匹配：0
- `requestValid=false`：0
- 失败记录：0
- `metricEligible=false`：0

其中 222 条进入 `COMPLETED`，78 条为 `WAITING_CLIENT`。后者表示后端已经生成并校验操作票，但本轮脚本没有等待浏览器回执；它可以用于 Skill 路由指标，不代表页面操作已经收到客户端成功回执。
