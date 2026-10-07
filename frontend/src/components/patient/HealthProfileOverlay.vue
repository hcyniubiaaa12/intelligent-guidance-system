<template>
  <Transition name="p-fade">
    <div v-if="open" class="p-overlay p-overlay--wide" @click.self="close">
      <div class="p-overlay__box" role="dialog" aria-label="我的健康档案">
        <header class="p-overlay__head">
          <div>
            <div class="p-overlay__title">健 康 档 案</div>
            <p class="p-overlay__sub">选填 · 帮分诊结合您的既往情况，随时可改</p>
          </div>
          <button class="p-overlay__close" aria-label="关闭健康档案" @click="close">关 闭</button>
        </header>

        <div v-if="loading" class="p-prof__loading">正在加载…</div>

        <div v-else class="p-overlay__body">
          <!-- 性别 / 年龄段：选项由读回体的 options 下发，**前端不写死** -->
          <div class="p-prof__row">
            <label id="p-prof-l-gender" class="p-prof__label">性　别</label>
            <PatientSelect
              id="p-prof-gender"
              label-id="p-prof-l-gender"
              v-model="form.gender"
              :options="options.genders"
              none-label="不 填"
              placeholder="暂无可选项"
            />
          </div>
          <div class="p-prof__row">
            <label id="p-prof-l-age" class="p-prof__label">年 龄 段</label>
            <PatientSelect
              id="p-prof-age"
              label-id="p-prof-l-age"
              v-model="form.ageRange"
              :options="options.ageRanges"
              none-label="不 填"
              placeholder="暂无可选项"
            />
          </div>

          <section v-for="grp in groups" :key="grp.key" class="p-prof__group">
            <div class="p-prof__ghead">
              <span class="p-prof__glabel">{{ grp.label }}</span>
              <span class="p-prof__quota">已选 {{ form[grp.tags].length }} / 最多 {{ limits.tagMax }} 项</span>
            </div>
            <div class="p-prof__chips">
              <button
                v-for="t in grp.options"
                :key="t.id"
                type="button"
                class="p-prof__chip"
                :class="{ 'is-on': form[grp.tags].includes(t.term) }"
                @click="toggleTag(grp.tags, t.term)"
              >{{ t.term }}</button>
              <p v-if="!grp.options.length" class="p-prof__none">词表暂无可选项，可在下方自由填写</p>
            </div>
            <div class="p-prof__other">
              <input
                v-model="form[grp.other]"
                class="p-prof__input"
                type="text"
                :placeholder="grp.placeholder"
                :maxlength="textFieldMax(grp.other)"
              />
              <span class="p-prof__left">{{ textFieldRemaining(grp.other) }} 字</span>
            </div>
          </section>

          <p v-if="error" class="p-prof__err">{{ error }}</p>
        </div>

        <footer class="p-overlay__foot">
          <button class="p-btn" :disabled="loading || saving" @click="save">
            {{ saving ? '保 存 中…' : '保 存 档 案' }}
          </button>
        </footer>
      </div>
    </div>
  </Transition>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { getHealthProfile, saveHealthProfile, listHealthTags } from '../../api/profile'
import PatientSelect from './PatientSelect.vue'

/**
 * 健康档案（选填浮层）。
 *
 * 浮层**外壳**（遮罩 / 容器 / 抬头 / 关闭 / 进出节奏）走共享件 `.p-overlay*` 与
 * `.p-fade-*`——见设计文档 §3.8；本页只管表单本身。
 *
 * 上限直接在源头挡住：标签多选到顶写不进、自由文本走 maxlength，并显示剩余额度。
 * **前端硬限只是提示**，后端还会二次校验兜底。
 *
 * 数据全部由本组件自己取：打开 → 拉读回体（含 limits 与 options），保存 → 整份覆盖写。
 * 调用方只管 `open` 一个布尔，不需要知道里面有哪些字段。
 */
const props = defineProps({
  open: { type: Boolean, default: false }
})
const emit = defineEmits(['update:open'])

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const tags = ref([])
// 上限来自后端受管参数；这里的默认值只在前端首次渲染、尚未拿到响应时兜底
const limits = ref({ tagMax: 10, textMax: 50, textTotalMax: 120 })
const options = ref({ genders: [], ageRanges: [] })
const form = ref(emptyProfile())

// 三个自由文本框共用一条合计额度：写满一个，另外两个的可写空间随之收窄
const OTHER_FIELDS = ['historyOther', 'medicationOther', 'allergyOther']

function emptyProfile() {
  return {
    gender: '',
    ageRange: '',
    historyTags: [],
    historyOther: '',
    medicationTags: [],
    medicationOther: '',
    allergyTags: [],
    allergyOther: ''
  }
}

/**
 * 三组「多选标签 + 其他自由文本」：标签按 type 从词表过滤。
 * 分组名与后端 `HealthTagType.label`（既往病史/长期用药/过敏史）各持一份——跨了语言边界
 * （后端 Java 枚举 / 前端展示文案），无法共享同一常量，故保留；改文案时两处一起改。
 */
const groups = computed(() => [
  { key: 'history', label: '既 往 病 史', tags: 'historyTags', other: 'historyOther', type: 'chronic', placeholder: '词表里没有的病史，在这里补充' },
  { key: 'medication', label: '长 期 用 药', tags: 'medicationTags', other: 'medicationOther', type: 'medication', placeholder: '词表里没有的药物，在这里补充' },
  { key: 'allergy', label: '过 敏 史', tags: 'allergyTags', other: 'allergyOther', type: 'allergy', placeholder: '具体药名或过敏物，在这里补充' }
].map((g) => ({ ...g, options: tags.value.filter((t) => t.type === g.type) })))

