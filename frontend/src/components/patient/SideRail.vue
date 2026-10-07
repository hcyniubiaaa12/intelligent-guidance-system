<template>
  <aside
    class="p-chat__side"
    :class="{ 'is-open': mobileOpen, 'is-collapsed': isDesktop && sideHidden }"
    :style="isDesktop && !sideHidden ? { width: SIDE_W + 'px' } : null"
    @click.self="emit('close-mobile')"
  >
    <!-- 精简轨（只在桌面收起时渲染）：收起后只剩标识——它本身就是展开入口，右边再给一个展开图标 -->
    <div class="p-chat__rail">
      <button class="p-chat__railbrand" title="展开侧栏" aria-label="展开侧栏" @click="toggleSide">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <rect x="2.6" y="2.6" width="18.8" height="18.8" stroke="currentColor" stroke-width="1.7" />
          <path d="M6.9 8h10.2" stroke="currentColor" stroke-width="2.3" />
          <path d="M6.9 12h6.8" stroke="currentColor" stroke-width="2" opacity=".52" />
          <path d="M6.9 16h3.6" stroke="currentColor" stroke-width="1.7" opacity=".3" />
        </svg>
      </button>
      <span class="p-chat__railsep" />
      <button class="p-chat__iconbtn" title="展开侧栏" aria-label="展开侧栏" @click="toggleSide">
        <svg width="15" height="15" viewBox="0 0 14 14" aria-hidden="true"><rect x="1.2" y="2.2" width="11.6" height="9.6" fill="none" stroke="currentColor" stroke-width="1.2"/><path d="M5 2.2v9.6" stroke="currentColor" stroke-width="1.2"/></svg>
      </button>
      <!-- 收起后仍要能开新咨询——否则得先展开侧栏才够得到 -->
      <button class="p-chat__iconbtn" title="新 的 咨 询" aria-label="新 的 咨 询" @click="startNew">
        <svg width="15" height="15" viewBox="0 0 14 14" aria-hidden="true"><circle cx="7" cy="7" r="5.6" fill="none" stroke="currentColor" stroke-width="1.2"/><path d="M7 4.8v4.4M4.8 7h4.4" fill="none" stroke="currentColor" stroke-width="1.2"/></svg>
      </button>
    </div>

    <div class="p-chat__sidebox">
      <!-- 侧栏抬头：标识 ＋ 品牌名 ＋ 收起按钮；「智能导诊」从对话区顶栏挪到这里 -->
      <div class="p-chat__hd">
        <span class="p-chat__logo" aria-hidden="true">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <rect x="2.6" y="2.6" width="18.8" height="18.8" stroke="currentColor" stroke-width="1.7" />
            <path d="M6.9 8h10.2" stroke="currentColor" stroke-width="2.3" />
            <path d="M6.9 12h6.8" stroke="currentColor" stroke-width="2" opacity=".52" />
            <path d="M6.9 16h3.6" stroke="currentColor" stroke-width="1.7" opacity=".3" />
          </svg>
        </span>
        <span class="p-chat__brand">智能导诊<small>您的陪诊助手</small></span>
        <button class="p-chat__iconbtn p-chat__foldbtn" title="收起侧栏" aria-label="收起侧栏" @click="toggleSide">
          <svg width="15" height="15" viewBox="0 0 14 14" aria-hidden="true"><rect x="1.2" y="2.2" width="11.6" height="9.6" fill="none" stroke="currentColor" stroke-width="1.2"/><path d="M5 2.2v9.6" stroke="currentColor" stroke-width="1.2"/></svg>
        </button>
      </div>

      <!-- 新咨询放最上头：会话多了要能一眼够到 -->
      <button class="p-chat__new" @click="startNew">+ 新 的 咨 询</button>

      <div class="p-chat__sidehd">
        <!-- 就诊次数**跟着标签走**（`我 的 就 诊 · 7 次`），不另起一个 19px 的大数字：
             大数字会和下面 12.5px 的会话条目抢层级，而次数只是列表的量词 -->
        <div class="p-eyebrow p-chat__lb">我 的 就 诊 · <b>{{ countText }}</b></div>
        <div class="p-chat__tabs">
          <button :class="{ on: tab === 'all' }" @click="tab = 'all'">全 部 对 话</button>
          <button :class="{ on: tab === 'booked' }" @click="tab = 'booked'">挂 号 历 史</button>
        </div>
      </div>

      <div class="p-chat__list">
        <template v-for="day in shownDays" :key="day.date">
          <div class="p-day">
            <span>{{ shortDate(day.date) }}</span>
            <span class="n">{{ day.sessions.length }} 次{{ isCollapsed(day) ? ' · 已折叠' : '' }}</span>
          </div>
          <!-- 一行 = 会话 + 归档动作。动作是**兄弟** button（.p-i 自己是 button，里面不能再放 button），
               绝对定位贴在右侧，hover 才浮现——与对话区的复制按钮同一套语言 -->
          <div v-for="s in visibleSessions(day)" :key="s.id" class="p-irow">
            <button
              class="p-i"
              :class="{ on: s.id === activeSessionId, dim: !s.hasResult }"
              @click="openSession(s.id)"
            >
              <span class="p-i__x">{{ s.firstComplaint || '（无内容）' }}</span>
              <span class="p-i__q">{{ s.questionCount }} 问</span>
              <span class="p-i__d">{{ hhmm(s.startedAt) }}</span>
            </button>
            <button class="p-irow__act" title="收进归档（不是删除，随时可取回）" @click="setArchived(s, true)">归 档</button>
          </div>
          <button v-if="isCollapsed(day)" class="p-fold" @click="expandDay(day.date)">
            展 开 该 天 全 部 {{ day.sessions.length }} 次
          </button>
          <button v-else-if="expanded.has(day.date)" class="p-fold" @click="collapseDay(day.date)">
            收 起 该 天
          </button>
        </template>
        <p v-if="!shownDays.length" class="p-chat__nores">
          {{ archivedTotal ? '都收进归档了' : tab === 'booked' ? '还没有挂过号' : '还没有就诊记录' }}
        </p>

        <!-- 归档区（收纳）：**按天分组**（口径=会话开始那天），条目仍能点开只读回放，只是不在主区 -->
        <template v-if="archivedDaysShown.length">
          <button class="p-fold" @click="archOpen = !archOpen">
            {{ archOpen ? '收 起 归 档' : `已 归 档 · ${archivedTotal} 次` }}
          </button>
          <template v-if="archOpen">
            <template v-for="day in archivedDaysShown" :key="day.date">
              <div class="p-day">
                <span>{{ shortDate(day.date) }}</span>
                <span class="n">{{ day.sessions.length }} 次</span>
              </div>
              <div v-for="s in day.sessions" :key="s.id" class="p-irow is-arch">
                <button
                  class="p-i"
                  :class="{ on: s.id === activeSessionId, dim: !s.hasResult }"
                  @click="openSession(s.id)"
                >
                  <span class="p-i__x">{{ s.firstComplaint || '（无内容）' }}</span>
                  <span class="p-i__q">{{ s.questionCount }} 问</span>
                  <span class="p-i__d">{{ hhmm(s.startedAt) }}</span>
                </button>
                <button class="p-irow__act" title="取回主区" @click="setArchived(s, false)">取 回</button>
              </div>
            </template>
          </template>
        </template>
      </div>

      <!-- 底部用户区：一张压在侧栏底上的白卡，与选中条目同一层次。
           昵称在上（它是"看挂号历史"的入口）、「健 康 档 案」在下当副标，
           退出收在最右的小字里——三件事都在，但不与列表的条目抢眼 -->
      <div class="p-chat__user">
        <span class="p-chat__uava" aria-hidden="true">{{ initial }}</span>
        <span class="p-chat__uinfo">
          <button class="p-chat__unick" title="看我的挂号历史" @click="emit('open-booked')">{{ name }}</button>
          <button class="p-chat__uprof" title="填写健康档案（选填）" @click="emit('open-profile')">健 康 档 案</button>
        </span>
        <button class="p-chat__uout" title="退出登录" @click="emit('logout')">退 出</button>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { listSessions, archiveSession } from '../../api/records'

