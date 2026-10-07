<template>
  <div class="ev">
    <template v-if="parsed.ok">
      <!-- 柱高 = 本组内的相对相关度，蓝色 = 模型引用了这一注。
           不读一个字就能看出「检索排第一的有没有被用上」 -->
      <div v-if="parsed.retrieved.length" class="ev__bar">
        <div
          v-for="(item, i) in parsed.retrieved"
          :key="item.index"
          class="ev__seg"
          :class="{
            'ev__seg--cited': isCited(parsed, item),
            'ev__seg--unknown': heights[i] === null
          }"
          :style="heights[i] === null ? null : { height: heights[i] + 'px' }"
        />
        <span class="ev__cap">柱高 = 本组内的相对相关度 · 蓝色 = 模型引用了这一注</span>
      </div>

      <p v-if="hint" class="ev__hint" :class="'ev__hint--' + hint.level">{{ hint.text }}</p>

      <template v-if="parsed.retrieved.length">
        <div class="ev__label">系统召回 · 精排 Top{{ parsed.retrieved.length }}（正文为 400 字缩写）</div>
        <button
          v-for="item in parsed.retrieved"
          :key="item.index"
          class="ev__row"
          :class="{ 'ev__row--cited': isCited(parsed, item) }"
          @click="toggleRow(item.index)"
        >
          <span class="ev__no">{{ item.rank === null ? '注?' : '注' + item.rank }}</span>
          <span class="ev__title">{{ item.title || '（无标题）' }}</span>
          <span v-if="isCited(parsed, item)" class="ev__cited">已引用</span>
          <span class="ev__score">{{ fmtScore(item.score) }}</span>
        </button>
        <template v-if="openIndex !== null">
          <pre class="ev__pre">{{ openContent }}</pre>
          <p class="ev__note">
            不是完整切片，要全文得拿 chunk_id 回知识库页查：{{ openChunkId || '（这条没存 chunk_id）' }}
          </p>
        </template>
      </template>

      <template v-if="parsed.model">
        <div class="ev__label">模型当时的回答</div>
        <p class="ev__model">
          <span v-if="parsed.model.verdict" class="a-tag">{{ parsed.model.verdict }}</span>
          <span v-if="parsed.model.dept">{{ parsed.model.dept }}</span>
          <span v-if="parsed.model.confidence !== null">置信度 {{ fmtScore(parsed.model.confidence) }}</span>
        </p>
        <p v-if="parsed.model.note" class="ev__note">{{ parsed.model.note }}</p>
      </template>

      <template v-if="parsed.profile">
        <div class="ev__label">当时的健康档案</div>
        <p class="ev__profile">{{ parsed.profile.text || '（档案召回串非空，但注入文本为空）' }}</p>
      </template>

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
import { barHeights, evidenceHint, fmtScore, isCited, parseEvidence, prettySnapshot } from '../../utils/evidenceReplay'

const props = defineProps({
  evidence: { type: String, default: '' }
})

const parsed = computed(() => parseEvidence(props.evidence))
const hint = computed(() => evidenceHint(parsed.value))
const heights = computed(() => barHeights(parsed.value.retrieved))

// 一次只展开一条正文，跟这一页「一次只开一个桶」的节奏一致
const openIndex = ref(null)
const open = reactive({ query: false, raw: false, snapshot: false })

const openHit = computed(() => parsed.value.retrieved.find((item) => item.index === openIndex.value))
const openContent = computed(() => (openHit.value && openHit.value.content) || '（这条没存正文）')
const openChunkId = computed(() => (openHit.value ? openHit.value.chunkId : ''))
const snapshotText = computed(() => prettySnapshot(parsed.value))

function toggleRow(index) {
  openIndex.value = openIndex.value === index ? null : index
}
</script>

<style scoped>
.ev {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
/* 条带：柱底对齐、高度按本组分数归一化——只表达相对高低，不是绝对分数 */
.ev__bar {
  display: flex;
  align-items: flex-end;
  gap: 4px;
  height: 28px;
}
.ev__seg {
  width: 18px;
  background: var(--line);
}
.ev__seg--cited {
  background: var(--blue);
}
/* 分数未知：虚线空框。不画成最矮那根——那等于说「它最不相关」 */
.ev__seg--unknown {
  height: 8px;
  background: transparent;
  border: 1px dashed var(--line);
}
.ev__cap {
  align-self: center;
  margin-left: 8px;
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
  gap: 10px;
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
  background: var(--wash);
  color: var(--ink);
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
/* verdict 用药丸 tag（.a-tag 在 admin.css 里，10px 圆角） */
.ev__model {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 13px;
  color: var(--ink);
}
.ev__profile,
.ev__note {
  margin: 0;
  font-size: 11px;
  line-height: 1.7;
  color: var(--ink-2);
}
.ev__profile {
  padding: 6px 9px;
  background: var(--wash);
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
