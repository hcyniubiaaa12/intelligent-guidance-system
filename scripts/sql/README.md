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
| 2026-09-26 | **pgvector** `kb_chunk_vec` 新增 `doc_id` / `title` / `content`（归属 + 正文副本，见《数据库设计.md》§4.1）。**分两步**，中间要回填存量行 | `migration_20260926_kb_chunk_vec_payload_step1.sql` → 回填 → `..._step2.sql` |
| 2026-09-26 | `ingest_task` 新增 `external_job_id`（外部解析服务任务号，两段式编排靠它重启后接着轮询，见《数据库设计.md》§7）。上传流水线未动工，暂不执行也不影响既有功能 | `migration_20260926_ingest_task_external_job_id.sql` |

## PostgreSQL + pgvector（后执行）

```bash
psql -U postgres -c "CREATE DATABASE guide_vec;"
psql -U postgres -d guide_vec -f pgvector_init.sql
```

包含：`kb_chunk_vec`（RAG 向量召回）与 `cluster_bucket_vec`（聚类锚点，feedback 直写），HNSW + 余弦距离，embedding 维度 1024 对应阿里 text-embedding-v3——**换 embedding 模型需同步修改维度并重建向量**。

`kb_chunk_vec` 除向量外还存 `doc_id`（行自证归属、按文档批量清理）与 `title` / `content`（MySQL 切片正文的**副本**）。副本**只作人工排查用**：`searchChunks` 不 SELECT 它们，检索仍经 `ChunkTextProvider` 回填 MySQL——**MySQL 为准，副本不作数**，故 `kb_chunk` 只写不改（任何引入 UPDATE 路径的改动都要同步三处）。

### 存量向量回填（2026-09-26 起，老库升级必看）

上面那三列是后加的，**老库里已写入的向量行没有值**，step2 的 `NOT NULL` 会因此加不上。跨库不能 JOIN，回填只能「读 MySQL → 批量 UPDATE pgvector」，故走应用侧一次性脚本：

```bash
# 1. 加可空列 + 索引（可重复执行）
psql -U postgres -d guide_vec -f migration_20260926_kb_chunk_vec_payload_step1.sql

# 2. 回填（幂等：只挑 doc_id 为空的行；跑完即空转）
#    带上这个开关启动一次后端即可，日志会打印「待补 N 条 / 已回填 M 条」
mvn -pl admin spring-boot:run -Dspring-boot.run.arguments=--guide.backfill.chunk-vec-payload.enabled=true

# 3. 收紧约束
psql -U postgres -d guide_vec -f migration_20260926_kb_chunk_vec_payload_step2.sql
```

第 2 步若报告**孤儿向量**（MySQL 里已无对应切片，成因是 pgvector 与 ES 都不在 MySQL 事务里），脚本**只报告不删除**——确认后按日志给出的 chunk_id 手动清理，再跑第 3 步。全新初始化无需以上三步（`pgvector_init.sql` 已是最终形态）。

## 语料与科室（可挂号范围）

科室蓝本与知识片段由应用侧装载：`guide.seed.enabled=true`（默认开启）时启动即幂等装载
7 个科室 + 12 条鉴别语料 + 术语白名单——**它是挂号科室与知识库的唯一默认来源**，
链路 B 上传流水线就绪后可关闭。

连接参数写入 `backend/admin/src/main/resources/application-local.yml`（不提交）。
