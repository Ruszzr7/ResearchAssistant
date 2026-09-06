---
name: paper-action
description: 当用户明确要求在论文可信区域执行跳转、高亮、下划线、笔记或评论时，通过结构化页面操作命令完成一次对应操作。
---

# Page operation

Activate this Skill only when the user explicitly asks to modify or navigate the paper page. Convert the request into one structured `paper_action` call with `actionType`, a trusted `sourceObjectId`, and `content` or `color` only when needed.

Use a sourceObjectId from the current selection or an earlier paper profile/evidence result. Never invent an ID or coordinates. Do not use this Skill to retrieve content or create citations. If the target or operation is materially unclear, ask for clarification before calling the tool.
