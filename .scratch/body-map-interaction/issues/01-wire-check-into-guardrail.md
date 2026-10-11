# 01: 把 `npm run check` 接进硬闸

**What to build:** 让 `npm run check`（在 `frontend/` 下跑）从"记得跑才跑"变成"跑了才准提交"。断言脚本已经常驻（`frontend/scripts/check-body-map.mjs`，56 条），但它现在没有任何东西会**自动**触发它——一个没人自动跑的守卫，在没人记得跑的那天就等于不存在。

仓库现状：**没有 CI**（无 `.github/workflows`），**没有 pre-commit hook**（`.git/hooks` 里只有 sample）。所以这一步唯一能落地的是**本地 hook**；将来若引入 CI，跑同一条命令、不另写第二份断言逻辑。

hook 必须是**被 git 跟踪的**——放在 `.git/hooks/` 里只存在于本机，别人克隆下来就没有这道闸（这个仓库是要展示的，闸得跟着仓库走）。做法：hook 放进一个被跟踪的目录，用 `core.hooksPath` 指过去；并在 `CLAUDE.md` 的校验一节补一句"改完前端跑 `npm run check`"。

**Blocked by:** None（可立即开始）

**Status:** ready-for-agent

- [ ] 提交前自动跑 `npm run check`；断言失败**拒绝提交**，并把那条失败原样显示出来
- [ ] 只改 `backend/` 的提交**不触发**前端检查——否则每次后端提交都被无关的检查拖慢，很快就会有人 `--no-verify` 绕过，闸就废了
- [ ] hook 随仓库分发（不是只在本机 `.git/hooks/`），并有一段说明告诉新克隆的人怎么启用
- [ ] 两条路径都手工验过：故意破坏一条断言 → 提交被拒且报出该条；还原 → 提交通过
- [ ] 没有引入新的依赖（本仓库规定不额外引入中间件/依赖）
