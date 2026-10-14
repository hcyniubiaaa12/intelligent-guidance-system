# 01: 首轮事实收到后端——`session` 事件带 `firstTurn`，前端不再预测会话状态

**What to build:**

1. **后端**：`SseEvents.SessionEvent` 增 `boolean firstTurn`（语义：**本轮是该会话的首条主诉**，也就是部位声明会被采纳的那一轮）。`firstTurn` 在 `ChatService.handle()` 里**只算一次**，同时喂 `resolveParts(...)` 与事件；`resolveParts` 改成吃 boolean。
2. **前端**：拆掉 `utils/sse.js` 里用 `sessionId` 预测的那半句——请求体组装改成**无依赖纯函数** `utils/chatBody.js`（`buildChatBody`），**接不接纳交给后端裁决**。
3. **前端**：`Chat.vue` 自己维护"这份声明还没花掉"（`partsSubmitted`），发送时按 `partStage==='locked' && !partsSubmitted` 决定带不带；`onSession` 里就地消费 `firstTurn`。

**已定的规则**（每条都有据，见 spec）：

1. **判定只算一次**。`isFirstTurn` 的调用点从两处收成一处——本 bug 的成因就是"同一个判定在两处各自算、而两处用的事实不同"（前端看 `sessionId`、后端看状态机）。`resolveParts` 收到的是**算好的那个值**。
2. **`session` 事件管不了本轮请求**（服务端→客户端的顺序决定的）。所以别指望"事件带字段"能修本轮——修本轮靠的是**拆掉前端预测**；事件字段管的是"让前端知道后端刚才把会话翻页了"，从而不再靠 `done.hasResult` 与"组件重新挂载"两个巧合对齐 UI。
3. **前端只报自己的事实**：`partStage==='locked'`（患者完成了声明流程，含「说不清在哪儿」出口）= 前端知道的事；**后端会不会采纳**是后端的事。两者不互相猜。
4. **收下声明不等于要重新弹门禁**：`firstTurn=true` 且本轮**患者没走过声明流程**（`partStage!=='locked'`）时，才把门禁摆回 `awaiting`——覆盖"后端因 CLOSED / 换会话开了新就诊，而本地还以为在续聊"那条路径；患者刚提交过声明的那一轮**不许**回等待态。
5. **字段缺失要能用**：前端不做硬依赖（老后端没这个字段时，退化为"有声明就带"，行为正确）。
6. **不动的东西**：追问 / 结论 / 敏感词 / 检索 / 落库行为；`parts` 进模型上下文的规则；SSE 八事件的名字与顺序。

**验收**

- [x] `SessionEvent(sessionId, firstTurn)`；`isFirstTurn` 只在 `handle()` 算一次，`resolveParts(boolean, List)` 不再碰 session
- [x] 首轮判定进 INFO 日志（与既有的"会话新建/续聊"同一行：`… 首轮=true`）
- [x] 后端既有 20 条 `ChatServiceTest` 全绿（其中「已出结论的旧会话再带声明 → 开新会话、声明保留」正是本 bug 的服务端侧事实）
- [x] 请求体规则抽成 `utils/chatBody.js` 纯函数，`sse.js` 只做调用；**不再出现 `sessionId` 参与 parts 判定**
- [x] 常驻自检 `scripts/check-chat-body.mjs`（8 条），已接进 `npm run check`
- [x] 硬闸 `.githooks/pre-commit` 改为逐个跑 `scripts/check-*.mjs`（原先只写死一个脚本名，新脚本将不受闸保护）
- [x] `Chat.vue`：`partsSubmitted` 生命周期（声明完成置 false、`onSession` 确认送达后置 true、`startNew`/`rechooseParts`/结论归零）；`firstTurn && partStage!=='locked'` → 回 `awaiting`
- [x] `npm run check` 通过（人体图 153 + 请求体 8）；前端 build 通过
- [x] 《总体架构与链路设计.md》链路 A 的 `session` 事件载荷同步

**Blocked by:** 无

**Status:** resolved

## Comments

**发现一（改写了方案）**：最初设想是"`session` 事件带状态、前端据此决定要不要带 parts"，动手时才发现**顺序上不可能**——事件在请求之后才回来。真正修这条 bug 的是**拆掉前端的预测**；事件字段的角色是"暴露后端判定 + 兜底 UI 对齐"。所以本票比原估计更小：**没有协议破坏性改动**，只是一处加法。

**发现二（改了初版验收）**：初版验收写的是"非首轮带声明 → 事件 `firstTurn=false`"——**这条无法在单测里断言**：`ChatServiceTest` 的注释早就写明"断言落在落库副作用而不是 SSE 事件名（`stream()` 内部自建 SseEmitter，无注入点）"，而事件载荷在测试里不可观察（真去反射 Spring 的 `SseEventBuilder` 属于测实现细节）。改为：

- **一致性由结构保证**：一个局部变量喂两个消费者 ⇒ 编译期就不可能分叉，不需要（也不该有）测试；
- **可观察的那一半**由既有测试钉住（首轮采纳 / 非首轮丢弃 / 已出结论后开新会话仍采纳）；
- **线级载荷**留给真机（`Progress.md` 下一步的手工验证点：出结论后同页继续 → 选部位 → 发送，日志应是「会话新建 … 首轮=true」且**不出现**「部位声明丢弃」）。

**发现三（新增了本票没写的东西）**：为了让"带不带声明"这条规则**能被常驻自检钉住**，把请求体组装从 `sse.js` 抽成纯函数 `utils/chatBody.js`（`sse.js` 依赖 router/pinia，纯 node 导不进来）。**红绿各跑了一次**证明这道闸是真的：把旧写法（`sessionId || !parts?.length ? {} : { parts }`）放回去 → 自检立刻 2 条红（失败的正是那两条"带着 sessionId 不许吞声明"），改回来 → 8/8 绿。

**代码审查（标准轴 + 规格轴）后补的三处**：

- **`isFirstTurn` 还有第二个调用点**：`ruleGate(...)` 也在用它（"首轮才叠加内容门槛"），验收里写的"只在 `handle()` 算一次"当时**不成立**。已把算好的值传进去（`ruleGate(boolean firstTurn, …)`），现在全类只调用 `isFirstTurn` 一次。**教训：这类"收口"必须 grep 全类，不能只改自己看得见的那一处。**
- **归档当前会话这条路径漏了状态归零**（审查发现，是原 bug 的**另一个入口**）：`onArchiveChange` 里的 `chat.reset()` 只清了 sessionId，`partStage` 仍 `locked`、`partsSubmitted` 仍 `true` ⇒ 门禁不拦、声明又带不出去，新就诊等于没有位置声明。已按 `startNew()` 同一口径一起归零。**这类"同一状态要在 N 处同步"正是本链条反复踩的形状**——所以它是结构性隐患，不是手滑。
- **硬闸的"逐个跑"名不副实**：第一版把脚本名写死在 `checks="check-body-map.mjs check-chat-body.mjs"` 里，下一个新脚本照样静默绕过 —— **正是这次要修的那个缺陷本身**。已改成通配 `scripts/check-*.mjs`，`.githooks/README.md` 同步改。
