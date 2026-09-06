---
name: paper-profile
description: 当对话缺少对当前论文研究问题、方法、贡献、实验、结论或局限的整体认识时，加载已生成的全文画像；结果会进入上下文，并可能包含关联原文来源的证据。
---

# Whole-paper profile

Activate this Skill when a question needs a whole-paper view and that view is not already present in the current context.

After activation, call `read_paper_profile` to load the prepared profile. The returned profile is paper context and remains available to the Agent in the conversation; reuse it instead of activating or reading it again merely for confirmation. If context compaction removes the profile, activate this Skill again.

The profile can contain sourceObjectIds attached to claims and benchmark results. Cite one only when it directly supports the answer. For exact wording, local facts, formulas, figures, tables, algorithms, or page-linked visual inspection, activate `paper-evidence` and retrieve original sources. Do not retrieve evidence or perform page actions from this Skill.

If the profile is unavailable or stale, state that limitation and use original evidence where appropriate.