// 打开就拉：每次打开都是最新的读回体（可能刚在另一处改过），不做缓存
watch(
  () => props.open,
  (v) => {
    if (v) load()
  },
  { immediate: true }
)

function close() {
  emit('update:open', false)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [data, tagList] = await Promise.all([getHealthProfile(), listHealthTags()])
    tags.value = tagList || []
    limits.value = {
      tagMax: data.limits?.tagMax ?? 10,
      textMax: data.limits?.textMax ?? 50,
      textTotalMax: data.limits?.textTotalMax ?? 120
    }
    options.value = data.options || { genders: [], ageRanges: [] }
    form.value = {
      gender: data.gender || '',
      ageRange: data.ageRange || '',
      historyTags: data.historyTags || [],
      medicationTags: data.medicationTags || [],
      allergyTags: data.allergyTags || [],
      historyOther: data.historyOther || '',
      medicationOther: data.medicationOther || '',
      allergyOther: data.allergyOther || ''
    }
  } catch (e) {
    console.error('[profile] 健康档案加载失败', e)
    error.value = e.message || '档案加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

/** 多选标签：超上限**当场写不进**（后端仍会二次校验，前端只是即时提示） */
function toggleTag(field, term) {
  const list = form.value[field]
  const idx = list.indexOf(term)
  if (idx >= 0) {
    list.splice(idx, 1)
    return
  }
  if (list.length >= limits.value.tagMax) {
    error.value = `每类最多选 ${limits.value.tagMax} 项，先取消一项再选`
    return
  }
  error.value = ''
  list.push(term)
}

/**
 * 单框可写上限 = min(单框上限, 合计上限 − 另外两框已写字数)。
 * 作为 input 的 maxlength ⇒ 到额度就**写不进**（浏览器拦截），与后端二次校验口径一致，不静默裁剪。
 */
function textFieldMax(field) {
  const others = OTHER_FIELDS.filter((f) => f !== field)
    .reduce((n, f) => (n + (form.value[f]?.length || 0)), 0)
  return Math.max(0, Math.min(limits.value.textMax, limits.value.textTotalMax - others))
}

/** 本框还剩多少字（同时反映单框与三框合计两条额度） */
function textFieldRemaining(field) {
  return Math.max(0, textFieldMax(field) - (form.value[field]?.length || 0))
}

async function save() {
  if (saving.value) return
  error.value = ''
  saving.value = true
  try {
    await saveHealthProfile(form.value)
    emit('update:open', false)
  } catch (e) {
    // 后端二次校验拒绝（超条数 / 超字数）会给可展示的 message
    error.value = e.message || '保存失败，请稍后重试'
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
/* ---------- 健康档案：表单内部 ----------
   浮层**外壳**（遮罩 / 容器 / 抬头 / 关闭 / 进出节奏）走共享件 `.p-overlay*` 与
   `.p-fade-*`，本组件只留表单——见设计文档 §3.8。
   性别 / 年龄段用自绘下拉 `PatientSelect`，样式在共享件 `.p-sel*` 里（§3.9）。 */
.p-prof__loading {
  padding: 48px 16px;
  font-family: var(--sans);
  font-size: 11.5px;
  letter-spacing: .06em;
  color: var(--ink-3);
}
.p-prof__row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--line);
}
.p-prof__label {
  flex: none;
  width: 64px;
  font-family: var(--sans);
  font-size: 11.5px;
  letter-spacing: .1em;
  color: var(--ink-2);
}
.p-prof__group {
  padding: 16px 0;
  border-bottom: 1px solid var(--line);
}
.p-prof__group:last-of-type { border-bottom: none; }
.p-prof__ghead {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}
.p-prof__glabel {
  font-family: var(--sans);
  font-size: 11.5px;
  letter-spacing: .1em;
  color: var(--ink);
}
.p-prof__quota {
  font-family: var(--sans);
  font-size: 10px;
  color: var(--ink-3);
}
.p-prof__chips { display: flex; flex-wrap: wrap; gap: 8px; }
.p-prof__chip {
  display: inline-flex;
  align-items: center;
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 0 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-full);
  background: var(--surface);
  font-family: var(--sans);
  font-size: 12.5px;
  color: var(--ink);
  cursor: pointer;
  transition: border-color .18s ease, background .18s ease, color .18s ease;
}
.p-prof__chip:hover { border-color: var(--leaf); color: var(--leaf-deep); }
/* 选中态与科室列表同一口径：浅底 + 主色描边 + 主色深字（不是实心主色底） */
.p-prof__chip.is-on {
  background: var(--leaf-soft);
  border-color: var(--leaf);
  color: var(--leaf-deep);
  font-weight: 600;
}
.p-prof__none {
  font-family: var(--sans);
  font-size: 11.5px;
  color: var(--ink-3);
}
.p-prof__other { display: flex; align-items: center; gap: 8px; margin-top: 12px; }
.p-prof__input {
  flex: 1;
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--surface);
  font-family: var(--sans);
  font-size: 13.5px;
  color: var(--ink);
}
.p-prof__input:focus { outline: none; border-color: var(--leaf); }
.p-prof__left {
  flex: none;
  font-family: var(--sans);
  font-size: 10px;
  color: var(--ink-3);
}
.p-prof__err {
  margin: 12px 0 4px;
  padding: 12px 16px;
  border-left: 4px solid var(--alert);
  border-radius: var(--r-sm);
  background: var(--alert-soft);
  color: var(--alert);
  font-size: 11.5px;
  line-height: 1.85;
}
</style>