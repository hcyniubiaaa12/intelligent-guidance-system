<template>
  <div class="ev">
    <template v-if="parsed.ok">
      <!-- 结论行放最上面：先知道"答了什么、引用了谁"，再看依据 -->
      <p v-if="parsed.model" class="ev__model">
        <span v-if="parsed.model.verdict" class="a-tag">{{ parsed.model.verdict }}</span>
        <span v-if="parsed.model.dept" class="ev__dept">{{ parsed.model.dept }}</span>
        <span v-if="parsed.model.confidence !== null" class="ev__conf">
          置信度 {{ fmtScore(parsed.model.confidence) }}
        </span>
        <span v-if="citedSummary" class="ev__citedsum">{{ citedSummary }}</span>
      </p>

      <p v-if="hint" class="ev__hint" :class="'ev__hint--' + hint.level">{{ hint.text }}</p>

      <template v-if="parsed.retrieved.length">
        <div class="ev__label">系统召回 · Top{{ parsed.retrieved.length }}（正文为 400 字缩写，点条目展开）</div>
        <template v-for="item in parsed.retrieved" :key="item.index">
          <button
            class="ev__row"
            :class="{
              'ev__row--cited': isCited(parsed, item),
              'ev__row--open': isOpen(item.index)
            }"
            @click="toggleRow(item.index)"
          >
            <span class="ev__chev">{{ isOpen(item.index) ? '▾' : '▸' }}</span>
            <span class="ev__no">{{ item.rank === null ? '注?' : '注' + item.rank }}</span>
            <span class="ev__title">{{ item.title || '（无标题）' }}</span>
            <span v-if="isCited(parsed, item)" class="ev__cited">已引用</span>
            <span class="ev__score">{{ fmtScore(item.score) }}</span>
          </button>
          <!-- 点哪条就在哪条下面展开，不把正文挪到整个列表底下 -->
          <div v-if="isOpen(item.index)" class="ev__body">
            <pre class="ev__pre">{{ item.content || '（这条没存正文）' }}</pre>
            <p class="ev__note">不是完整切片，要全文得拿 chunk_id 回知识库页查：{{ item.chunkId || '（没存）' }}</p>
          </div>
        </template>
      </template>

      <p v-if="parsed.profile" class="ev__profile">
        <span class="ev__profile-label">当时的健康档案</span>{{ parsed.profile.text || '（档案召回串非空，但注入文本为空）' }}
      </p>

      <div class="ev__folds">
        <button class="ev__fold" :class="{ 'ev__fold--on': open.query }" @click="open.query = !open.query">
          检索用串
        </button>
        <button class="ev__fold" :class="{ 'ev__fold--on': open.raw }" @click="open.raw = !open.raw">
          模型原始输出
        </button>
        <button class="ev__fold" :class="{ 'ev__fold--on': open.snapshot }" @click="open.snapshot = !open.snapshot">
          原始快照
        </button>
      </div>
      <pre v-if="open.query" class="ev__pre">{{ parsed.retrievedQuery || '（空）' }}</pre>
      <pre v-if="open.raw" class="ev__pre">{{ parsed.modelOutputRaw || '（空）' }}</pre>
      <pre v-if="open.snapshot" class="ev__pre">{{ snapshotText }}</pre>
    </template>

    <!-- 解析不了就原样摊开，不猜结构。老记录、坏 JSON 都走这条路 -->
    <template v-else>
      <p class="ev__hint ev__hint--warn">这份快照不是可解析的证据结构，按原文展示</p>
      <pre class="ev__pre">{{ parsed.raw || '（空）' }}</pre>
    </template>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { evidenceHint, fmtScore, isCited, parseEvidence, prettySnapshot } from '../../utils/evidenceReplay'

const props = defineProps({
  evidence: { type: String, default: '' }
})

const parsed = computed(() => parseEvidence(props.evidence))
const hint = computed(() => evidenceHint(parsed.value))
const snapshotText = computed(() => prettySnapshot(parsed.value))

