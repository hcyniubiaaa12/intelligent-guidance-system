# 14: 回执缩略图去掉左右方位字（并修掉工单 13 带坏的两处绑定）

**What to build:** 对话页「锁定回执」里那个 24×56 的缩略图，**不再画图下缘的「右 / 左」方位字**。

用户真机看到的：选完部位跳到对话页，小图底部那对字糊成一团。四倍放大看确实如此——24px 宽的图里那对 12px 的字什么都传达不了，只是噪点。方位信息由回执文案承担（「位置已锁定：左上肢」）；**覆盖层里照旧保留**——患者在那儿需要知道"图上的左右是哪一边"（图是观者视角）。

**顺手修两处回归**（工单 13 删 `side` / `readonly` 两个 prop 时带坏的，属静默失效）：`EmptyState.vue` 还在传 `:side="lockedSide"` 与 `readonly`，两个都成了无效绑定——缩略图**不再按方位区分左右半边**（选「左腿」会显示成两条腿都实心）。本票把它接到"每块一份"的新口径上：新增 `sidesOfLocations(locations)`（`declarationLocations` 的反向），回执用它把手里那串 `locations` 拆回「区域 → 方位」再点亮图。

**Blocked by:** 13

**Status:** resolved

- [x] 缩略图里不再渲染 `.p-body__sides`（0 组）
- [x] 覆盖层图下缘的方位字仍在（右 / 左）
- [x] 缩略图按**每块自己的**方位点亮（左腿 ⇒ 只实心左边那条、另一条虚框；两侧 ⇒ 两条都实心）
- [x] `sidesOfLocations` 与 `declarationLocations` 互为逆（往返断言钉住）
- [x] `npm run check` 全绿；真机探针两个视口全过

## Comments

实现完成（集成分支 `feat/body-map-interaction`）。

- 做法：`HumanBodyFigure` 加 `showSides`（默认 `true`，覆盖层不用改）——缩略图那边传 `false`。**不用父组件 `:deep` 去藏**：那是绕过组件接口，别人下次换个类名就静默失效。
- 回归修复：`EmptyState` 的 `:side`/`readonly` 换成 `:side-of="lockedSideOf"`（喂 `sidesOfLocations(locations)`），`lockedSide` 那个"取第一个前缀"的旧推导删掉。
- 验证：`npm run check` **153 条**（新增 ①b 段 7 条，含 `sidesOfLocations` ↔ `declarationLocations` 的**往返不变**断言）；真机探针 **41 条**，桌面 1440×900 与窄屏 430×900 各一遍——新增两条互为对照：覆盖层 `.p-body__sides` **仍在**、缩略图里 **0 组**。预览页（`body-map-preview.html` 的 `BodyMapPreview`）补了一个缩略图实例，让这条能在真机上被断言，而不是靠肉眼。
- 文档：`前端设计方案.md` §3.6 的回执条目补上缩略图与 `show-sides=false` 的说明。
