<template>
  <section class="p-mem" aria-label="我记住的">
    <div class="p-mem__head">
      <span class="p-mem__title">我记住的</span>
      <span class="p-mem__hint">自动记下的，每条都带着您的原话</span>
    </div>

    <!-- 三态：加载中 / 取回 / 失败。失败给「重试」而不是留一片空白——
         「取不到」与「没有」是两件事，写成一个字都看不见会让人以为记忆丢了。 -->
    <p v-if="state === 'loading'" class="p-mem__state">正在加载…</p>

    <div v-else-if="state === 'failed'" class="p-mem__failure">
      <p class="p-mem__state">记忆加载失败，请重试</p>
      <button type="button" class="p-mem__retry" @click="load">重试</button>
    </div>

    <template v-else>
      <p v-if="!memoryEnabled" class="p-mem__paused">
        自动记忆已暂停——旧记忆还留着，只是暂时不参与回答。
      </p>

      <p v-if="isEmpty" class="p-mem__empty">
        还没有记住什么。聊几句之后，我会把您的偏好和长期情况记在这里。
      </p>

      <template v-else>
        <div v-if="preferences.length" class="p-mem__group">
          <div class="p-mem__ghead">
            <span class="p-mem__glabel">偏好</span>
            <span class="p-mem__quota">{{ preferences.length }} / {{ limits.preferenceMax }}</span>
          </div>
          <article v-for="item in preferences" :key="item.id" class="p-mem__item">
            <p class="p-mem__kv">
              <span class="p-mem__key">{{ item.key }}</span>：<span class="p-mem__val">{{ item.value }}</span>
            </p>
            <p v-if="item.sourceQuote" class="p-mem__quote">{{ item.sourceQuote }}</p>
            <p class="p-mem__time">{{ formatTime(item.observedAt) }}</p>
          </article>
        </div>

        <div v-if="backgrounds.length" class="p-mem__group">
          <div class="p-mem__ghead">
            <span class="p-mem__glabel">长期背景</span>
            <span class="p-mem__quota">{{ backgrounds.length }} / {{ limits.backgroundMax }}</span>
          </div>
          <article v-for="item in backgrounds" :key="item.id" class="p-mem__item">
            <p class="p-mem__kv">
              <span class="p-mem__key">{{ item.key }}</span>：<span class="p-mem__val">{{ item.value }}</span>
            </p>
            <p v-if="item.sourceQuote" class="p-mem__quote">{{ item.sourceQuote }}</p>
            <p class="p-mem__time">{{ formatTime(item.observedAt) }}</p>
          </article>
        </div>
      </template>
    </template>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { getMemory } from '../../api/memory'

/**
 * 「我记住的」：长期记忆只读列表（偏好 + 长期背景）。
 *
 * 本工单只读：每条显示键、值、患者原话出处与观察时间；编辑 / 删除 / 开关在后续工单接。
 * **每次挂载都重拉**（浮层一关就卸载、一开就重新挂载），不缓存——记忆可能刚被抽取写入。
 *
 * 三态是硬要求：loading / ready / failed。失败给「重试」，绝不把「取不到」渲染成空态
 * ——那会让患者以为系统把他的记忆弄丢了。
 */
const state = ref('loading')
const memoryEnabled = ref(true)
const preferences = ref([])
const backgrounds = ref([])
// 上限来自后端受管参数；这里的默认值只在响应到达前兜底
const limits = ref({ preferenceMax: 5, backgroundMax: 10 })

const isEmpty = computed(() => preferences.value.length === 0 && backgrounds.value.length === 0)

onMounted(load)

async function load() {
  state.value = 'loading'
  try {
    const data = await getMemory()
    memoryEnabled.value = data.memoryEnabled !== false
    preferences.value = data.preferences || []
    backgrounds.value = data.backgrounds || []
    limits.value = {
      preferenceMax: data.limits?.preferenceMax ?? 5,
      backgroundMax: data.limits?.backgroundMax ?? 10
    }
    state.value = 'ready'
  } catch (e) {
    console.error('[memory] 记忆加载失败', e)
    state.value = 'failed'
  }
}

/**
 * 观察时间本地化：后端给 ISO 本地时间（无时区后缀），按本地时间解析即可。
 * 空值 / 坏值不显示「Invalid Date」，返回空串让整行消失。
 */
function formatTime(raw) {
  if (!raw) return ''
  const date = new Date(raw)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
</script>

<style scoped>
/* 记忆段：和「您填写的」同一张纸上的第二块，不另起一张卡。
   分隔靠一条 1px 线与 16px 节奏，不靠描边盒子（§1 的暖沙语言）。 */
.p-mem {
  padding: 20px 0 4px;
  border-top: 1px solid var(--line);
}
.p-mem__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.p-mem__title {
  font-family: var(--sans);
  font-size: 13.5px;
  letter-spacing: .08em;
  color: var(--ink);
}
.p-mem__hint {
  flex: none;
  font-family: var(--sans);
  font-size: 10.5px;
  color: var(--ink-3);
}
.p-mem__state {
  margin: 0;
  font-family: var(--sans);
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-3);
}
.p-mem__failure {
  display: flex;
  align-items: center;
  gap: 12px;
}
.p-mem__retry {
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 0 16px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
  background: var(--surface);
  font-family: var(--sans);
  font-size: 12px;
  color: var(--leaf-deep);
  cursor: pointer;
  transition: border-color .18s ease, background .18s ease;
}
.p-mem__retry:hover {
  border-color: var(--leaf);
  background: var(--leaf-soft);
}
.p-mem__paused {
  margin: 0 0 12px;
  padding: 12px 16px;
  border-left: 4px solid var(--line-2);
  border-radius: var(--r-sm);
  background: var(--rail);
  font-family: var(--sans);
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-2);
}
.p-mem__empty {
  margin: 0;
  font-family: var(--sans);
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-3);
}
.p-mem__group { padding: 12px 0 4px; }
.p-mem__ghead {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 11px;
}
.p-mem__glabel {
  font-family: var(--sans);
  font-size: 12px;
  letter-spacing: .08em;
  color: var(--ink);
}
.p-mem__quota {
  font-family: var(--sans);
  font-size: 11.5px;
  color: var(--ink-3);
}
.p-mem__item { margin-bottom: 11px; }
.p-mem__item:last-child { margin-bottom: 0; }
.p-mem__kv {
  margin: 0 0 4px;
  font-family: var(--sans);
  font-size: 13.5px;
  line-height: 1.85;
  color: var(--ink);
}
.p-mem__key { color: var(--ink-2); }
.p-mem__val { font-weight: 600; }
/* 原话出处：与追问 / 依据同一套「引用」语言（左侧 2px 浅青竖线 + 缩进） */
.p-mem__quote {
  margin: 0 0 4px;
  padding-left: 11px;
  border-left: 2px solid var(--leaf-soft);
  font-family: var(--sans);
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-2);
}
.p-mem__time {
  margin: 0;
  font-family: var(--sans);
  font-size: 10.5px;
  color: var(--ink-3);
}
</style>
