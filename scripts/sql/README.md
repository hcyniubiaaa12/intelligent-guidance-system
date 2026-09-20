# SQL 初始化脚本

对应《数据库设计.md》v0.2。双库分工：MySQL 管事实，pgvector 管语义。

## MySQL（先执行）

```bash
mysql -uroot -p < mysql_init.sql
```

包含：全部 19 张业务表（公共字段 id/deleted/created_at/updated_at）+ 初始数据（admin 账号 + 12 条默认 sys_config）。

**枚举字段**：状态列一律存英文小写编码值（如 `ongoing` / `parsing` / `pending`），列宽统一 `varchar(32)`；Java 侧由各模块 `enums` 包的枚举经 `@EnumValue` 自动装载，两端对齐关系见《数据库设计.md》§0 全字段枚举清单。布尔语义字段（`deleted`/`enabled`/`top1_hit` 等）保持 tinyint 0/1。

⚠️ admin 初始密码为示例 BCrypt 值（明文 `123456`），上线前请重新生成替换。

## 已有库的增量变更（升级必看）

`mysql_init.sql` 用 `CREATE TABLE IF NOT EXISTS`，**不会**改动已存在的表；下列变更需手动执行 `migration_*.sql`：

| 日期 | 变更 | 脚本 |
|------|------|------|
| 2026-09-19 | `chat_session` 新增 `has_result`（新主诉判定双信号之一，见《数据库设计.md》§2） | `migration_20260919_chat_session_has_result.sql` |
| 2026-09-20 | `user` 新增 `mute_until`、新建 `user_violation`、`sys_config` 新增 5 条敏感词处置参数（见《数据库设计.md》§1） | `migration_20260920_user_violation.sql` |

## PostgreSQL + pgvector（后执行）

```bash
psql -U postgres -c "CREATE DATABASE guide_vec;"
psql -U postgres -d guide_vec -f pgvector_init.sql
```

包含：`kb_chunk_vec`（RAG 向量召回）与 `cluster_bucket_vec`（聚类锚点，feedback 直写），HNSW + 余弦距离，embedding 维度 1024 对应阿里 text-embedding-v3——**换 embedding 模型需同步修改维度并重建向量**。

## 语料与科室（可挂号范围）

科室蓝本与知识片段由应用侧装载：`guide.seed.enabled=true`（默认开启）时启动即幂等装载
7 个科室 + 12 条鉴别语料 + 术语白名单——**它是挂号科室与知识库的唯一默认来源**，
链路 B 上传流水线就绪后可关闭。

连接参数写入 `backend/admin/src/main/resources/application-local.yml`（不提交）。
