<template>
  <section class="p-card">
    <div class="p-card__head">
      <div>
        <div class="p-eyebrow">给 您 的 分 诊 结 论</div>
        <div class="p-card__dept">{{ card.dept }}</div>
      </div>
      <div class="p-card__conf" :class="{ 'p-card__conf--low': isLow(card) }">
        {{ confText(card.confidence) }}
      </div>
    </div>

    <!-- 置信度条列表 Top3 -->
    <div class="p-bars">
      <div
        v-for="(c, k) in rankedTop3(card)"
        :key="c.deptId || c.name"
        class="p-bar"
        :class="{ 'p-bar--top': k === 0 }"
      >
        <span class="p-bar__rank">{{ k + 1 }}</span>
        <span class="p-bar__name">{{ c.name }}</span>
        <span class="p-bar__track"><span class="p-bar__fill" :style="{ width: (c.pct ?? 0) + '%' }" /></span>
        <span class="p-bar__pct">{{ c.pct == null ? '—' : c.pct + '%' }}</span>
      </div>
    </div>

    <p class="p-card__note">{{ card.note }}</p>

    <!-- 脚注区：判断依据（1px dashed 上边框，注号对应证据顺序） -->
    <div class="p-card__foot">
      <span class="p-eyebrow">判 断 依 据</span>
      <p v-for="c in card.cites" :key="c.no" class="p-cite">
        <span class="p-cite__no">注{{ c.no }}</span>
        <span class="p-cite__title">{{ c.title }}</span>
        <span class="p-cite__body">{{ c.content }}</span>
      </p>
    </div>

    <!-- 健康档案：患者自述，不是医学证据。单独一行、标成「健康档案」，与「判断依据」分开 -->
    <p v-if="card.profileText" class="p-card__profile">
      已参考您的健康档案：{{ card.profileText }}
    </p>

    <p v-if="isLow(card)" class="p-card__lowhint">
      信息有限，结果仅供参考，建议进一步咨询医生。
    </p>

    <!-- 一键复制结论单（科室＋置信度＋依据），学 DS 的块级复制 -->
    <CopyButton
      class="p-card__copy"
      :copied="copied"
      aria-label="复制这条分诊结论"
      text="复 制 结 论"
      @copy="copy"
    />

    <!-- 回放时已挂号的，补一行就诊信息；没有的什么都不加 -->
    <div v-if="replay && card.booked" class="p-card__visit">
      就 诊　{{ card.actualDept }}<template v-if="card.actualDeptLocation"> · {{ card.actualDeptLocation }}</template>
    </div>

    <!-- 签名部件：三步陪伴带。**只在实时对话里出现**——回放是历史记录，
         "接下来我陪您走这三步"在那时是句假话 -->
    <div v-if="!replay" class="p-band">
      <div class="p-band__hd">接下来我陪您走这三步</div>
      <div class="p-band__row">
        <span class="p-band__n p-band__n--now">
          <span class="p-band__dot"></span><span class="p-band__t">① 拿到结论</span>
        </span>
        <span class="p-band__link"></span>
        <span class="p-band__n">
          <span class="p-band__dot"></span><span class="p-band__t">② 去挂号</span>
        </span>
        <span class="p-band__link"></span>
        <span class="p-band__n">
          <span class="p-band__dot"></span><span class="p-band__t">③ 确认完成</span>
        </span>
      </div>
    </div>

    <button
      v-if="!replay"
      class="p-btn p-card__go"
      :disabled="!canRegister"
      @click="emit('register')"
    >
      我陪您 · 去模拟挂号
    </button>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { cardText, confText, isLow, rankedTop3 } from '../../utils/conclusion'
import { copyToClipboard } from '../../utils/clipboard'
import CopyButton from './CopyButton.vue'

/**
 * 分诊结论卡（链路 A 的结论单，签名部件）。
 *
 * 卡体本身的样式（科室名、置信度块、Top3 并列条、判断依据、健康档案行）是**共享件**
 * `.p-card*`，见 `patient.css` §3.2；本组件只补三处页面私有的东西：
 * 复制按钮常显、就诊信息行、去挂号按钮的间距。
 *
 * 顶部三条并列候选的置信度条 = 侧栏标识里那三条横线，**与产品最强的记忆点同源**。
 *
 * 三处「只读」的口径由父页面用 props 决定，本组件不自己判断：
 * - `replay` 回放态：不出陪伴带、不出挂号按钮（翻旧账不该往前推流程）
 * - `canRegister` 只有**最新一张**卡能继续挂号（一个聊天页可先后承载多个会话与多张卡）
 */
const props = defineProps({
  /** 对话条目本身：{ recordId, card } —— 挂号跳转要 recordId 与 card 一起带走 */
  entry: { type: Object, required: true },
  /** 正在看历史（只读回放） */
  replay: { type: Boolean, default: false },
  /** 是否是最新一张卡（决定去挂号按钮可用） */
  canRegister: { type: Boolean, default: false }
})
const emit = defineEmits(['register'])

const card = computed(() => props.entry.card)

/** 复制态自己管：这张卡的复制与对话流里那些气泡的复制互不相干，1.5s 后复位 */
const copied = ref(false)
let timer = null
async function copy() {
  await copyToClipboard(cardText(card.value))
  copied.value = true
  clearTimeout(timer)
  timer = setTimeout(() => { copied.value = false }, 1500)
}

onBeforeUnmount(() => clearTimeout(timer))
</script>

<style scoped>
/* 结论卡的复制**常显**：卡是"交代"，hover 才出现会让人找不到 */
.p-card__copy { margin-top: 12px; opacity: 1; }
/* 回放态补的一行就诊信息（1px 实线与上方内容分开——它是"这次真的去了"的事实） */
.p-card__visit {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
  font-family: var(--sans);
  font-size: 11.5px;
  letter-spacing: .06em;
  color: var(--ink-2);
}
/* 卡里最后那个主操作（陪伴带之后），与陪伴带保持一个档位的间距 */
.p-card__go { margin-top: 16px; }
</style>