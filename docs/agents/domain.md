# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root, or
- **`CONTEXT-MAP.md`** at the repo root if it exists: it points at one `CONTEXT.md` per context. Read each one relevant to the topic.
- **`docs/adr/`**: read ADRs that touch the area you're about to work in. In multi-context repos, also check `src/<context>/docs/adr/` for context-scoped decisions.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill (reached via `/grill-with-docs` and `/improve-codebase-architecture`) creates them lazily when terms or decisions actually get resolved.

## This repo (智能导诊系统)

Beyond `CONTEXT.md`, these are load-bearing — read them before touching the area they cover:

- **`总体架构与链路设计.md`** (repo root) — 架构基线与四条链路的「对齐点」。它在本仓库扮演 `docs/adr/` 的角色。改动涉及链路 A/B/C/D 对齐点、参数或模块边界时，必须同步更新它。
- **`CLAUDE.md`** — 模块依赖方向（单向、禁循环）、中间件白名单、提交与完工校验清单。
- **`.claude/rules/数据库设计.md`** — 表结构、枚举字段规范、逻辑删除与唯一键的坑。
- **`.claude/rules/前端设计方案.md`** — 患者端 / 管理端视觉方案（均已定稿，新页面不得偏离）。
- **`进度.md`** — 各链路完成度、未接通的假数据点、已知坑。

Note: `CONTEXT.md` here is a **domain glossary**（领域词汇表）, not a context map. A new domain concept must be added there *before* it appears in code, doc, or interface names.

## File structure

Single-context repo (this one):

```
/
├── CONTEXT.md                          ← 领域词汇表
├── 总体架构与链路设计.md                  ← 架构基线（本仓库的 docs/adr/ 等价物）
├── .claude/rules/                      ← 数据库设计.md / 前端设计方案.md
├── backend/                            ← Maven 多模块
└── frontend/                           ← Vite 单工程
```

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `CONTEXT.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal: either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag baseline conflicts

If your output contradicts an existing decision in `总体架构与链路设计.md` or a rule in `CLAUDE.md`, surface it explicitly rather than silently overriding:

> _Contradicts 链路 B「一份文档 = 一个科室」, but worth reopening because…_