/**
 * 左栏「我的就诊」：会话目录 + 底部用户区。
 *
 * **会话列表的状态归本组件管**——它只服务于这个目录（新会话落库、挂号状态变化、归档…），
 * 留在页面里只会让 Chat.vue 变成第二个"什么都知道一点"的地方。对外只留三样输入与
 * 几样动作，不暴露任何列表字段：
 *   输入  props  `activeSessionId`（哪条是当前看的）、`nickname`、`mobileOpen`（手机覆盖层）
 *   动作  emits  `select` / `new` / `close-mobile` / `open-booked` / `open-profile` / `logout` / `archive-change`
 *         两个方法 `reload()`（页面在一轮导诊结束后刷新）、`showBooked()`（顶栏点昵称时切 tab）
 *
 * 窄屏下它是**浮层语言的唯一例外**：不套 `.p-overlay`——那不是"浮层内容"，而是同一块
 * 侧栏在窄屏下的形态（底色仍是 `--rail`、内容一字未变），只在外面加一层 `--mask`。
 */
const props = defineProps({
  /** 正在看的是哪一条：看历史就是历史的，实时对话就是本轮的 */
  activeSessionId: { type: [String, Number], default: null },
  nickname: { type: String, default: '' },
  /** 手机端这条是不是展开着（覆盖层） */
  mobileOpen: { type: Boolean, default: false }
})
const emit = defineEmits([
  'select', 'new', 'close-mobile', 'open-profile', 'open-booked', 'logout', 'archive-change'
])

