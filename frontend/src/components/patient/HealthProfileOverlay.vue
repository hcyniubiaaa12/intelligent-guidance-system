<template>
  <Transition name="p-fade">
    <div v-if="open" class="p-overlay p-overlay--wide" @click.self="close">
      <div class="p-overlay__box" role="dialog" aria-label="我的健康档案">
        <header class="p-overlay__head">
          <div>
            <div class="p-overlay__title">健康档案</div>
            <p class="p-overlay__sub">您填写的 + 我记住的</p>
          </div>
          <button class="p-overlay__close" aria-label="关闭健康档案" @click="close">关闭</button>
        </header>

        <div v-if="loading" class="p-prof__loading">正在加载…</div>

        <div v-else class="p-overlay__body">
          <!-- 性别 / 出生年：性别选项由读回体的 options 下发，**前端不写死**；
               出生年是数字输入（1900–当前年），不再编辑旧年龄段 -->
          <div class="p-prof__row">
            <label id="p-prof-l-gender" class="p-prof__label">性别</label>
            <PatientSelect
              id="p-prof-gender"
              label-id="p-prof-l-gender"
              v-model="form.gender"
              :options="options.genders"
              none-label="不填"
              placeholder="暂无可选项"
            />
          </div>
          <div class="p-prof__row">
            <label id="p-prof-l-birth" class="p-prof__label">出生日期</label>
            <PatientDatePicker
              id="p-prof-birth"
              label-id="p-prof-l-birth"
              v-model="form.birthDate"
              :year-only="!form.birthDateExact"
              placeholder="选择出生日期（选填）"
              @update:model-value="onBirthDate"
            />
          </div>
          <!-- 只读兜底：出生年缺失时展示旧档案年龄段，并说清补出生年后就改用它 -->
          <p v-if="showLegacyAgeRange" class="p-prof__legacy">
            旧档案年龄段：{{ legacyAgeRangeLabel }}
            <span>补上出生年后自动改用出生年</span>
          </p>

          <!-- 过敏：仍是多选标签 + 其他（没有时间语义） -->
          <section class="p-prof__group">
            <div class="p-prof__ghead">
              <span class="p-prof__glabel">过敏史</span>
              <span class="p-prof__quota">已选 {{ form.allergyTags.length }} / 最多 {{ limits.tagMax }} 项</span>
            </div>
            <div class="p-prof__chips">
              <button
                v-for="t in allergyOptions"
                :key="t.id"
                type="button"
                class="p-prof__chip"
                :class="{ 'is-on': form.allergyTags.includes(t.term) }"
                @click="toggleAllergy(t.term)"
              >{{ t.term }}</button>
              <p v-if="!allergyOptions.length" class="p-prof__none">词表暂无可选项，可在下方自由填写</p>
            </div>
            <div class="p-prof__other">
              <input
                v-model="form.allergyOther"
                class="p-prof__input"
                type="text"
                placeholder="具体药名或过敏物，在这里补充"
                :maxlength="textFieldMax('allergyOther')"
              />
              <span class="p-prof__left">{{ textFieldRemaining('allergyOther') }} 字</span>
            </div>
          </section>

          <!-- 用药 / 手术：条目行 = 值 + 时间（YYYY-MM），可增删；点标签把该词加为一条 -->
          <section class="p-prof__group">
            <div class="p-prof__ghead">
              <span class="p-prof__glabel">近期用药</span>
              <span class="p-prof__quota">已填 {{ filledCount('medicationEntries') }} / 最多 {{ limits.tagMax }} 项</span>
            </div>
            <div class="p-prof__chips">
              <button
                v-for="t in medicationOptions"
                :key="t.id"
                type="button"
                class="p-prof__chip"
                :class="{ 'is-on': hasEntry('medicationEntries', t.term) }"
                @click="toggleEntry('medicationEntries', t.term, medicationEntry)"
              >{{ t.term }}</button>
              <p v-if="!medicationOptions.length" class="p-prof__none">词表暂无可选项，可在下面直接添加</p>
            </div>
            <div v-if="form.medicationEntries.length" class="p-prof__entries">
              <div v-for="(entry, i) in form.medicationEntries" :key="i" class="p-prof__entry">
                <input
                  v-model="entry.value"
                  class="p-prof__input p-prof__entry-name"
                  type="text"
                  placeholder="药名"
                />
                <div class="p-prof__times">
                  <input
                    v-model="entry.startedOn"
                    class="p-prof__input p-prof__time"
                    type="text"
                    maxlength="7"
                    placeholder="开始 YYYY-MM"
                  />
                  <span class="p-prof__dash">—</span>
                  <input
                    v-model="entry.endedOn"
                    class="p-prof__input p-prof__time"
                    type="text"
                    maxlength="7"
                    placeholder="结束 YYYY-MM"
                  />
                </div>
                <button type="button" class="p-prof__del" @click="form.medicationEntries.splice(i, 1)">删除</button>
              </div>
            </div>
            <p v-if="form.medicationEntries.length" class="p-prof__hint">结束时间留空表示仍在使用，会算「近期用药」。</p>
            <button type="button" class="p-prof__add" @click="addEntry('medicationEntries', medicationEntry)">＋ 添加一条用药</button>
            <div class="p-prof__other">
              <input
                v-model="form.medicationOther"
                class="p-prof__input"
                type="text"
                placeholder="词表里没有的药物，在这里补充"
                :maxlength="textFieldMax('medicationOther')"
              />
              <span class="p-prof__left">{{ textFieldRemaining('medicationOther') }} 字</span>
            </div>
          </section>

          <section class="p-prof__group">
            <div class="p-prof__ghead">
              <span class="p-prof__glabel">近期手术</span>
              <span class="p-prof__quota">已填 {{ filledCount('surgeryEntries') }} / 最多 {{ limits.tagMax }} 项</span>
            </div>
            <div class="p-prof__chips">
              <button
                v-for="t in surgeryOptions"
                :key="t.id"
                type="button"
                class="p-prof__chip"
                :class="{ 'is-on': hasEntry('surgeryEntries', t.term) }"
                @click="toggleEntry('surgeryEntries', t.term, surgeryEntry)"
              >{{ t.term }}</button>
              <p v-if="!surgeryOptions.length" class="p-prof__none">词表暂无可选项，可在下面直接添加</p>
            </div>
            <div v-if="form.surgeryEntries.length" class="p-prof__entries">
              <div v-for="(entry, i) in form.surgeryEntries" :key="i" class="p-prof__entry">
                <input
                  v-model="entry.value"
                  class="p-prof__input p-prof__entry-name"
                  type="text"
                  placeholder="手术名称"
                />
                <div class="p-prof__times">
                  <input
                    v-model="entry.occurredOn"
                    class="p-prof__input p-prof__time"
                    type="text"
                    maxlength="7"
                    placeholder="发生 YYYY-MM"
                  />
                </div>
                <button type="button" class="p-prof__del" @click="form.surgeryEntries.splice(i, 1)">删除</button>
              </div>
            </div>
            <p v-if="form.surgeryEntries.length" class="p-prof__hint">超过 12 个月的手术会自动算「既往手术」，不再写成近期。</p>
            <button type="button" class="p-prof__add" @click="addEntry('surgeryEntries', surgeryEntry)">＋ 添加一条手术</button>
            <div class="p-prof__other">
              <input
                v-model="form.surgeryOther"
                class="p-prof__input"
                type="text"
                placeholder="词表里没有的手术，在这里补充"
                :maxlength="textFieldMax('surgeryOther')"
              />
              <span class="p-prof__left">{{ textFieldRemaining('surgeryOther') }} 字</span>
            </div>
          </section>

          <!-- 第二段「我记住的」：长期记忆只读列表。放在「您填写的」之后——
               先说患者确认过的，再说系统自动记下的，顺序本身就是两层信息的优先级。 -->
          <MemorySection />

          <!-- 第三段「待确认」：档案候选。接受后才写进上面的「您填写的」；
               接受事件要并回表单缓存，否则随后「保存档案」会用旧表单覆盖刚确认的值。 -->
          <CandidateSection @accepted="onCandidateAccepted" />

          <p v-if="error" class="p-prof__err">{{ error }}</p>
        </div>

        <footer class="p-overlay__foot">
          <button class="p-btn" :disabled="loading || saving" @click="save">
            {{ saving ? '保存中…' : '保存档案' }}
          </button>
        </footer>
      </div>
    </div>
  </Transition>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { getHealthProfile, saveHealthProfile, listHealthTags } from '../../api/profile'
