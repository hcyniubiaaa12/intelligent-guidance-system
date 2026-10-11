# 01: 把 `npm run check` 接进硬闸

**What to build:** 让 `npm run check`（在 `frontend/` 下跑）从"记得跑才跑"变成"跑了才准提交"。断言脚本已经常驻（`frontend/scripts/check-body-map.mjs`，56 条），但它现在没有任何东西会**自动**触发它——一个没人自动跑的守卫，在没人记得跑的那天就等于不存在。

仓库现状：**没有 CI**（无 `.github/workflows`），**没有 pre-commit hook**（`.git/hooks` 里只有 sample）。所以这一步唯一能落地的是**本地 hook**；将来若引入 CI，跑同一条命令、不另写第二份断言逻辑。

hook 必须是**被 git 跟踪的**——放在 `.git/hooks/` 里只存在于本机，别人克隆下来就没有这道闸（这个仓库是要展示的，闸得跟着仓库走）。做法：hook 放进一个被跟踪的目录，用 `core.hooksPath` 指过去；并在 `CLAUDE.md` 的校验一节补一句"改完前端跑 `npm run check`"。

**Blocked by:** None（可立即开始）

**Status:** resolved

- [x] 提交前自动跑 `npm run check`；断言失败**拒绝提交**，并把那条失败原样显示出来
- [x] 只改 `backend/` 的提交**不触发**前端检查——否则每次后端提交都被无关的检查拖慢，很快就会有人 `--no-verify` 绕过，闸就废了
- [x] hook 随仓库分发（不是只在本机 `.git/hooks/`），并有一段说明告诉新克隆的人怎么启用
- [x] 两条路径都手工验过：故意破坏一条断言 → 提交被拒且报出该条；还原 → 提交通过
- [x] 没有引入新的依赖（本仓库规定不额外引入中间件/依赖）

## Comments

实现完成（集成分支 `feat/body-map-interaction`）。

- 提交：`7b748a5` 把 `npm run check` 接进 pre-commit 硬闸；`7718a5c` 给 `.githooks/*` 钉死 LF（`autocrlf=true` 的机器会把 hook 检出成 CRLF，shebang 坏掉、hook 直接跑不起来）；**评审修复 `5fa19d4` 补上执行位**——此前 index 里是 `100644`，`core.fileMode=true` 的克隆上 git 会"hook was ignored because it's not set as executable"**静默跳过**，闸等于不存在。
- 两条路径都手工验过：① 故意改坏 `bodyMap.js` 的 `DEFAULT_FEELING` → `git commit` 被拒、两条失败断言原样打出；② 同一工作区里前端仍是坏的，但只暂存 `.gitattributes` 的提交照常通过——证明触发判据是**暂存区是否含 `frontend/`**，不是工作区。
- hook 随仓库分发：`.githooks/` 被跟踪，启用方式写在 `.githooks/README.md`，并在 `CLAUDE.md` 的校验一节补了一句。
- 未引入新依赖：hook 只调既有的那一条命令（`node scripts/check-body-map.mjs`），没有第二份断言逻辑。