// 一天内会话超过这个数就折叠；折叠时只露最新这几条
const FOLD_OVER = 10
const FOLD_SHOW = 2
const SIDE_HIDDEN_KEY = 'p-chat-side-hidden'
const SIDE_W = 228

const days = ref([])
const archivedDays = ref([])
const total = ref(0)
const booked = ref(0)
const tab = ref('all')
const expanded = ref(new Set())
const archOpen = ref(false)

const sideHidden = ref(localStorage.getItem(SIDE_HIDDEN_KEY) === '1')
// 只有桌面才收起侧栏；手机侧栏是覆盖层，开关走的是 mobileOpen
const isDesktop = ref(window.matchMedia('(min-width: 768px)').matches)

/** 昵称为空时给个兜底：空白卡比"我的就诊"更像出错了 */
const name = computed(() => props.nickname || '我 的 就 诊')
const initial = computed(() => (props.nickname || '我').slice(0, 1))
/** 次数跟着当前 tab 走：全部对话报总次数，挂号历史报挂号次数 */
const countText = computed(() => (tab.value === 'all' ? `${total.value} 次` : `${booked.value} 次挂号`))

/** 当前 tab 口径下要展示的天：全部对话 = 原样；挂号历史 = 只留挂过号的会话 */
const shownDays = computed(() => filterByBooked(days.value))

/** 归档区跟着当前 tab 的过滤口径走，结构同主区（按天） */
const archivedDaysShown = computed(() => filterByBooked(archivedDays.value))

/** 归档区总条数（折起时的「N 次」标签用） */
const archivedTotal = computed(() => archivedDaysShown.value.reduce((sum, d) => sum + d.sessions.length, 0))

function filterByBooked(list) {
  if (tab.value === 'all') return list
  return list
    .map((d) => ({ ...d, sessions: d.sessions.filter((s) => s.booked) }))
    .filter((d) => d.sessions.length > 0)
}

async function loadSessions() {
  try {
    const data = await listSessions()
    days.value = data.days || []
    archivedDays.value = data.archivedDays || []
    total.value = data.totalSessions || 0
    booked.value = data.totalBooked || 0
  } catch (e) {
    // 侧栏拉不到不该挡住发消息；接口真挂了别处也会报错
    console.error('[rail] 会话列表拉取失败', e)
  }
}

/**
 * 归档 / 取回一条会话。归档是收纳不是删除：归档后仍能点开只读回放，随时可「取回」。
 *
 * 两个细节：
 * ① 归档后自动展开归档区一次——让患者看见"它去哪了"，否则像凭空消失。
 * ② 归档的若正是当前正在看的那条，**把视图一起收掉**：侧栏不知道对话区在看什么，
 *    所以只 emit `archive-change`，由页面处理（见 Chat.vue 的 onArchiveChange）。
 */
async function setArchived(session, next) {
  try {
    await archiveSession(session.id, next)
  } catch (e) {
    console.error('[rail] 归档操作失败', e)
    return
  }
  if (next) archOpen.value = true
  else if (archivedTotal.value <= 1) archOpen.value = false
  emit('archive-change', { session, archived: next })
  await loadSessions()
}

function openSession(id) {
  emit('select', id)
}

function startNew() {
  emit('new')
}

function toggleSide() {
  sideHidden.value = !sideHidden.value
  localStorage.setItem(SIDE_HIDDEN_KEY, sideHidden.value ? '1' : '0')
}

function isCollapsed(day) {
  return day.sessions.length > FOLD_OVER && !expanded.value.has(day.date)
}

function visibleSessions(day) {
  return isCollapsed(day) ? day.sessions.slice(0, FOLD_SHOW) : day.sessions
}