import CandidateSection from './CandidateSection.vue'
import MemorySection from './MemorySection.vue'
import PatientSelect from './PatientSelect.vue'
import PatientDatePicker from './PatientDatePicker.vue'
import { fromBirthYear, parseDate } from '../../utils/datePicker'

/**
 * 健康档案（选填浮层）。
 *
 * 缩表后只问「性别 / 出生年 / 过敏 / 近期用药 / 近期手术」。旧年龄段在出生年缺失时
 * **只读展示**（补出生年后自动改用它）；既往病史不再展示、不再提交——库里保留原值
 * 只作迁移与回滚依据。
 *
 * 时间语义（工单 03）：用药条目 = 药名 + 开始/结束月份，结束月份留空表示仍在使用；
 * 手术条目 = 名称 + 发生月份，超过 12 个月由后端组装成「既往手术」。时间一律用
 * **两个小文本输入（YYYY-MM）**，不用原生 month/date 控件——后者的长相由浏览器决定，
 * 同一套界面换台机器就变了（§3.9 同一理由）。
 *
 * 浮层**外壳**（遮罩 / 容器 / 抬头 / 关闭 / 进出节奏）走共享件 `.p-overlay*` 与
 * `.p-fade-*`——见设计文档患者端 §3.8；本页只管表单本身。
 *
 * 上限直接在源头挡住：标签 / 条目到顶写不进、自由文本走 maxlength，并显示剩余额度。
 * **前端硬限只是提示**，后端还会二次校验兜底。
 */
