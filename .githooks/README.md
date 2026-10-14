# .githooks —— 随仓库分发的 git hooks

本目录被 git 跟踪（`.git/hooks/` 里的东西不会跟着仓库走，这里会）。目前有 `pre-commit` 与 `pre-merge-commit` 两个。

## 启用（每台机器、每个克隆做一次）

```sh
git config core.hooksPath .githooks
```

它是**本机配置**，不会随克隆带过去——所以新克隆的人必须自己执行这一条。想确认是否已启用：

```sh
git config core.hooksPath   # 应输出 .githooks
```

## 两个 hook 的关系：闸只有一份

`pre-merge-commit` **只是一层转发**（`exec pre-commit`），不复制任何检查逻辑。为什么不合并成一个文件：**`git merge` 的自动合并提交不触发 `pre-commit`**——`pre-commit` 是 `git commit` 的钩子，而一次无冲突的 merge 由内部机制直接落提交、绕过它；git 为这条路径单独留了 `pre-merge-commit`。

代价真踩到过：本仓库没有 CI，这套 hook 是唯一的 guardrail，而一次功能合并（17 个提交）**完全不设防**地落进了 `main`。功能合并恰恰是最该设防的时刻——它是"一整批改动一次性进主干"。

⇒ **以后加闸只改 `pre-commit` 一处**，`git commit` 与 `git merge` 两条路径自动同时受益。

## 三道闸

**各看自己那一片的暂存文件**，互不拖累（只改前端却要等后端编译，很快就会有人 `--no-verify` 绕过，闸就废了）：

| 闸 | 触发条件 | 跑什么 | 失败时 |
|---|---|---|---|
| ① 基线文档不许链进 `.scratch/` | 暂存区含 `CLAUDE.md` / `总体架构与链路设计.md` / `.claude/rules/*` | grep 暂存内容里有没有指向具体 spec 的 `.scratch/<slug>/` | 拒绝提交 |
| ② 前端纯规则自检 | 暂存区含 `frontend/` | `frontend/scripts/` 下**所有** `check-*.mjs`（等价 `npm run check`） | 拒绝提交 |
| ③ 后端编译 | 暂存区含 `backend/` | `mvn -q -B -pl <从暂存路径推出的模块> -am test-compile` | 拒绝提交 |

### ① 为什么是 grep 而不是人眼

规则原文在 `docs/agents/issue-tracker.md`：「Production code and the baseline docs … must not link into `.scratch/`」。这是**机械规则**——固定文件里出现固定模式，grep 就能判。曾经真的违反过：三份基线文档都留着指向工单的链接，最后是评审子代理读出来的，机器本可以当场拦下。

- 判的是**暂存内容**（`git show :path`）而不是工作区文件：提交的是哪一版就查哪一版，不会因工作区另有改动误报，也不会因文件已删漏报。
- 模式要求 `.scratch/` 后紧跟字母或数字，所以 `CLAUDE.md` §Agent skills 里那句 `.scratch/<feature-slug>/` **不会命中**——那是登记"spec 放在哪"的模板，不是指向某个 spec 的引用。

### ② 前端自检

- **脚本清单是通配出来的**（`check-*.mjs`），不是写死的名单：新增一个自检脚本即自动受闸保护。写死名单时曾经发生过"新脚本静默绕过闸"——那正是这道闸存在的意义所在。
- 断言失败即**拒绝提交**，并把失败那几条原样打出来。
- 确认是误报才绕过：`git commit --no-verify`。

### ③ 后端编译：为什么不是 `mvn test`

`CLAUDE.md` §6 把「后端编译通过」列为**提交线**、把全量测试列为**功能完成后**的动作，两者不该混在一起——每次后端提交都跑 40–60s 全量测试，很快就没人肯提交了。而编译正是真漏过两次的那类失败（少传一个参数、少写一个右括号），`test-compile` 一秒就能抓住。实测：改 `chat` 模块约 **15s**。

- **模块是从暂存路径推出来的**（`backend/<模块>/...`），不是写死清单：新增模块自动受保护；`-am` 带上被依赖的模块（改了 `common` 就得重编下游）。
- hook 里给 Maven 设了 `-Dstdout.encoding=UTF-8`：这台机器控制台默认 GBK，javac 的中文错误会变成乱码，而乱码会让人下意识跳过错误段——闸拦住了却看不懂为什么，等于没拦。
- **编译过 ≠ 测试过**。功能完成后仍要自己跑 `cd backend && mvn -B test`（闸不替你做这个）。

## 绕不过去时要走哪条路

`git commit --no-verify` **只对 commit 有效**，merge 那条路径要用 `git merge --no-verify`（两者都同理：确认是误报才用）。

## 将来接 CI

若引入 CI，跑同两条命令（`cd frontend && npm run check`、`cd backend && mvn -B test`）即可——**不要另写第二份断言逻辑**。

