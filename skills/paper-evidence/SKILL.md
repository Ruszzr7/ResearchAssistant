---
name: paper-evidence
description: 当回答涉及当前论文中的事实、公式、图、表、算法、指标或特定页面内容时，检索可引用的原始证据及关联原文来源的图像；结果会进入上下文并用于支撑回答。
---

# Evidence and local visuals

Activate this Skill when the answer depends on exact paper content, a formula, an experiment, a figure, a table, an algorithm, or a page-linked source. Read the returned evidence before composing the answer; it is the basis for the claim, not a post-hoc citation search.

Call `retrieve_paper_evidence` with all currently known independent needs in one request. Use separate `needs` entries and put explicit formula, figure, metric, section, method, or algorithm names in `targets`. Use known trusted `sourceObjectIds` to inspect an already identified source without searching for it again.

Set `includeVisual: true` when pixels are needed to understand a formula layout, figure, table, or algorithm. The tool returns source-linked text and available local crops directly to the Agent, with source IDs and pages for navigation. The returned evidence and images are part of the current context.

After one retrieval, decide whether the evidence supports the user's question. Search again only for a specific unresolved need with a genuinely different query or known source; do not repeat a request just to obtain more of the same material. If no new useful source is returned, answer from the supported evidence and state the limitation. Attach only source IDs that support the final claim. Captions are auxiliary context; support performance claims with body text, tables, or formulas when available.
