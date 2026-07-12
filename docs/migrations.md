# 数据库迁移说明

Mission 13 起，Flyway 是正式数据库迁移工具。

- `V12.1__baseline_current_schema.sql`：Mission 12.1 完成后的无损基线，只创建缺失对象，不删除数据。
- `V13__mission13_data_safety.sql`：RAG 一致性检查审计表。
- `schema.sql` 和 `schema-upgrade-*.sql`：历史参考，不再作为部署步骤。

空数据库直接启动即可执行迁移。已有数据库必须先备份，并确认已达到 Mission 12.1 schema 后再执行一次 baseline 切换；对部分升级或未知版本数据库，禁止盲目 baseline。
