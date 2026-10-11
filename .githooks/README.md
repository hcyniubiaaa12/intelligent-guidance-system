# .githooks —— 随仓库分发的 git hooks

本目录被 git 跟踪（`.git/hooks/` 里的东西不会跟着仓库走，这里会）。目前只有一个 `pre-commit`。

## 启用（每台机器、每个克隆做一次）

```sh
git config core.hooksPath .githooks
```

它是**本机配置**，不会随克隆带过去——所以新克隆的人必须自己执行这一条。想确认是否已启用：

```sh
git config core.hooksPath   # 应输出 .githooks
```

## pre-commit 做什么

- **只在暂存区含 `frontend/` 时**跑 `frontend/scripts/check-body-map.mjs`（人体图纯规则自检，等价于 `npm run check`）。
  只改 `backend/` 的提交不触发——否则每次后端提交都被无关检查拖慢，很快就有人 `--no-verify` 绕过，闸就废了。
- 断言失败即**拒绝提交**，并把失败那几条原样打出来。
- 确认是误报才绕过：`git commit --no-verify`。

## 将来接 CI

若引入 CI，跑同一条命令（`cd frontend && npm run check`）即可——**不要另写第二份断言逻辑**。