const props = defineProps({
  open: { type: Boolean, default: false }
})
const emit = defineEmits(['update:open'])

const currentYear = new Date().getFullYear()

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const tags = ref([])
// 上限来自后端受管参数；这里的默认值只在前端首次渲染、尚未拿到响应时兜底
const limits = ref({ tagMax: 10, textMax: 50, textTotalMax: 120 })
const options = ref({ genders: [], ageRanges: [] })
const legacyAgeRange = ref('')
const form = ref(emptyProfile())

// 三个自由文本框共用一条合计额度：写满一个，另外两个的可写空间随之收窄
const OTHER_FIELDS = ['allergyOther', 'medicationOther', 'surgeryOther']

function emptyProfile() {
  return {
    gender: '',
    // 服务端只存 birth_year；birthDate 是日历的完整值，birthDateExact 表示「本次选了完整日期」
    birthYear: '',
    birthDate: '',
    birthDateExact: false,
    allergyTags: [],
    allergyOther: '',
    medicationEntries: [],
    medicationOther: '',
    surgeryEntries: [],
    surgeryOther: ''
  }
}

// 词表按类别过滤（只列启用项；前端不写死任何词条）
const allergyOptions = computed(() => tags.value.filter((t) => t.type === 'allergy'))
const medicationOptions = computed(() => tags.value.filter((t) => t.type === 'medication'))
const surgeryOptions = computed(() => tags.value.filter((t) => t.type === 'surgery'))

