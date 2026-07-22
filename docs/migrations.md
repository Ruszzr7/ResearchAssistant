# 数据库迁移说明

Mission 13 起，Flyway 是正式数据库迁移工具。

- `V12.1__baseline_current_schema.sql`：Mission 12.1 完成后的无损基线，只创建缺失对象，不删除数据。
- `V13__mission13_data_safety.sql`：RAG 一致性检查审计表。
- `V13.1__repair_legacy_schema.sql`：修复已被 baseline 标记、但实际缺少 Mission 12 表/字段的旧库；补齐异步任务、RAG、AI 质量和证据元数据，不删除既有数据。
- `V14__paper_layout_artifact.sql`：保存按 PDF hash 与组合解析器版本缓存的语义版面制品。
- `V15__paper_workbench_runs.sql`：保存 PDF 工作台固定计划、artifact 版本、run/step 状态和证据/token/耗时 trace。
- `V16__grounded_paper_analysis.sql`：为全文分析报告保存门禁通过的正文、evidence IDs、workbench run ID、PDF hash 与 parser version。
- `V17__layout_artifact_provenance.sql`：为版面制品保存主解析器、最终解析器、回退原因、质量分与回退收益。
- `V18__formula_region_artifact.sql`：保存用户框选的公式区域、版本绑定坐标、候选/确认状态、LaTeX、来源与置信度；论文删除时级联清理。
- `V19__research_sessions.sql`：研究档案、论文关联与可恢复对话消息。
- `V20__goal_driven_reading_plans.sql`：已发布的历史阅读计划结构；不能删除，后续由 V22 正式移除。
- `V21__writing_claim_evidence_matrix.sql`：写作论点、证据关系和项目内论文约束。
- `V22__remove_reading_plans.sql`：按外键顺序移除阅读计划领域表。
- `V23__redesign_pdf_notes_and_comments.sql`：清理旧测试标注，统一选区笔记与页面批注，并删除独立 notes 表。
- `V24__structured_paper_memory.sql`：版本化论文结构事实与记忆记录。
- `V25__paper_memory_understanding_progress.sql`：异步分块理解进度、阶段、token 与恢复字段。
- `V26__paper_context_and_observation_memory.sql`：服务端对话轮次、grounded observations 与冻结上下文快照。
- `V27__inline_math_transcription_cache.sql`：按 PDF hash、解析版本、block 字符范围、原文 hash 和 provider 版本缓存行内数学转写；论文删除时级联清理。

空数据库直接启动即可执行迁移。已有数据库必须先备份；baseline history 记录不等于实际 schema 完整，旧库应先让 Flyway 执行增量修复迁移。对部分升级或未知版本数据库，禁止盲目 baseline。Flyway 迁移目录是唯一结构真源，已删除早期手工 schema 和升级脚本。

本文和迁移脚本均使用 UTF-8；PowerShell 查看 SQL 时显式使用 `Get-Content -Encoding utf8`。
