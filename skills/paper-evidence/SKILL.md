---
name: paper-evidence
description: 当回答依赖当前论文中的事实、公式、图、表、算法、指标或页面定位时，检索可引用的原始证据和必要的局部图像/视觉内容。
---

# 论文证据检索

当用户的问题需要当前论文的精确内容、公式、实验、图表、算法、指标或页面定位时，激活本 Skill。普通闲聊、只依赖已有对话内容的回答，以及不涉及当前论文的通用知识，不需要激活。

## 先设计证据需求（Need）

调用工具前，先从最终答案需要成立的事实出发，拆出 1～4 个独立 Need。Need 是内部的证据需求，不是对用户问题的复述。下文的英文标识仅对应工具协议字段或论文原术语，所有操作说明均以中文理解。

- 一个 Need 只对应一个可独立判断的事实，例如“方法如何处理干扰”或“实验中最差用户的结果”；不要把机制、结果和局限混在一起。
- 保持中立，不预设论文一定支持某个结论。否定性问题也只能检索论文是否明确讨论了该对象，不能把“没有找到”当成“不存在”。
- 每个 Need 使用稳定、简短且唯一的 `id`。再次检索同一事实时保留原 `id`，不要因为改写查询而新建 Need。
- `objective` 用中文、中立地说明要确认的事实；再次检索同一 Need 时原样保留。
- 每个 Need 至少提供一种检索锚点：非空 `query`、`sourceObjectIds` 或 `profileClaimRefs`。
- `query` 使用论文原文中的术语、变量名、公式编号、数值或指标名；不要只写“找相关内容”。
- `targets` 只填写可在论文原文中做词面核对的术语、数值、变量、公式或基线，不要填写中文语义结论。它是可选的检查点，不代表语义证明。
- `keywords` 是第一次查询没有命中时使用的少量核心词；`sectionHint`、`pageHints` 和 `contentTypes` 只在确有依据时填写。
- 如果上一次结果返回 `hasMore=true`，只使用同一 Need 返回的 `nextCursor` 继续读取下一批候选；不要把游标改写成新 Need。
- 已经知道可信来源时，使用 `sourceObjectIds` 直接读取，不要重新搜索；需要理解二维布局、图表、表格、算法或复杂公式时才设置 `includeVisual: true`。

把当前已知的独立 Need 放在同一次 `retrieve_paper_evidence` 调用的 `needs` 数组中。一次请求最多四个 Need。不要为了“看得更全面”提交一个覆盖整篇论文的宽泛 Need。

首次有效检索后，本 Run 的 Need ID 集合即被冻结。后续只能保留原 `id/objective` 补检索未解决 Need；不得用新 ID 重述原需求或借此绕过停止信号。因此首次调用必须覆盖回答所需的全部独立事实。

首次调用前检查：最终答案所需事实是否都已拆出；每个 Need 是否只有一个事实；`id` 是否唯一；`objective` 是否中立；检索锚点是否使用论文原文术语；当前已知 Need 是否已经批量提交。

## 阅读检索结果

每个 Need 的 `retrievalStatus` 只说明是否返回候选来源；`targetCoverage` 只说明 `targets` 是否出现词面匹配。二者都不代表来源已经在语义上证明了用户想要的结论。必须阅读返回的原文、公式、表格或视觉内容后再决定答案范围。

优先使用正文、公式和表格支持性能或因果性结论；caption 只能作为辅助上下文。不要把解析出的摘要、画像陈述或自己的推断写成论文原话。视觉 fallback 只说明页面区域需要人工/图像确认，不能据此猜测不可读的文字或公式。

工具返回的 `quote` 只是便于在答案中核对的短预览，不是完整证据。`fullText` 是当前返回的完整来源文本；只有 `contentComplete=true` 时才能把它当作完整证据，若为 false，应使用来源 ID 重新读取或缩小 Need，不得自行补写被截断的内容。检索结果可能返回 `candidateCount`、`returnedCount`、`hasMore` 和 `nextCursor`；候选未全部返回不等于论文没有相关内容。定位对象可能包含多个页面或多个矩形，引用时保留全部定位信息，不能只取第一个定位框。相同内容只有在完整文本、页码和矩形区域都相同的情况下才视为重复；同文但不同页面仍是不同证据。

公式证据优先使用可靠的 LaTeX 文本（`textFormat=LATEX` 且 `textReliable=true`）。如果文本不可靠或来自视觉 fallback，工具只会返回公式编号/区域标签；应依据该编号和原始页面区域核对，不要根据乱码 OCR 重构公式，也不要把“文本提取不可靠”等内部诊断文案写进答案。若用户要求公式具体表达式，必须设置 `includeVisual=true` 后再根据局部图像核对。

## 何时再次检索

第一次返回后，只对仍然缺少的具体 Need 进行补检索。保持同一个 `id` 和原 `objective`，填写 `refinementReason` 说明缺口和改写依据，并且至少改变一个有效检索条件：`query`、`keywords`、`sectionHint`、页码、内容类型或已知来源。不要重复完全相同或只换同义词的请求。

- `new_sources / judge`：阅读新来源，重新判断该 Need 是否已被原文充分支持。
- `no_match / refine_once`：第一次没有来源，可以针对明确缺口有效改写一次。
- `same_sources / stop`：补检索只返回旧来源，停止该检索方向。
- `duplicate_request / stop`：请求与此前相同，立即停止重复调用。
- 补检索仍为 `no_match / stop`：停止该检索方向并说明证据限制。
- `need_stopped / answer`：该 Need 已进入停止态，工具没有再次执行底层检索；立即转入回答。
- 输入不合法时按 `issues` 修正一次；同一 Need 连续两次未通过输入契约会进入 `need_stopped / answer`，不要继续试错。
- 某个 Need 证据不足但其他 Need 已充分：回答有证据的部分，并明确剩余限制。

检索停止由 Agent 根据原文和这些客观进展信号判断。Skill 或工具不会调用第二个模型来判断证据充分性，也不会把检索状态当成答案结论。

顶层 `noProgress` 只表示本次所有 Need 是否都没有新增来源；`stopRecommended=true` 仅在所有当前 Need 都没有可继续的候选时成立。它不是论文结论，也不表示单个 Need 一定已经解决；单个 Need 的 `progress` 和 `hasMore/nextCursor` 优先用于决定该 Need 的下一步。

## 形成最终回答

论文相关回答必须通过 `submit_answer` 完成。无论来源是在本轮读取还是由当前论文版本的历史证据上下文复用，依赖论文的每个事实性答案块都只绑定实际读取且确实支持该块的 `sourceObjectIds`；不要添加自编号引用或引用未读取的来源。一般知识回答也使用 `submit_answer`，此时 `sourceObjectIds` 为空。若结论是跨来源推断，应明确使用“根据这些结果可以推断”等措辞，并绑定构成推断的来源。论文没有提供足够证据时，缩小回答范围并如实说明，不补写或臆测。

### Need 拆分示例

问题：“论文的方法为什么改善最差用户公平性？”可以拆为：

- `method-mechanism`：`objective` 为“确认改善公平性的机制”，query/targets 使用论文原文中的 `common stream`、`private stream`、`interference management`、`power allocation` 等术语。
- `worst-user-result`：`objective` 为“确认最差用户的实验结果”，query/targets 使用论文原文中的最差用户、公平性指标和基线术语，`contentTypes` 可包含 TEXT、TABLE、FIGURE。

问题：“解释公式（21）及其作用”应至少检索公式编号 `21`；如果需要确认二维排版或变量上下标，再设置 `includeVisual`。不要用一个“方法和所有实验结果”的 Need 替代这些独立需求。
