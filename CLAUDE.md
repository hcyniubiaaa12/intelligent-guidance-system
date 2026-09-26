# CLAUDE.md —— 智能导诊系统 全局规范

## 1. 目录层级

### 1.1 前端（单工程，Vite）

```
frontend/
├── src/
│   ├── views/patient/   # 患者端（Vant，样式：临床纸感 方案 A）：对话、推荐卡片、模拟挂号
│   ├── views/admin/     # 管理端（Element Plus，样式：冷靛控制台 方案 B）：知识库、审核、看板、LLM 配置
│   ├── views/common/    # 双端共用（新增）：登录/注册页
│   ├── api/             # HTTP 统一封装
│   ├── stores/          # Pinia：登录态、会话状态
│   ├── utils/           # 埋点上报、SSE 客户端
│   └── router/          # 路由守卫：admin 路由校验 ROLE_ADMIN
└── vite.config.js       # /api 代理到后端
```

- 患者端与管理端物理隔离，不互相 import
- 患者端视觉风格遵循 `.claude/rules/前端设计方案.md`（临床纸感方案 A），管理端遵循同文件「冷靛控制台」章节（方案 B），均已定稿；新页面不得偏离对应方案的色板、字体与部件规范

### 1.2 后端（Maven 多模块）

```
backend/
├── common/     # 公共件：统一返回体、异常、工具类、常量；被所有模块依赖，不依赖任何业务模块
├── auth/       # 链路 D：Security + JWT 签发/校验、登录注册、角色权限、用户封禁状态校验；sys_config 运行时参数
├── chat/       # 链路 A：问诊对话编排、SSE 事件流（delta/question/result/done/error + session）、会话/消息落库、信息充足性判定与追问、敏感词入口前置校验
├── rag/        # RAG 检索层：查询改写→召回→重排→Prompt→解析
├── llm/        # LLM 适配层：DeepSeek 对话 / 阿里 embedding / 阿里 rerank / 阿里 DocumentMind 文档解析；唯一外部模型出口
├── kb/         # 链路 B：知识库管理、科室/文档/映射维护；唯一写向量库入口；文档解析（格式分流）与切分（三层策略）
├── async/      # 离线侧：入库流水线线程池 + 任务表编排（阶段推进；解析与切分在 kb）
├── feedback/   # 链路 C：埋点、比对、聚合、审核回流
├── stats/      # 统计看板：准确率/分布/盲区
└── admin/      # 启动器：聚合全部模块、controller 层、application.yml
```

## 2. 依赖规范（单向，禁止循环）

- 依赖方向：admin → 各业务模块 → rag → llm；所有模块 → common；禁止反向与循环
- rag 不依赖业务模块，不感知业务状态（会话、用户）
- chat 不得直连向量库、ES 与 LLM，只经 rag → llm
- kb 是唯一写向量库与 ES chunk 索引入口（上传流水线 + 回流同步）；**双写不是同一事务**——MySQL 在事务内，pgvector 走独立连接、ES 走 HTTP，都吃不到事务，故写入失败必须走写时补偿回删已写入的向量；聚类锚点向量除外，归 feedback 模块直写 pgvector（见数据库设计 cluster_bucket_vec）
- 文档解析：`pdf`/`docx`/`png` 只经 `llm` 适配层调 DocumentMind（**唯一路径，不降级**）；`html` 走 Tika、`txt`/`md` 原生读；**Tika 不得接 pdf/docx**——降级路径会顺着依赖爬回来
- feedback 只读导诊记录、只写映射与知识片段；埋点旁路，不阻塞主流程
- async 线程池与在线导诊线程隔离，不共用
- 回流只前向修正，不回改历史导诊记录；写知识库唯一路径是人工 approve
- JWT 登录态存 Redis，登出/过期即时失效；其余业务状态一律落库，不经 Redis

## 3. 中间件（只许用这些，不额外引入）

MySQL、PostgreSQL + pgvector、MinIO、Spring Security + JWT、MyBatis、Redis（存储用户登录 JWT）、Elasticsearch（chunk 全文检索 + 医疗术语聚合）。
不引入消息队列、注册中心；新依赖先确认现有组件无法等价实现。
双库分工：MySQL 管事实，pgvector 管语义，ES 管全文检索与术语聚合。

## 4. Git 提交规范

`<type>(<scope>): <subject>`，一次提交一件事。
type：feat / fix / docs / style / refactor / perf / test / chore。
scope：common / chat / kb / feedback / auth / admin / patient / rag / llm / async / stats。

密钥安全：配置文件（application.yml、.env 等）含密钥/API Key 时，提交前必须提醒用户不要提交。
配置分工：`application.yml` 公共配置（占位符 `${...}`，提交）；`application-local.yml` 私密配置（真实密钥，不提交，已 gitignore）；`prompts.yml` 提示词配置（导诊主提示词/查询改写/话术，经 `spring.config.import` 引入，改提示词不改代码，启动时校验占位符完整性）。
日志：链路 A 关键节点（入口校验/召回/融合/精排/生成/解析/落库）打 INFO 且带轮次标记 `[sXXXXXX-rN]`（MDC `turn`），需要模型请求与召回明细时把 `com.guide.rag`/`com.guide.llm` 调到 DEBUG。
新增密钥流程：cp application.yml application-local.yml → 在 local 填真实值 → 正常启动（active 叠加覆盖）；同时更新 `application-local.yml.example` 模板（占位符，提交）。

## 5. 开发前置（开工前必读）

- 开始任何功能改动前，先读 `进度.md`：了解各链路完成度、未接通的假数据点与已知坑
- 再读 `CONTEXT.md`（领域词汇表）：领域术语以它为准，代码、文档、接口命名与词表保持一致
- 遇到词表未覆盖的新领域概念，先把词条补进 `CONTEXT.md` 再写代码；发现用词与词表冲突，先改词表再动手

## 6. 功能完成后的校验（提交前逐项过）

- 数据流：entity / mapper / dto / 前端 api / 页面五处同步；检查统计看板、导出、关联页面是否漏改
- 检索存储双写：chunk 改动必须同步 pgvector 与 ES 两路，外加 MySQL 里的切片正文副本（三处）；**不是同一事务**——MySQL 在事务内，pgvector 与 ES 不在，失败靠写时补偿（回删已写向量）与删除补偿顺序收敛
- 约束：无新中间件；依赖方向未破坏；未绕过 LLM 适配层；埋点未侵入主流程；线程池未混用
- 验证：后端编译通过；接口真实请求跑通；涉及链路 A/B/C/D 时核对基线文档对齐点
- 文档：涉及链路对齐点、参数、模块边界的改动，同步更新《总体架构与链路设计.md》
- 进度：功能完成后立即更新 `进度.md`（完成内容、耗时、困难点与解法、新待办）
- 术语：新增/变更领域概念时同步更新 `CONTEXT.md`
- 校验：功能修改后检查 `.claude/rules/` 下全部文档（数据库设计.md、前端设计方案.md）是否过时，CLAUDE.md 本身同样校验；过时即同步更新