const legacyAgeRangeLabel = computed(() => {
  if (!legacyAgeRange.value) return ''
  const found = (options.value.ageRanges || []).find((o) => o.value === legacyAgeRange.value)
  return found ? found.label : legacyAgeRange.value
})
const showLegacyAgeRange = computed(
  () => !String(form.value.birthYear ?? '').trim() && !!legacyAgeRangeLabel.value
)

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

/**
 * 候选接受后把事实并回「您填写的」表单缓存。
 *
 * <p>为什么必须并回：接受是后端直接写档案，而本表单是**整份覆盖写**——如果表单不知道
 * 刚写进去的值，患者随后点「保存档案」就会把它覆盖掉。这里只做本地合并、不重拉表单，
 * 以免冲掉患者正在填写但尚未保存的其它内容。
 *
 * <p>落点与后端同一口径：过敏值在启用词表里进标签、否则进自由文本；用药/手术进条目。
 */
function onCandidateAccepted(fact) {
  const value = String(fact?.value || '').trim()
  if (!value) return
  if (fact.field === 'allergy') {
    if (allergyOptions.value.some((t) => t.term === value)) {
      if (!form.value.allergyTags.includes(value)) form.value.allergyTags.push(value)
    } else {
      appendOther('allergyOther', value)
    }
    return
  }
  if (fact.field === 'medication') {
    if (!form.value.medicationEntries.some((e) => (e.value || '').trim() === value)) {
      form.value.medicationEntries.push({ value, startedOn: fact.occurredOn || '', endedOn: '' })
    }
    return
  }
  if (fact.field === 'surgery') {
    if (!form.value.surgeryEntries.some((e) => (e.value || '').trim() === value)) {
      form.value.surgeryEntries.push({ value, occurredOn: fact.occurredOn || '' })
    }
  }
}