function expandDay(date) {
  expanded.value = new Set([...expanded.value, date])
}

function collapseDay(date) {
  const next = new Set(expanded.value)
  next.delete(date)
  expanded.value = next
}

function hhmm(at) {
  return at ? String(at).slice(11, 16) : ''
}

function shortDate(date) {
  return String(date).slice(5)
}

let desktopMQ = null
const onDesktopChange = (e) => { isDesktop.value = e.matches }

onMounted(() => {
  desktopMQ = window.matchMedia('(min-width: 768px)')
  desktopMQ.addEventListener?.('change', onDesktopChange)
  loadSessions()
})
onBeforeUnmount(() => desktopMQ?.removeEventListener?.('change', onDesktopChange))

// 页面上的一轮导诊结束后要刷新（新会话 / 提问数 / 挂号状态都靠它）
defineExpose({ reload: loadSessions, showBooked: () => { tab.value = 'booked' } })
</script>

<style scoped>
/* ---------- 左：会话目录 ----------
   `--rail` 比舞台 `--stage` 暗一档：「我的就诊」与「对话」因此自然分成两块，
   两栏之间**一条线都不画**（画线会把一整块暖沙隔成两间病房） */
.p-chat__side { display: none; }
.p-chat__sidebox {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--rail);
}
/* 侧栏抬头：标识 ＋ 品牌名 ＋ 收起按钮（收起按钮靠 margin-left:auto 推到最右） */
.p-chat__hd { flex: none; display: flex; align-items: center; gap: 8px; padding: 16px 12px 12px; }
/* 标识里的三条横线 = 推荐卡上那组并列候选条，与产品最强的记忆点同源 */
.p-chat__logo {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: var(--r-xs);
  background: var(--leaf);
  color: var(--on-accent);
}
.p-chat__brand {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 13.5px;
  font-weight: 600;
  letter-spacing: .04em;
  color: var(--ink);
}
.p-chat__brand small {
  display: block;
  margin-top: 4px;
  font-size: 10px;
  font-weight: 400;
  letter-spacing: .06em;
  color: var(--ink-3);
}
.p-chat__new {
  flex: none;
  margin: 4px 12px 12px;
  min-height: 44px; /* 触控目标 ≥ 44px */
  border: 0;
  border-radius: var(--r-sm);
  background: var(--leaf);
  color: var(--on-accent);
  cursor: pointer;
  font-family: var(--sans);
  font-size: 12.5px;
  font-weight: 600;
  letter-spacing: .1em;
  box-shadow: var(--sh-primary);
  transition: background .18s ease;
}
.p-chat__new:hover { background: var(--leaf-deep); }

.p-chat__sidehd { padding: 12px 12px 0; border-bottom: 1px solid var(--line); }
/* 就诊次数：只是列表的量词，跟在标签后面，颜色比会话条目还弱一档 */
.p-chat__lb b { color: var(--leaf-deep); font-weight: 600; letter-spacing: .04em; }
.p-chat__tabs { display: flex; gap: 8px; margin-top: 12px; padding-bottom: 12px; }
.p-chat__tabs button {
  flex: 1;
  min-height: 44px; /* 触控目标 ≥ 44px */
  border: none;
  border-radius: var(--r-full);
  background: none;
  cursor: pointer;
  font-family: var(--sans);
  font-size: 11.5px;
  letter-spacing: .1em;
  color: var(--ink-2);
  transition: background .18s ease, color .18s ease, box-shadow .18s ease;
}
.p-chat__tabs button:hover { color: var(--leaf-deep); }
.p-chat__tabs button.on {
  background: var(--surface);
  color: var(--leaf-deep);
  font-weight: 600;
  box-shadow: var(--sh-raised);
}
.p-chat__list { flex: 1; min-height: 0; overflow-y: auto; padding: 0 8px; }
.p-chat__nores {
  padding: 48px 12px;
  font-size: 11.5px;
  letter-spacing: .06em;
  color: var(--ink-3);
  text-align: center;
}

