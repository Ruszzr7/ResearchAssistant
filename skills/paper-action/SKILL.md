---
name: paper-action
description: 当用户明确要求在论文可信区域执行跳转、高亮、下划线、笔记或评论时，通过结构化页面操作命令完成一次对应操作。
---

# 页面操作

仅当用户明确要求在论文页面中修改或跳转时，激活本 Skill。将请求转换为一次结构化的 `paper_action` 调用，提供 `actionType` 和可信的 `sourceObjectId`，仅在需要时提供 `content` 或 `color`。

使用当前选区或先前论文画像/证据结果中的 `sourceObjectId`。绝不要编造 ID 或坐标。本 Skill 不负责检索内容，也不创建引用。如果目标或操作存在实质歧义，应先请求澄清，再调用工具。