/** 自由文本追加：按顿号/逗号/分号判重，与后端 appendProfileFact 的幂等口径一致 */
function appendOther(key, value) {
  const current = String(form.value[key] || '').trim()
  const parts = current ? current.split(/[、,，;；]/).map((part) => part.trim()) : []
  if (parts.includes(value)) return
  form.value[key] = current ? `${current}、${value}` : value
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
    legacyAgeRange.value = data.ageRange || ''
    form.value = {
      gender: data.gender || '',
      birthYear: data.birthYear ?? '',
      birthDate: fromBirthYear(data.birthYear),
      birthDateExact: false,
      allergyTags: data.allergyTags || [],
      allergyOther: data.allergyOther || '',
      medicationEntries: (data.medicationEntries || []).map((e) => ({
        value: e.value || '',
        startedOn: e.startedOn || '',
        endedOn: e.endedOn || ''
      })),
      medicationOther: data.medicationOther || '',
      surgeryEntries: (data.surgeryEntries || []).map((e) => ({
        value: e.value || '',
        occurredOn: e.occurredOn || ''
      })),
      surgeryOther: data.surgeryOther || ''
    }
  } catch (e) {
    console.error('[profile] 健康档案加载失败', e)
    error.value = e.message || '档案加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

/** 过敏多选：超上限**当场写不进**（后端仍会二次校验，前端只是即时提示） */
function toggleAllergy(term) {
  const list = form.value.allergyTags
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

/** 条目工厂：点标签时加一条时间空的记录 */
function medicationEntry(value) {
  return { value, startedOn: '', endedOn: '' }
}
function surgeryEntry(value) {
  return { value, occurredOn: '' }
}

function hasEntry(field, term) {
  return form.value[field].some((e) => (e.value || '').trim() === term)
}

/** 额度只数**填了名的**条目：空行还没成为一条记录，不该占患者的额度 */
function filledCount(field) {
  return form.value[field].filter((e) => (e.value || '').trim()).length
}

/** 点标签：已存在同值条目则移除，否则加一条（时间空，患者可再补月份） */
function toggleEntry(field, term, factory) {
  const list = form.value[field]
  const idx = list.findIndex((e) => (e.value || '').trim() === term)
  if (idx >= 0) {
    list.splice(idx, 1)
    return
  }
  if (filledCount(field) >= limits.value.tagMax) {
    error.value = `每类最多填 ${limits.value.tagMax} 项，先删一条再添加`
    return
  }
  error.value = ''
  list.push(factory(term))
}

function addEntry(field, factory) {
  if (filledCount(field) >= limits.value.tagMax) {
    error.value = `每类最多填 ${limits.value.tagMax} 项`
    return
  }
  error.value = ''
  form.value[field].push(factory(''))
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

/** 日历选值 → 只把年份写进表单；清除时两处一起清空 */
function onBirthDate(value) {
  const parsed = parseDate(value)
  if (!parsed) {
    form.value.birthYear = ''
    form.value.birthDate = ''
    form.value.birthDateExact = false
    return
  }
  form.value.birthYear = String(parsed.year)
  form.value.birthDateExact = true
}

/** 出生年：空 = 未填；填了就必须是 1900–当前年（后端还会二次校验） */
function birthYearOf(formValue) {
  const raw = String(formValue.birthYear ?? '').trim()
  if (!raw) return null
  const year = Number(raw)
  if (!Number.isInteger(year) || year < 1900 || year > currentYear) {
    error.value = `出生年需在 1900 到 ${currentYear} 之间`
    return undefined
  }
  return year
}

/**
 * 月份即时校验（与后端同一口径）：空 = 未填；否则必须是 YYYY-MM 且不是未来月份。
 * 返回 null 表示通过，返回字符串表示可展示的错误。
 */
function monthError(raw, label) {
  const value = (raw || '').trim()
  if (!value) return null
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(value)) {
    return `${label}格式应为 YYYY-MM（如 2024-03）`
  }
  const [year, month] = value.split('-').map(Number)
  const now = new Date()
  if (year > now.getFullYear() || (year === now.getFullYear() && month > now.getMonth() + 1)) {
    return `${label}不能是未来月份`
  }
  return null
}

/** 组装用药提交体：完全空的行跳过；半填行（有时间没药名）拒绝，不静默丢数据 */
function buildMedicationEntries() {
  const result = []
  for (const entry of form.value.medicationEntries) {
    const value = (entry.value || '').trim()
    const startedOn = (entry.startedOn || '').trim()
    const endedOn = (entry.endedOn || '').trim()
    if (!value && !startedOn && !endedOn) continue
    if (!value) {
      error.value = '请填写用药名称'
      return null
    }
    const invalid = monthError(startedOn, '用药开始时间') || monthError(endedOn, '用药结束时间')
    if (invalid) {
      error.value = invalid
      return null
    }
    if (startedOn && endedOn && endedOn < startedOn) {
      error.value = `用药「${value}」的结束时间不能早于开始时间`
      return null
    }
    result.push({ value, startedOn: startedOn || null, endedOn: endedOn || null })
  }
  return result
}

/** 组装手术提交体（口径同上） */
function buildSurgeryEntries() {
  const result = []
  for (const entry of form.value.surgeryEntries) {
    const value = (entry.value || '').trim()
    const occurredOn = (entry.occurredOn || '').trim()
    if (!value && !occurredOn) continue
    if (!value) {
      error.value = '请填写手术名称'
      return null
    }
    const invalid = monthError(occurredOn, '手术发生时间')
    if (invalid) {
      error.value = invalid
      return null
    }
    result.push({ value, occurredOn: occurredOn || null })
  }
  return result
}

async function save() {
  if (saving.value) return
  error.value = ''
  const birthYear = birthYearOf(form.value)
  if (birthYear === undefined) return
  const medicationEntries = buildMedicationEntries()
  if (medicationEntries === null) return
  const surgeryEntries = buildSurgeryEntries()
  if (surgeryEntries === null) return
  saving.value = true
  try {
    // 只提交缩表后的可编辑字段：旧年龄段与既往病史留在库里，不由前端回写
    await saveHealthProfile({
      gender: form.value.gender || null,
      birthYear,
      allergyTags: form.value.allergyTags,
      allergyOther: form.value.allergyOther,
      medicationEntries,
      medicationOther: form.value.medicationOther,
      surgeryEntries,
      surgeryOther: form.value.surgeryOther
    })
    emit('update:open', false)
  } catch (e) {
    // 后端二次校验拒绝（超条数 / 超字数 / 时间越界）会给可展示的 message
    error.value = e.message || '保存失败，请稍后重试'
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
/* ---------- 健康档案：表单内部 ----------
   浮层**外壳**（遮罩 / 容器 / 抬头 / 关闭 / 进出节奏）走共享件 `.p-overlay*` 与
   `.p-fade-*`，本组件只留表单——见设计文档患者端 §3.8。
   性别用自绘下拉 `PatientSelect`，样式在共享件 `.p-sel*` 里（§3.9）。 */
.p-prof__loading {
  padding: 48px 16px;
  font-family: var(--sans);
  font-size: 12px;
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
  font-size: 12px;
  letter-spacing: .08em;
  color: var(--ink-2);
}
/* 旧年龄段只读兜底：与表单文字同列，不抢主信息 */
.p-prof__legacy {
  margin: 4px 0 0;
  /* 与右侧输入列对齐：标签宽 64 + 行内间隙 12 */
  padding-left: calc(64px + 12px);
  font-family: var(--sans);
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-2);
}
.p-prof__legacy span { color: var(--ink-3); }
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
  font-size: 12px;
  letter-spacing: .08em;
  color: var(--ink);
}
.p-prof__quota {
  font-family: var(--sans);
  font-size: 12px;
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
  font-size: 12px;
  color: var(--ink-3);
}
/* 条目行：值 + 时间 + 删除；窄屏自动换行，但每个控件仍 ≥44px */
.p-prof__entries {
  display: flex;
  flex-direction: column;
  gap: 11px;
  margin-top: 12px;
}
.p-prof__entry {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.p-prof__input.p-prof__entry-name { flex: 1 1 140px; min-width: 120px; }
.p-prof__times { display: flex; flex: 1 1 auto; align-items: center; gap: 8px; }
.p-prof__input.p-prof__time { flex: 0 0 104px; width: 104px; }
.p-prof__dash {
  flex: none;
  font-family: var(--sans);
  font-size: 12px;
  color: var(--ink-3);
}
.p-prof__del {
  flex: none;
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--surface);
  font-family: var(--sans);
  font-size: 12px;
  color: var(--ink-2);
  cursor: pointer;
  transition: border-color .18s ease, color .18s ease;
}
.p-prof__del:hover { border-color: var(--alert); color: var(--alert); }
.p-prof__add {
  min-height: 44px; /* 触控目标 ≥ 44px */
  margin-top: 12px;
  padding: 0 16px;
  border: 1px dashed var(--line-2);
  border-radius: var(--r-sm);
  background: transparent;
  font-family: var(--sans);
  font-size: 12.5px;
  color: var(--leaf-deep);
  cursor: pointer;
  transition: border-color .18s ease, background .18s ease;
}
.p-prof__add:hover { border-color: var(--leaf); background: var(--leaf-soft); }
.p-prof__hint {
  margin: 8px 0 0;
  font-family: var(--sans);
  font-size: 11.5px;
  line-height: 1.7;
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
  font-size: 12px;
  color: var(--ink-3);
}
.p-prof__err {
  margin: 12px 0 4px;
  padding: 12px 16px;
  border-left: 4px solid var(--alert);
  border-radius: var(--r-sm);
  background: var(--alert-soft);
  color: var(--alert);
  font-size: 12px;
  line-height: 1.85;
}
</style>