/* ---------- 底部用户区 ----------
   一张压在侧栏底上的白卡，与选中条目同一层次。里面三件事按重要性排成两行：
   昵称（12.5px 600，"看挂号历史"的入口）在上、「健 康 档 案」（10px，作为副标）在下，
   退出收在右端的小字——**静息态不画下划线**，靠 hover 变色给可点提示，
   否则四条带下划线的文字并排会把底卡变成一堵字墙。

   **那两行不给高度、只给行高**：44px 是"手指够得着的地方"的下限，可这两行是**竖着叠
   起来**的——各撑到 44px 底卡就变成 116px（实测），比一条会话条目高两倍，整块侧栏的
   节奏断了；草图那张卡只有 51px。所以按输入方式分档：鼠标端贴着文字高度（`min-height: 0`
   + `line-height: 1.3`，实测底卡 **212×57**），触屏端才补到 44px。与共享件 `.p-act__btn`
   同一口径，两处都登记在设计文档 §5 的豁免清单里。 */
.p-chat__user {
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 8px 12px;
  padding: 12px;
  border-radius: var(--r-sm);
  background: var(--surface);
}
/* 昵称首字：杏色底，与"陪您走完流程的人"这层语气一致，比一行纯文字更有归属感 */
.p-chat__uava {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--r-full);
  background: var(--apricot-soft);
  color: var(--apricot-deep);
  font-size: 12.5px;
  font-weight: 600;
}
.p-chat__uinfo {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}
/* 两行之间的 `gap` 只是底线的一半——真正把两行撑开的是**行高**：
   `line-height: 1.3` 之下两行的半行距各只有 ~2px，字与字之间看着是连着的；
   若跟着全局的 1.85 走，半行距各 4~5px，加上 gap 就成了"两行之间隔一条带"。 */
.p-chat__unick,
.p-chat__uprof,
.p-chat__uout {
  max-width: 100%;
  min-height: 0;
  padding: 0;
  border: none;
  background: none;
  cursor: pointer;
  font-family: var(--sans);
  line-height: 1.3;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: color .18s ease;
}
/* 触屏没有 hover：这两行这时候是手指要按的真控件，命中区补到 44px */
@media (hover: none) {
  .p-chat__unick,
  .p-chat__uprof,
  .p-chat__uout { min-height: 44px; }
}
/* 昵称用 `--ink-2` 而不是 `--ink`：它是**身份标识**，不是当前要读的内容——
   底卡已经因为有白底和主色头像而被凸显了，再加一行近黑的名字会跟上面的会话条目
   抢"这里是要看的东西"这份注意力（`.p-i__x` 才是正文色）。
   层级靠 12.5px/600 与下面 10px/`--ink-3` 的字号差撑。 */
.p-chat__unick {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-2);
}
.p-chat__unick:hover { color: var(--leaf-deep); text-decoration: underline; text-underline-offset: 3px; }
.p-chat__uprof { font-size: 10px; letter-spacing: .1em; color: var(--ink-3); }
.p-chat__uprof:hover { color: var(--leaf-deep); }
.p-chat__uout { flex: none; font-size: 10px; letter-spacing: .1em; color: var(--ink-3); }
.p-chat__uout:hover { color: var(--alert); }

/* ---------- 目录条目：天头 + 条目 + 折叠条。侧栏底是 `--rail`，所以选中行用**实白卡片**
   ——「压上去」的层次靠这层明度关系出来，不是靠加边框 ---------- */