const citedSummary = computed(() => {
  if (!parsed.value.cited.length) return '未引用任何注'
  return '引用 ' + parsed.value.cited.map((no) => `注${no}`).join('、')
})

// 每条召回独立开合：点哪条看哪条，互不影响
const openIndexes = ref([])
const open = reactive({ query: false, raw: false, snapshot: false })

function isOpen(index) {
  return openIndexes.value.includes(index)
}

function toggleRow(index) {
  const pos = openIndexes.value.indexOf(index)
  if (pos >= 0) openIndexes.value.splice(pos, 1)
  else openIndexes.value.push(index)
}
</script>

<style scoped>
.ev {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
/* 结论行：verdict 用既有药丸 tag（.a-tag 在 admin.css，10px 圆角） */
.ev__model {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0;
  font-size: 13px;
  color: var(--ink);
}
.ev__dept {
  font-weight: 700;
}
.ev__conf,
.ev__citedsum {
  font-size: 11px;
  color: var(--ink-2);
}
.ev__hint {
  margin: 0;
  padding: 5px 9px;
  border-left: 2px solid var(--line);
  font-size: 11px;
  line-height: 1.7;
  color: var(--ink-2);
}
.ev__hint--warn {
  border-left-color: var(--coral);
  background: var(--coral-soft);
  color: var(--ink);
}
.ev__hint--info {
  border-left-color: var(--blue);
  background: var(--wash);
}
.ev__label {
  margin-top: 4px;
  font-size: 11px;
  font-weight: 700;
  color: var(--ink);
}
.ev__row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 9px;
  border: 0;
  border-left: 2px solid transparent;
  background: transparent;
  font-family: var(--sans);
  font-size: 13px;
  color: var(--ink-2);
  text-align: left;
  cursor: pointer;
}
.ev__row:hover {
  background: var(--wash);
}
.ev__row--cited {
  border-left-color: var(--blue);
  color: var(--ink);
}
.ev__row--open {
  background: var(--wash);
  color: var(--ink);
}
.ev__chev {
  flex: none;
  width: 12px;
  font-size: 10px;
  color: var(--ink-2);
}
.ev__no {
  flex: none;
  width: 28px;
  font-size: 11px;
}
.ev__row--cited .ev__no {
  color: var(--blue);
}
.ev__title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ev__cited {
  flex: none;
  font-size: 11px;
  color: var(--blue);
}
.ev__score {
  flex: none;
  width: 34px;
  text-align: right;
  font-size: 11px;
  color: var(--ink-2);
}
/* 展开的正文贴着它的条目，左侧同一条 2px 蓝线连起来 */
.ev__body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-left: 2px;
  padding-left: 9px;
  border-left: 2px solid var(--wash);
}
.ev__profile {
  margin: 0;
  font-size: 11px;
  line-height: 1.7;
  color: var(--ink-2);
}
.ev__profile-label {
  font-weight: 700;
  color: var(--ink);
  margin-right: 8px;
}
.ev__note {
  margin: 0;
  font-size: 11px;
  line-height: 1.7;
  color: var(--ink-2);
}
.ev__folds {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 4px;
}
.ev__fold {
  padding: 4px 10px;
  border: 1px solid var(--line);
  border-radius: 5px;
  background: var(--card);
  font-family: var(--sans);
  font-size: 11px;
  color: var(--ink-2);
  cursor: pointer;
}
.ev__fold:hover {
  border-color: var(--blue);
  color: var(--blue);
}
.ev__fold--on {
  border-color: var(--blue);
  background: var(--wash);
  color: var(--blue);
}
.ev__pre {
  margin: 0;
  padding: 8px 10px;
  background: var(--wash);
  font-family: var(--sans);
  font-size: 11px;
  line-height: 1.7;
  color: var(--ink-2);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 220px;
  overflow: auto;
}
</style>
