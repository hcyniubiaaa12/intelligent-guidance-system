# SQL 初始化脚本

对应《数据库设计.md》v0.5。双库分工：MySQL 管事实，pgvector 管语义。

## MySQL（先执行）

```bash
mysql -uroot -p < mysql_init.sql
```

包含：全部 19 张业务表（公共字段 id/deleted/created_at/updated_at）+ 初始数据（admin 账号 + 12 条默认 sys_config）。

**枚举字段**：状态列一律存英文小写编码值（如 `ongoing` / `parsing` / `pending`），列宽统一 `varchar(32)`；Java 侧由各模块 `enums` 包的枚举经 `@EnumValue` 自动装载，两端对齐关系见《数据库设计.md》§0 全字段枚举清单。布尔语义字段（`deleted`/`enabled`/`top1_hit` 等）保持 tinyint 0/1。

⚠️ admin 初始密码为示例 BCrypt 值（明文 `123456`），上线前请重新生成替换。

脚本即最终形态：历次增量（`has_result`、`mute_until` / `user_violation`、敏感词与入库的 `sys_config`、`ingest_task.external_job_id`、`kb_chunk.chunk_type`、`guide_record.bucket_id`、`review_task.main_dept_id`）都已并入本文件，不再单发迁移脚本。

⚠️ **全表都是 `CREATE TABLE IF NOT EXISTS`——重跑本脚本不会修改已存在的表。** 表已经建过、而后续版本改过列或索引时，重跑等于什么都没做，库会停在旧结构上，症状是运行期才炸的 `Unknown column`（例如 `review_task` 早期叫 `result_dept_id`，后来改成 `main_dept_id`）。**改过表结构之后，对已有的库要单独执行一次 ALTER。**

### 怎么查一个已有库漏了什么

把脚本灌进一个**临时库**，再拿两边真实的 `information_schema` 逐表、逐列、逐索引对——别靠读脚本肉眼看，列多、索引更多。

⚠️ 演练前先把脚本第 8 / 10 行的库名改掉：那里写死了 `CREATE DATABASE IF NOT EXISTS \`guide\`` + `USE \`guide\``，**直接 `mysql 临时库 < mysql_init.sql` 会灌到线上库去**（语句都幂等，不会损坏数据，但"临时库"是空的、结论全错）。

### 已知需要单独 ALTER 的两处

本机 2026-09-29 已执行；别的环境照抄（都在 `guide` 库上）：

```sql
-- 审核任务的主科室列改过名：旧库是 result_dept_id
ALTER TABLE review_task RENAME COLUMN result_dept_id TO main_dept_id;

-- 台账键 (symptom, main_dept_id) 的唯一键是后加的：旧库只有普通索引 idx_dm_symptom，
-- 不补的话同键会静默堆出重复行（approve 的 upsert 是"先查后插"，没有唯一键兜不住并发）
ALTER TABLE dept_mapping DROP INDEX idx_dm_symptom, ADD UNIQUE KEY uk_dm_symptom_main (symptom, main_dept_id);
```

若某条索引在库里不存在，`DROP INDEX` 会报 `Can't DROP ... check that column/key exists`——把该子句去掉再执行即可。

## PostgreSQL + pgvector（后执行）

```bash
psql -U postgres -c "CREATE DATABASE guide_vec;"
psql -U postgres -d guide_vec -f pgvector_init.sql
```

包含：`kb_chunk_vec`（RAG 向量召回）与 `cluster_bucket_vec`（聚类锚点，feedback 直写），HNSW + 余弦距离，embedding 维度 1024 对应阿里 text-embedding-v3——**换 embedding 模型需同步修改维度并重建向量**。

`kb_chunk_vec` 除向量外还存 `doc_id`（行自证归属、按文档批量清理）、`title` / `content`（MySQL 切片正文的**副本**）与 `chunk_type`（版面类型，历史与未知为 `unknown`）。副本**只作人工排查用**：`searchChunks` 不 SELECT 它们，检索仍经 `ChunkTextProvider` 回填 MySQL——**MySQL 为准，副本不作数**，故 `kb_chunk` 只写不改（任何引入 UPDATE 路径的改动都要同步三处）。

脚本即最终形态：`doc_id` / `title` / `content` / `chunk_type` 都已并入本文件，不再单发迁移脚本。

同样受 `CREATE TABLE IF NOT EXISTS` 保护——**旧库不会因为重跑本文件而补上新列**。2026-09-29 已用 JDBC 探针（无 psql 客户端）核对过本机实库：两张表的列、HNSW 索引、`vector` 扩展与 `chunk_type` 默认值都与本文件一致，无需补 ALTER。

## 语料与科室（可挂号范围）

科室蓝本与知识片段由应用侧装载：`guide.seed.enabled=true`（默认开启）时启动即幂等装载
7 个科室 + 12 条鉴别语料 + 术语白名单——**它是挂号科室与知识库的唯一默认来源**，
链路 B 上传流水线就绪后可关闭。

连接参数写入 `backend/admin/src/main/resources/application-local.yml`（不提交）。