.p-day {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 12px 8px 4px;
  font-family: var(--sans);
  font-size: 10px;
  letter-spacing: .14em;
  color: var(--ink-3);
}
.p-day .n { letter-spacing: .06em; }
.p-i {
  width: 100%;
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 12px;
  border: none;
  border-radius: var(--r-sm);
  background: none;
  cursor: pointer;
  text-align: left;
  transition: background .18s ease, box-shadow .18s ease;
}
.p-i:hover { background: var(--hover-rail); }
.p-i.on { background: var(--surface); box-shadow: var(--sh-raised); }
.p-i__x {
  flex: 1;
  min-width: 0;
  font-size: 12.5px;
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.p-i.dim .p-i__x { color: var(--ink-2); }
.p-i.on .p-i__x { color: var(--leaf-deep); font-weight: 600; }
.p-i__q,
.p-i__d {
  flex: none;
  font-family: var(--sans);
  font-size: 10px;
  color: var(--ink-3);
}
.p-i__q { letter-spacing: .06em; }
.p-fold {
  width: 100%;
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 12px;
  border: none;
  border-radius: var(--r-sm);
  background: none;
  cursor: pointer;
  font-family: var(--sans);
  font-size: 10.5px;
  letter-spacing: .1em;
  color: var(--leaf-deep);
}
.p-fold:hover { text-decoration: underline; }

/* ---------- 归档：入口 hover 才浮现（与对话区复制按钮同一套语言），收纳区折在列表末尾 ---------- */
.p-irow { position: relative; }
.p-irow__act {
  position: absolute;
  right: 12px;
  top: 50%;
  transform: translateY(-50%);
  padding: 12px 4px;
  border: none;
  background: none;
  cursor: pointer;
  font-family: var(--sans);
  font-size: 10px;
  letter-spacing: .1em;
  color: var(--leaf-deep);
  opacity: 0;
  transition: opacity .18s ease;
}
.p-irow:hover .p-irow__act,
.p-irow__act:focus-visible { opacity: 1; }
/* hover 时把时间淡掉：归档动作就压在那个位置，两条文字会叠 */
.p-i__d { transition: opacity .18s ease; }
.p-irow:hover .p-i__d { opacity: 0; }
/* 触屏没有 hover：常显动作，并让出时间的位置 */
@media (hover: none) {
  .p-irow__act { opacity: 1; }
  .p-irow .p-i__d { opacity: 0; }
}
.p-irow.is-arch .p-i__x { color: var(--ink-2); }

/* ---------- 侧栏折叠图标与精简轨（桌面） ---------- */
.p-chat__iconbtn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: none;
  border-radius: var(--r-xs);
  background: none;
  padding: 0;
  cursor: pointer;
  color: var(--ink-2);
  transition: color .18s ease, background .18s ease;
}
.p-chat__iconbtn:hover { color: var(--leaf-deep); background: var(--surface); }
/* 收起按钮在侧栏抬头里（桌面）；精简轨：收起态才出现，与展开态的 sidebox 互换 */
.p-chat__foldbtn { display: none; }
.p-chat__rail { display: none; }
@media (min-width: 768px) {
  /* 桌面常驻：这一条是"侧栏在宽屏下出现"的唯一开关，缺了它整条侧栏会被上面的
     display:none 一直藏着（页面上表现为只剩对话区，左边空一块） */
  .p-chat__side {
    display: block;
    flex: none;
    /* 收起/展开平滑过渡；宽度 0 时裁掉侧栏内容 */
    overflow: hidden;
    transition: width .2s ease;
  }
  .p-chat__foldbtn { display: inline-flex; margin-left: auto; }
  /* 收起后侧栏只剩横向一条：标识（点它展开）＋ 展开图标 ＋ 新建。
     宽度写**定值**而不是 fit-content——`fit-content` 不参与 width 插值，收起会「啪」地
     跳过去，0.2s 的过渡等于白写（2026-09-20 实测采样只有 [248, 127] 两帧）。
     min-width 兜底：图标有增减时轨不会挤坏。123 = 24(内边距) + 26(标识) + 4 + 1(分隔) + 4 + 30 + 4 + 30。 */
  .p-chat__side.is-collapsed { width: 123px; min-width: fit-content; }
  .p-chat__side.is-collapsed .p-chat__sidebox { display: none; }
  .p-chat__side.is-collapsed .p-chat__rail {
    display: flex;
    flex-direction: row;
    align-items: center;
    gap: 4px;
    padding: 12px;
    color: var(--leaf-deep);
    /* 贴在顶部，不要撑满高度居中 */
    height: auto;
  }
  /* 收起态的标识：它本身就是展开入口 */
  .p-chat__railbrand {
    flex: none;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 26px;
    height: 26px;
    border: none;
    background: none;
    padding: 0;
    cursor: pointer;
    color: var(--leaf-deep);
    transition: opacity .18s ease;
  }
  .p-chat__railbrand:hover { opacity: .7; }
  .p-chat__railsep { flex: none; width: 1px; height: 16px; background: var(--line-2); }
}

/* ---------- 手机 <768px：会话目录是覆盖层（§3.7 的唯一例外）---------- */
/* 它不套 `.p-overlay`——那不是"浮层内容"，而是同一块侧栏在窄屏下的形态：
   底色仍是 `--rail`、内容一字未变，只是套了一层 `--mask` 遮罩。*/
@media (max-width: 767px) {
  .p-chat__side {
    display: block;
    position: absolute;
    inset: 0;
    z-index: 20;
    background: var(--mask);
    opacity: 0;
    pointer-events: none;
    transition: opacity .18s ease;
  }
  .p-chat__side.is-open { opacity: 1; pointer-events: auto; }
  .p-chat__sidebox {
    width: 82%;
    max-width: 320px;
    border-radius: 0 var(--r-lg) var(--r-lg) 0;
    box-shadow: var(--sh-overlay);
  }
}
</style>