<template>
  <Transition name="p-fade">
    <div v-if="open" class="p-overlay p-overlay--console" @click.self="requestClose">
      <div class="p-overlay__box p-hp" role="dialog" aria-label="健康档案">
        <!-- 左侧导航：整条通顶（不再有横跨的抬头），小屏收成顶部一行药丸 -->
        <aside class="p-hp__side">
          <div class="p-hp__brand">
            <span class="p-hp__mark" aria-hidden="true">
              <svg width="19" height="19" viewBox="0 0 24 24" fill="none">
                <path d="M12 20.2s-7.4-4.5-7.4-9.4a4.1 4.1 0 0 1 7.4-2.5 4.1 4.1 0 0 1 7.4 2.5c0 4.9-7.4 9.4-7.4 9.4Z"
                      stroke="currentColor" stroke-width="1.7" stroke-linejoin="round" />
              </svg>
            </span>
            <div class="p-hp__brandtext">
              <div class="p-hp__title">健 康 档 案</div>
              <p class="p-hp__sub">系统对您的了解，随时可改。</p>
            </div>
          </div>

          <!-- 三页性质不同，不是三个步骤，所以不编号 -->
          <nav class="p-hp__nav" role="tablist" aria-orientation="vertical" aria-label="健康档案的三部分" @keydown="onNavKeydown">
            <button
              v-for="tab in tabs"
              :id="`hp-tab-${tab.key}`"
              :key="tab.key"
              type="button"
              role="tab"
              class="p-hp__tab"
              :class="{ 'is-on': active === tab.key }"
              :tabindex="active === tab.key ? 0 : -1"
              :aria-selected="active === tab.key ? 'true' : 'false'"
              :aria-controls="`hp-panel-${tab.key}`"
              @click="active = tab.key"
            >
              <span class="p-hp__tabicon" aria-hidden="true">
                <svg v-if="tab.key === 'profile'" width="17" height="17" viewBox="0 0 20 20" fill="none"
                     stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
                  <rect x="2.5" y="3.5" width="15" height="13" rx="2.2" />
                  <circle cx="8" cy="8.4" r="1.9" />
                  <path d="M4.9 14c.6-1.7 1.8-2.6 3.1-2.6s2.5.9 3.1 2.6M12.8 7.8h2.6M12.8 10.8h2.6" />
                </svg>
                <svg v-else-if="tab.key === 'memory'" width="17" height="17" viewBox="0 0 20 20" fill="none"
                     stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M5.2 3.6h9.6v12.8l-4.8-2.8-4.8 2.8z" />
                </svg>
                <svg v-else width="17" height="17" viewBox="0 0 20 20" fill="none"
                     stroke="currentColor" stroke-width="1.4" stroke-linecap="round">
                  <circle cx="10" cy="10" r="7.2" />
                  <path d="M7.9 7.7a2.1 2.1 0 1 1 2.7 2c-.5.2-.6.6-.6 1.1v.4" />
                  <circle cx="10" cy="13.9" r=".9" fill="currentColor" stroke="none" />
                </svg>
              </span>
              <span class="p-hp__tabtext">
                <span class="p-hp__tabname">{{ tab.name }}</span>
                <span class="p-hp__tabdesc">{{ tab.desc }}</span>
              </span>
              <span v-if="tab.badge !== null" class="p-hp__tabbadge" :class="{ 'is-muted': !tab.badgeCount }">{{ tab.badge }}</span>
            </button>
          </nav>

          <p class="p-hp__navnote">这些内容只用来帮您分诊。</p>
        </aside>

        <button type="button" class="p-overlay__close p-hp__close" aria-label="关闭健康档案" @click="requestClose">
          关闭
        </button>

        <div class="p-hp__main">
            <!-- ===== 第一页：我填写的 ===== -->
            <section
              v-show="active === 'profile'"
              id="hp-panel-profile"
              class="p-hp__panel"
              role="tabpanel"
              aria-labelledby="hp-tab-profile"
            >
              <header class="p-hp__phd">
                <div>
                  <span class="p-hp__eb">您 填 写 的</span>
                  <h2 class="p-hp__h">这几项会用在每一次回答里</h2>
                  <p class="p-hp__lead">由您亲口确认，系统不会自己改。</p>
                </div>
              </header>

              <div class="p-hp__scroll">
                <p v-if="profileState === 'loading'" class="p-hp__state">正在读取您的档案…</p>

                <div v-else-if="profileState === 'failed'" class="p-hp__failed">
                  <p class="p-hp__state">{{ profileError }}</p>
                  <button type="button" class="p-hp__retry" @click="loadProfile">重试</button>
                </div>

                <template v-else>
                  <!-- 有待确认的候选：第一页顶部先提一句，点进第三页 -->
                  <button
                    v-if="candidates.length"
                    type="button"
                    class="p-hp__banner"
                    @click="active = 'candidates'"
                  >
                    <span class="p-hp__bannerdot" aria-hidden="true">{{ candidates.length }}</span>
                    <span class="p-hp__bannertext">有 {{ candidates.length }} 条从对话里听来的健康信息，等您确认</span>
                    <span class="p-hp__bannerlink">去看看 →</span>
                  </button>

                  <!-- 基本信息：设置行式（左说明、右控件），参考控制台的区块卡语言 -->
                  <section class="p-hp__block">
                    <div class="p-hp__blockhd">
                      <div>
                        <h3 class="p-hp__bt">基本信息</h3>
                        <p class="p-hp__bnote">出生日期只保存年份，用来算年龄。</p>
                      </div>
                    </div>

                    <div class="p-hp__rows">
                      <div class="p-hp__row">
                        <div class="p-hp__rowtext">
                          <span id="p-hp-l-gender" class="p-hp__rowt">性别</span>
                          <p class="p-hp__rownote">选填 · 再点一下取消</p>
                        </div>
                        <!-- 两个选项，用分段按钮比下拉少一层交互；选项仍由读回体下发 -->
                        <div class="p-hp__seg" role="group" aria-labelledby="p-hp-l-gender">
                          <button
                            v-for="g in options.genders"
                            :key="g.value"
                            type="button"
                            class="p-hp__segopt"
                            :class="{ 'is-on': form.gender === g.value }"
                            :aria-pressed="form.gender === g.value ? 'true' : 'false'"
                            @click="toggleGender(g.value)"
                          >{{ g.label }}</button>
                          <span v-if="!options.genders.length" class="p-hp__segempty">暂时没有可选项</span>
                        </div>
                      </div>

                      <div class="p-hp__row">
                        <div class="p-hp__rowtext">
                          <span id="p-hp-l-birth" class="p-hp__rowt">出生日期</span>
                          <p class="p-hp__rownote">{{ ageHint || '选填' }}</p>
                        </div>
                        <PatientDatePicker
                          class="p-hp__birth"
                          id="p-hp-birth"
                          label-id="p-hp-l-birth"
                          v-model="form.birthDate"
                          :year-only="!form.birthDateExact"
                          placeholder="选填"
                          @update:model-value="onBirthDate"
                        />
                      </div>
                    </div>

                    <p v-if="showLegacyAgeRange" class="p-hp__legacy">
                      旧档案里记的是「{{ legacyAgeRangeLabel }}」；补上出生年后会自动改用出生年。
                    </p>
                  </section>

                  <!-- 过敏 -->
                  <section class="p-hp__block">
                    <div class="p-hp__blockhd">
                      <div>
                        <h3 class="p-hp__bt">过敏史</h3>
                        <p class="p-hp__bnote">资料里提到您过敏的东西时，我会在结论里先提醒您。</p>
                      </div>
                      <div class="p-hp__gstat">
                        <span class="p-hp__quota">{{ form.allergyTags.length }} / {{ limits.tagMax }}</span>
                      </div>
                    </div>
                    <TagInput
                      id="p-hp-allergy"
                      label-id="p-hp-l-allergy"
                      v-model="form.allergyTags"
                      :options="allergyOptions"
                      :max="limits.tagMax"
                      placeholder="药名、食物、接触物都可以写"
                      @overlimit="onOverlimit"
                    />
                    <span id="p-hp-l-allergy" class="p-hp__srlabel">过敏史</span>
                  </section>

                  <!-- 近期用药 -->
                  <section class="p-hp__block">
                    <div class="p-hp__blockhd">
                      <div>
                        <h3 class="p-hp__bt">近期用药</h3>
                        <p class="p-hp__bnote">结束月份留空就是「至今」，表示还在吃。</p>
                      </div>
                      <div class="p-hp__gstat">
                        <span class="p-hp__quota">{{ filledCount('medicationEntries') }} / {{ limits.tagMax }}</span>
                        <button type="button" class="p-hp__add" @click="addEntry('medicationEntries', medicationEntry)">
                          ＋ 添加
                        </button>
                      </div>
                    </div>

                    <div v-if="form.medicationEntries.length" class="p-hp__entries">
                      <div v-for="(entry, i) in form.medicationEntries" :key="i" class="p-hp__entry">
                        <input
                          v-model="entry.value"
                          class="p-hp__ename"
                          type="text"
                          placeholder="药名，如 二甲双胍"
                          :aria-label="`第 ${i + 1} 条用药的名称`"
                        />
                        <div class="p-hp__etimes">
                          <PatientDatePicker
                            class="p-hp__mon"
                            compact
                            granularity="month"
                            :model-value="entry.startedOn"
                            :max="entry.endedOn || currentMonth"
                            placeholder="开始"
                            @update:model-value="(v) => (entry.startedOn = v)"
                          />
                          <span class="p-hp__arrow" aria-hidden="true">→</span>
                          <PatientDatePicker
                            class="p-hp__mon p-hp__mon--end"
                            compact
                            granularity="month"
                            :model-value="entry.endedOn"
                            :min="entry.startedOn"
                            :max="currentMonth"
                            placeholder="至今"
                            clear-label="至今"
                            @update:model-value="(v) => (entry.endedOn = v)"
                          />
                        </div>
                        <button
                          type="button"
                          class="p-hp__del"
                          :aria-label="`删除用药 ${entry.value || '这一条'}`"
                          @click="form.medicationEntries.splice(i, 1)"
                        >
                          <svg viewBox="0 0 10 10" fill="none" stroke="currentColor" stroke-width="1.8"
                               stroke-linecap="round" aria-hidden="true">
                            <path d="M1 1 9 9M9 1 1 9" />
                          </svg>
                        </button>
                      </div>
                    </div>
                    <p v-else class="p-hp__empty">还没有填。没有就不用填。</p>

                    <div v-if="medicationOptions.length" class="p-hp__quick">
                      <button
                        type="button"
                        class="p-hp__quickt"
                        :aria-expanded="medPicker ? 'true' : 'false'"
                        @click="medPicker = !medPicker"
                      >
                        常用药（{{ medicationOptions.length }}）<span aria-hidden="true">{{ medPicker ? '▴' : '▾' }}</span>
                      </button>
                      <div v-if="medPicker" class="p-hp__chips">
                        <button
                          v-for="t in medicationOptions"
                          :key="t.id"
                          type="button"
                          class="p-hp__chip"
                          :disabled="hasEntry('medicationEntries', t.term)"
                          @click="addQuick('medicationEntries', t.term, medicationEntry)"
                        >{{ t.term }}</button>
                      </div>
                    </div>
                  </section>

                  <!-- 近期手术 -->
                  <section class="p-hp__block">
                    <div class="p-hp__blockhd">
                      <div>
                        <h3 class="p-hp__bt">近期手术</h3>
                        <p class="p-hp__bnote">超过 12 个月会自动算作「既往手术」。</p>
                      </div>
                      <div class="p-hp__gstat">
                        <span class="p-hp__quota">{{ filledCount('surgeryEntries') }} / {{ limits.tagMax }}</span>
                        <button type="button" class="p-hp__add" @click="addEntry('surgeryEntries', surgeryEntry)">
                          ＋ 添加
                        </button>
                      </div>
                    </div>

                    <div v-if="form.surgeryEntries.length" class="p-hp__entries">
                      <div v-for="(entry, i) in form.surgeryEntries" :key="i" class="p-hp__entry">
                        <input
                          v-model="entry.value"
                          class="p-hp__ename"
                          type="text"
                          placeholder="手术名称"
                          :aria-label="`第 ${i + 1} 条手术的名称`"
                        />
                        <div class="p-hp__etimes">
                          <PatientDatePicker
                            class="p-hp__mon"
                            compact
                            granularity="month"
                            :model-value="entry.occurredOn"
                            :max="currentMonth"
                            placeholder="发生月份"
                            @update:model-value="(v) => (entry.occurredOn = v)"
                          />
                        </div>
                        <button
                          type="button"
                          class="p-hp__del"
                          :aria-label="`删除手术 ${entry.value || '这一条'}`"
                          @click="form.surgeryEntries.splice(i, 1)"
                        >
                          <svg viewBox="0 0 10 10" fill="none" stroke="currentColor" stroke-width="1.8"
                               stroke-linecap="round" aria-hidden="true">
                            <path d="M1 1 9 9M9 1 1 9" />
                          </svg>
                        </button>
                      </div>
                    </div>
                    <p v-else class="p-hp__empty">还没有填。没有就不用填。</p>

                    <div v-if="surgeryOptions.length" class="p-hp__quick">
                      <button
                        type="button"
                        class="p-hp__quickt"
                        :aria-expanded="surgPicker ? 'true' : 'false'"
                        @click="surgPicker = !surgPicker"
                      >
                        常见手术（{{ surgeryOptions.length }}）<span aria-hidden="true">{{ surgPicker ? '▴' : '▾' }}</span>
                      </button>
                      <div v-if="surgPicker" class="p-hp__chips">
                        <button
                          v-for="t in surgeryOptions"
                          :key="t.id"
                          type="button"
                          class="p-hp__chip"
                          :disabled="hasEntry('surgeryEntries', t.term)"
                          @click="addQuick('surgeryEntries', t.term, surgeryEntry)"
                        >{{ t.term }}</button>
                      </div>
                    </div>
                  </section>
                </template>
              </div>

              <!-- 保存条只属于第一页：记忆与候选是各自即时生效的 -->
              <footer v-if="profileState === 'ready'" class="p-hp__foot">
                <p v-if="savedTip" class="p-hp__saved" role="status">
                  <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
                    <circle cx="7" cy="7" r="6.2" stroke="currentColor" stroke-width="1.3" />
                    <path d="M4.2 7.1 6.2 9.1 9.8 4.9" stroke="currentColor" stroke-width="1.5"
                          stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                  健康档案已保存，下一次回答会用到这些内容。
                </p>
                <p v-if="formError" class="p-hp__err" role="alert">{{ formError }}</p>

                <div v-if="closing" class="p-hp__closeconfirm">
                  <p class="p-hp__closetext">有未保存的改动。关掉之前要先保存吗？</p>
                  <div class="p-hp__actions">
                    <button
                      ref="saveCloseBtn"
                      type="button"
                      class="p-hp__btn p-hp__btn--primary"
                      :disabled="saving"
                      @click="saveThenClose"
                    >
                      {{ saving ? '保存中…' : '保存并关闭' }}
                    </button>
                    <button type="button" class="p-hp__btn p-hp__btn--danger" :disabled="saving" @click="discardAndClose">
                      放弃并关闭
                    </button>
                    <button type="button" class="p-hp__btn" :disabled="saving" @click="closing = false">
                      继续编辑
                    </button>
                  </div>
                </div>

                <div v-else class="p-hp__bar">
                  <p class="p-hp__barstate" :class="{ 'is-dirty': dirty }">
                    {{ dirty ? '有未保存的改动' : '所有内容都已保存' }}
                  </p>
                  <div v-if="dirty" class="p-hp__actions">
                    <button type="button" class="p-hp__btn" :disabled="saving" @click="discard">撤销改动</button>
                    <button type="button" class="p-hp__btn p-hp__btn--primary" :disabled="saving" @click="save()">
                      {{ saving ? '保存中…' : '保存' }}
                    </button>
                  </div>
                </div>
              </footer>
            </section>

            <!-- ===== 第二页：它记住的 ===== -->
            <section
              v-show="active === 'memory'"
              id="hp-panel-memory"
              class="p-hp__slot"
              role="tabpanel"
              aria-labelledby="hp-tab-memory"
            >
              <MemorySection
                :data="memoryData"
                :state="memoryState"
                @refresh="refreshMemory"
                @retry="loadMemory"
              />
            </section>

            <!-- ===== 第三页：待确认 ===== -->
            <section
              v-show="active === 'candidates'"
              id="hp-panel-candidates"
              class="p-hp__slot"
              role="tabpanel"
              aria-labelledby="hp-tab-candidates"
            >
              <CandidateSection
                :data="memoryData"
                :state="memoryState"
                @refresh="refreshMemory"
                @retry="loadMemory"
                @accepted="onCandidateAccepted"
              />
            </section>
          </div>
      </div>
    </div>
  </Transition>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { getHealthProfile, saveHealthProfile, listHealthTags } from '../../api/profile'
import { getMemory } from '../../api/memory'
import CandidateSection from './CandidateSection.vue'
import MemorySection from './MemorySection.vue'
import PatientDatePicker from './PatientDatePicker.vue'
import TagInput from './TagInput.vue'
import { fromBirthYear, parseDate, todayMonth } from '../../utils/datePicker'

/**
 * 健康档案（患者端浮层）。
 *
 * <p>**一本三页的小册子**：左栏是三个分类书签，右栏一次只看一页——
 * 「我填写的 / 它记住的 / 待确认」。三页性质不同（患者确认的事实 / 系统自动记的笔记 /
 * 还没确认的二手事实），所以不编号、不做成一条长表单上下堆叠：
 * 长表单会让患者滚过三段互不相干的语义，也说不清"哪部分动过、哪部分要存"。
 *
 * <p>三页的数据来源只有两处，由本组件统一持有：
 * - 档案（`GET /profile` + `GET /profile/tags`）→ 第一页；
 * - 记忆（`GET /memory`，含候选）→ 第二、三页，左栏徽标也从这里算。
 * 两个请求并行，**互不阻塞**：档案失败时记忆仍可看，反之亦然。
 * 「取不到」绝不当成「没有」——徽标显示「—」、页面给重试，不渲染成空。
 *
 * <p>第一页是**整份覆盖写**（后端只有一个 PUT），所以底栏只在第一页：
 * 改动按提交体快照算（`snapshot`），打字又删掉、空行都不会点亮它。
 * 第二、三页的每个动作（改/删记忆、接受/拒绝候选）都是**就地即时生效**，
 * 它们没有"待保存"这回事——把两种保存语义混在一条底栏上，患者会以为三段都没存。
 *
 * <p>旧自由文本 `*_other` 在版面上没有独立输入框：读回时就按顿号/逗号/分号拆成
 * 普通标签 / 条目（`splitOther`），提交时三列写空串。**删掉任何输入入口之前，
 * 先问它的存量值由谁展示**（进度.md 已知坑第一条）。
 *
 * <p>浮层外壳（遮罩 / 圆角面板 / 关闭 / 进出节奏）走共享件 `.p-overlay*` 与
 * `.p-fade-*`；三页共用的页头与滚动区走 `.p-hp__*`（patient.css §3.11）；
 * 本文件只写外壳布局与第一页表单。
 */
const props = defineProps({
  open: { type: Boolean, default: false }
})
const emit = defineEmits(['update:open'])

const currentYear = new Date().getFullYear()
/** 现在的月份（`YYYY-MM`）：用药/手术都不允许选未来 */
const currentMonth = todayMonth()

/* ---------------- 书签 ---------------- */

const active = ref('profile')
const TAB_DEFS = [
  { key: 'profile', name: '我填写的', desc: '性别、生日、过敏、用药、手术' },
  { key: 'memory', name: '它记住的', desc: '偏好与长期背景' },
  { key: 'candidates', name: '待确认', desc: '从对话里听来的健康信息' }
]

const tabs = computed(() =>
  TAB_DEFS.map((tab) => {
    let badge = null
    let badgeCount = 0
    if (tab.key === 'memory' || tab.key === 'candidates') {
      if (memoryState.value === 'failed') {
        badge = '—'
      } else if (memoryState.value === 'ready') {
        badgeCount = tab.key === 'memory' ? memoryTotal.value : candidates.value.length
        // 0 条不挂徽标：徽标是"有事要看"的信号，不是装饰
        badge = badgeCount > 0 ? String(badgeCount) : null
      }
    }
    return { ...tab, badge, badgeCount }
  })
)

/** 方向键在书签之间移动（WAI-ARIA tabs：自动激活，焦点跟着走） */
function onNavKeydown(e) {
  const idx = TAB_DEFS.findIndex((t) => t.key === active.value)
  let next = null
  if (e.key === 'ArrowDown' || e.key === 'ArrowRight') next = (idx + 1) % TAB_DEFS.length
  else if (e.key === 'ArrowUp' || e.key === 'ArrowLeft') next = (idx - 1 + TAB_DEFS.length) % TAB_DEFS.length
  else if (e.key === 'Home') next = 0
  else if (e.key === 'End') next = TAB_DEFS.length - 1
  if (next === null) return
  e.preventDefault()
  active.value = TAB_DEFS[next].key
  nextTick(() => document.getElementById(`hp-tab-${TAB_DEFS[next].key}`)?.focus())
}

/* ---------------- 数据状态 ---------------- */

const profileState = ref('loading') // loading / ready / failed
const profileError = ref('')
const memoryState = ref('loading')
const memoryData = ref(null)

const preferences = computed(() => memoryData.value?.preferences || [])
const backgrounds = computed(() => memoryData.value?.backgrounds || [])
const candidates = computed(() => memoryData.value?.candidates || [])
const memoryTotal = computed(() => preferences.value.length + backgrounds.value.length)

const tags = ref([])
// 上限来自后端受管参数；默认值只在首次渲染、响应未到时兜底
const limits = ref({ tagMax: 10 })
const options = ref({ genders: [], ageRanges: [] })
const legacyAgeRange = ref('')
const form = ref(emptyProfile())

/** 「常用」的展开态：两个字段各一份，默认收起（常驻一排 chips 会与条目重复编码） */
const medPicker = ref(false)
const surgPicker = ref(false)

/** 第一页相对读回体是否有改动（决定底栏是"安静的一行"还是"有按钮"） */
const dirty = ref(false)
const baseline = ref('')
const saving = ref(false)
/** 第一页的即时错误：额度到顶 / 保存校验失败。显示在底部保存条上方，始终可见。 */
const formError = ref('')
/** 保存成功的就地提示：保存后**不关闭浮层**，只在保存条上方给一句确认，3 秒后淡出 */
const savedTip = ref(false)
let savedTimer = null
/** 关闭确认：有未保存改动时先停在第一页问一句 */
const closing = ref(false)
const saveCloseBtn = ref(null)

function emptyProfile() {
  return {
    gender: '',
    // 服务端只存 birth_year；birthDate 是日历的完整值，birthDateExact = 本次选了完整日期
    birthYear: '',
    birthDate: '',
    birthDateExact: false,
    allergyTags: [],
    medicationEntries: [],
    surgeryEntries: []
  }
}

/**
 * 自由文本按顿号/逗号/分号拆成多项（与后端 appendProfileFact 的判重口径一致）。
 *
 * <p>三张 `*_other` 列在新版面上**没有独立输入框**：它们不是第三个入口，只是旧数据的
 * 形状。读回体里带出来的自由文本必须在界面上看得见——患者写过「皮肤过敏、荨麻疹」，
 * 界面却显示一片空白的过敏史，等于把他的档案藏起来了。
 */
function splitOther(raw) {
  return String(raw || '')
    .split(/[、,，;；]/)
    .map((part) => part.trim())
    .filter(Boolean)
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

/** 只保存年份，所以年龄是"约"——不假装知道月日 */
const ageHint = computed(() => {
  const year = Number(form.value.birthYear)
  if (!Number.isInteger(year) || year < 1900) return ''
  const age = currentYear - year
  return age >= 0 && age <= 130 ? `约 ${age} 岁` : ''
})

// 打开就拉：每次打开都是最新的读回体（可能刚在另一处改过），不做缓存。
// `immediate: true` 不能少——浮层挂载时 open 可能已经是 true，那样 watch 一次都不触发、
// 数据永远不加载而界面照常显示兜底值（进度.md「已知坑」）。
watch(
  () => props.open,
  (v) => {
    if (v) openLoad()
  },
  { immediate: true }
)

async function openLoad() {
  active.value = 'profile'
  closing.value = false
  formError.value = ''
  clearSavedTip()
  // 两个来源互不阻塞：档案失败不该让记忆页也打不开
  await Promise.all([loadProfile(), loadMemory()])
}

async function loadProfile() {
  profileState.value = 'loading'
  profileError.value = ''
  try {
    const [data, tagList] = await Promise.all([getHealthProfile(), listHealthTags()])
    tags.value = tagList || []
    limits.value = { tagMax: data.limits?.tagMax ?? 10 }
    options.value = data.options || { genders: [], ageRanges: [] }
    legacyAgeRange.value = data.ageRange || ''
    form.value = readIntoForm(data)
    medPicker.value = false
    surgPicker.value = false
    baseline.value = snapshot(form.value)
    dirty.value = false
    profileState.value = 'ready'
  } catch (e) {
    console.error('[profile] 健康档案加载失败', e)
    profileError.value = e.message || '档案加载失败，请稍后重试'
    profileState.value = 'failed'
  }
}

/** 读回体 → 表单。旧自由文本在这一步拆成普通标签 / 条目，不能在界面上隐形。 */
function readIntoForm(data) {
  const mergeUnique = (list) => [...new Set(list.map((v) => String(v || '').trim()).filter(Boolean))]
  return {
    gender: data.gender || '',
    birthYear: data.birthYear ?? '',
    birthDate: fromBirthYear(data.birthYear),
    birthDateExact: false,
    allergyTags: mergeUnique([...(data.allergyTags || []), ...splitOther(data.allergyOther)]),
    medicationEntries: [
      ...(data.medicationEntries || []).map((e) => ({
        value: e.value || '',
        startedOn: e.startedOn || '',
        endedOn: e.endedOn || ''
      })),
      ...splitOther(data.medicationOther).map((value) => ({
        value,
        startedOn: '',
        endedOn: ''
      }))
    ].filter((entry, i, all) =>
      all.findIndex((other) => (other.value || '').trim() === (entry.value || '').trim()) === i
    ),
    surgeryEntries: [
      ...(data.surgeryEntries || []).map((e) => ({
        value: e.value || '',
        occurredOn: e.occurredOn || ''
      })),
      ...splitOther(data.surgeryOther).map((value) => ({ value, occurredOn: '' }))
    ].filter((entry, i, all) =>
      all.findIndex((other) => (other.value || '').trim() === (entry.value || '').trim()) === i
    )
  }
}

async function loadMemory() {
  memoryState.value = 'loading'
  try {
    memoryData.value = await getMemory()
    memoryState.value = 'ready'
  } catch (e) {
    console.error('[memory] 记忆加载失败', e)
    memoryData.value = null
    memoryState.value = 'failed'
  }
}

/**
 * 写操作后的刷新：**静默**（不回到 loading），避免整页闪一下。
 * 失败则退回 failed 态给重试——写已经生效，不能报成"没成功"。
 */
async function refreshMemory() {
  try {
    memoryData.value = await getMemory()
    memoryState.value = 'ready'
  } catch (e) {
    console.error('[memory] 写入后刷新失败', e)
    memoryState.value = 'failed'
  }
}

/* ---------------- 改动快照 ---------------- */

/**
 * 可提交状态的规范化快照：只含**会提交给后端**的字段，且按提交口径归一
 * （去首尾空格、空条目剔除、标签排序）。打字又删掉、加了个空行都不会点亮底栏。
 */
function snapshot(f) {
  const meds = (f.medicationEntries || [])
    .map((e) => [String(e.value || '').trim(), e.startedOn || '', e.endedOn || ''])
    .filter((row) => row.some(Boolean))
  const surgery = (f.surgeryEntries || [])
    .map((e) => [String(e.value || '').trim(), e.occurredOn || ''])
    .filter((row) => row.some(Boolean))
  return JSON.stringify({
    gender: f.gender || '',
    birthYear: String(f.birthYear ?? '').trim(),
    allergyTags: [...(f.allergyTags || [])].sort(),
    meds,
    surgery
  })
}

watch(form, () => {
  formError.value = ''
  dirty.value = profileState.value === 'ready' && snapshot(form.value) !== baseline.value
}, { deep: true })

/* ---------------- 关闭 ---------------- */

function closeNow() {
  closing.value = false
  emit('update:open', false)
}

/** 关闭前拦一下：有未保存改动就停在第一页问一句，别静默丢。
    焦点同时交给「保存并关闭」——键盘用户点完关闭，下一步就该是它。 */
function requestClose() {
  if (saving.value) return
  if (dirty.value && profileState.value === 'ready') {
    active.value = 'profile'
    closing.value = true
    nextTick(() => saveCloseBtn.value?.focus())
    return
  }
  closeNow()
}

/** 放弃改动 = 重拉一次读回体（顺带把 baseline 复位、清掉成功提示） */
async function discard() {
  closing.value = false
  clearSavedTip()
  await loadProfile()
}

function discardAndClose() {
  closeNow()
}

/* ---------------- 候选接受 ---------------- */

/**
 * 候选接受后把事实并回「您填写的」表单缓存。
 *
 * <p>为什么必须并回：接受是后端直接写档案，而本表单是**整份覆盖写**——如果表单不知道
 * 刚写进去的值，患者随后点「保存」就会把它覆盖掉。这里只做本地合并、不重拉表单，
 * 以免冲掉患者正在填写但尚未保存的其它内容。
 *
 * <p>**前提没改动时顺手把 baseline 也推进**：候选已被后端写进档案，不该再把底栏
 * 点亮成"有内容没保存"。若此前已有未保存改动，baseline 保持不动——那些改动确实还没存。
 */
function onCandidateAccepted(fact) {
  const wasDirty = dirty.value
  const value = String(fact?.value || '').trim()
  if (value) {
    if (fact.field === 'allergy') {
      if (!form.value.allergyTags.includes(value)) form.value.allergyTags.push(value)
    } else if (fact.field === 'medication') {
      if (!form.value.medicationEntries.some((e) => (e.value || '').trim() === value)) {
        form.value.medicationEntries.push({ value, startedOn: fact.occurredOn || '', endedOn: '' })
      }
    } else if (fact.field === 'surgery') {
      if (!form.value.surgeryEntries.some((e) => (e.value || '').trim() === value)) {
        form.value.surgeryEntries.push({ value, occurredOn: fact.occurredOn || '' })
      }
    }
  }
  if (!wasDirty) baseline.value = snapshot(form.value)
}

/* ---------------- 第一页字段操作 ---------------- */

function toggleGender(value) {
  // 选填：再点一下取消。选项本身由读回体下发，前端不写死词条
  form.value.gender = form.value.gender === value ? '' : value
}

/** 标签输入框到顶：当场写不进，给一句能照做的提示（后端仍会二次校验） */
function onOverlimit(max) {
  formError.value = `每类最多选 ${max} 项，先移除一项再加`
}

/** 条目工厂：点常用词时加一条时间空的记录 */
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

/** 常用词是"快捷添加"，不是已选态——已加过的词按钮禁用，删除只在条目行里做 */
function addQuick(field, term, factory) {
  if (hasEntry(field, term)) return
  if (filledCount(field) >= limits.value.tagMax) {
    formError.value = `每类最多填 ${limits.value.tagMax} 项，先删一条再添加`
    return
  }
  formError.value = ''
  form.value[field].push(factory(term))
}

function addEntry(field, factory) {
  if (filledCount(field) >= limits.value.tagMax) {
    formError.value = `每类最多填 ${limits.value.tagMax} 项`
    return
  }
  formError.value = ''
  form.value[field].push(factory(''))
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

/* ---------------- 保存 ---------------- */

/** 出生年：空 = 未填；填了就必须是 1900–当前年（后端还会二次校验） */
function birthYearOf(formValue) {
  const raw = String(formValue.birthYear ?? '').trim()
  if (!raw) return null
  const year = Number(raw)
  if (!Number.isInteger(year) || year < 1900 || year > currentYear) {
    formError.value = `出生年需在 1900 到 ${currentYear} 之间`
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
      formError.value = '请填写用药名称'
      return null
    }
    const invalid = monthError(startedOn, '用药开始时间') || monthError(endedOn, '用药结束时间')
    if (invalid) {
      formError.value = invalid
      return null
    }
    if (startedOn && endedOn && endedOn < startedOn) {
      formError.value = `用药「${value}」的结束时间不能早于开始时间`
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
      formError.value = '请填写手术名称'
      return null
    }
    const invalid = monthError(occurredOn, '手术发生时间')
    if (invalid) {
      formError.value = invalid
      return null
    }
    result.push({ value, occurredOn: occurredOn || null })
  }
  return result
}

/**
 * 保存第一页。
 *
 * <p>**保存成功不关浮层**：患者常常想接着看/接着改，替他把页面关掉等于把"我还在看"打断。
 * 成功后就地给一句「已保存」，并把 baseline 推进（底栏回到"所有内容都已保存"）。
 * 唯一会关的是关闭确认里的「保存并关闭」——那是患者已经明确要关。
 */
async function save({ andClose = false } = {}) {
  if (saving.value) return false
  formError.value = ''
  const birthYear = birthYearOf(form.value)
  if (birthYear === undefined) return false
  const medicationEntries = buildMedicationEntries()
  if (medicationEntries === null) return false
  const surgeryEntries = buildSurgeryEntries()
  if (surgeryEntries === null) return false
  saving.value = true
  try {
    // 只提交缩表后的可编辑字段：旧年龄段与既往病史留在库里，不由前端回写。
    // 三张 `*_other` 列一律写空串——旧自由文本已在读回时拆成普通标签 / 条目（splitOther），
    // 组装口径（标签 ∪ 补充）完全相同，不会丢内容。
    await saveHealthProfile({
      gender: form.value.gender || null,
      birthYear,
      allergyTags: form.value.allergyTags,
      allergyOther: '',
      medicationEntries,
      medicationOther: '',
      surgeryEntries,
      surgeryOther: ''
    })
    // 服务端已经收下这份提交体：推进 baseline，底栏不再说"有未保存的改动"
    baseline.value = snapshot(form.value)
    dirty.value = false
    if (andClose) {
      closeNow()
    } else {
      showSavedTip()
    }
    return true
  } catch (e) {
    // 后端二次校验拒绝（超条数/超字数/时间越界）会给可展示的 message
    formError.value = e.message || '保存失败，请稍后重试'
    return false
  } finally {
    saving.value = false
  }
}

/** 就地成功提示：3 秒后自己消失，不弹窗、不需要患者点掉 */
function showSavedTip() {
  savedTip.value = true
  if (savedTimer) clearTimeout(savedTimer)
  savedTimer = setTimeout(() => {
    savedTip.value = false
    savedTimer = null
  }, 3200)
}

function clearSavedTip() {
  savedTip.value = false
  if (savedTimer) {
    clearTimeout(savedTimer)
    savedTimer = null
  }
}

onBeforeUnmount(clearSavedTip)

async function saveThenClose() {
  closing.value = false
  await save({ andClose: true })
}
</script>

<style scoped>
/* ============================================================
   健康档案：外壳 + 三页书签 + 第一页表单
   三页共用的页头 / 滚动区在 patient.css §3.11（.p-hp__phd / __h / __lead / __scroll …），
   这里只写只属于外壳与第一页的东西。
   ============================================================ */

/* ---------- 左侧导航（整条通顶）---------- */
/* .p-overlay__box 默认是纵向列；这里改成"左导航 + 右内容"的两栏 */
.p-hp {
  flex-direction: row;
  position: relative;
}
.p-hp__side {
  flex: none;
  width: 224px;
  display: flex;
  flex-direction: column;
  padding: 18px 12px 16px;
  background: var(--stage);
  overflow-y: auto;
}
.p-hp__brand {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 0 6px 18px;
}
.p-hp__mark {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: var(--r-sm);
  background: var(--leaf-soft);
  color: var(--leaf-deep);
}
.p-hp__brandtext { min-width: 0; }
.p-hp__title {
  font-family: var(--sans);
  font-size: 17px;
  font-weight: 600;
  letter-spacing: .04em;
  line-height: 1.3;
  color: var(--ink);
}
.p-hp__sub {
  margin-top: 3px;
  font-family: var(--sans);
  font-size: 10.5px;
  line-height: 1.6;
  color: var(--ink-3);
}

/* 书签 = 图标 + 两行文字 + 可选徽标；选中是**主色浅底块**，不是白卡压上去 */
.p-hp__nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.p-hp__tab {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 9px 10px;
  border: 0;
  border-radius: var(--r-sm);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background .18s ease;
}
.p-hp__tab:hover { background: var(--hover-soft); }
.p-hp__tab.is-on { background: var(--leaf-soft); }
.p-hp__tabicon {
  flex: none;
  display: flex;
  color: var(--ink-3);
  transition: color .18s ease;
}
.p-hp__tab.is-on .p-hp__tabicon { color: var(--leaf-deep); }
.p-hp__tabtext {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.p-hp__tabname {
  font-family: var(--sans);
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-2);
  transition: color .18s ease;
}
.p-hp__tab.is-on .p-hp__tabname { color: var(--leaf-deep); }
.p-hp__tabdesc {
  font-family: var(--sans);
  font-size: 10px;
  line-height: 1.45;
  color: var(--ink-3);
}
.p-hp__tabbadge {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: var(--r-full);
  background: var(--leaf);
  color: var(--on-accent);
  font-family: var(--sans);
  font-size: 10.5px;
  font-weight: 600;
  line-height: 1;
}
/* 取不到（加载失败）用中性色：它不是"有 N 条"，只是不知道 */
.p-hp__tabbadge.is-muted {
  background: var(--line-2);
  color: var(--ink-2);
}
.p-hp__navnote {
  margin-top: auto;
  padding: 14px 6px 0;
  font-family: var(--sans);
  font-size: 10px;
  line-height: 1.7;
  color: var(--ink-3);
}

/* 关闭按钮浮在右栏右上角（参考控制台的 X 位），页头右侧留出它的宽度 */
.p-hp__close {
  position: absolute;
  top: 12px;
  right: 14px;
  z-index: 2;
  border-radius: var(--r-xs);
}

/* ---------- 右栏 ---------- */
.p-hp__main {
  flex: 1;
  min-width: 0;
  min-height: 0; /* 窄屏 column 布局下，内容区要靠它才能自己滚 */
  display: flex;
  background: var(--bg);
}
/* 第二、三页的插槽：v-show 的容器只要 flex:1，真正的页布局由子组件的
   `.p-hp__panel`（patient.css §3.11）承担——不套两层同名容器 */
.p-hp__slot {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
}

/* ---------- 第一页：待确认横幅 ---------- */
.p-hp__banner {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  margin-bottom: 12px;
  padding: 12px 14px;
  border: 0;
  border-radius: var(--r-sm);
  background: var(--leaf-soft);
  text-align: left;
  cursor: pointer;
  transition: background .18s ease;
}
/* hover 不引入新色值：一层 inset 主色细线，比再调一档浅底克制 */
.p-hp__banner:hover { box-shadow: inset 0 0 0 1px var(--leaf-line); }
.p-hp__bannerdot {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: var(--r-full);
  background: var(--leaf);
  color: var(--on-accent);
  font-family: var(--sans);
  font-size: 11px;
  font-weight: 600;
}
.p-hp__bannertext {
  flex: 1;
  min-width: 0;
  font-family: var(--sans);
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--leaf-deep);
}
.p-hp__bannerlink {
  flex: none;
  font-family: var(--sans);
  font-size: 11.5px;
  font-weight: 600;
  color: var(--leaf-deep);
  white-space: nowrap;
}

/* ---------- 第一页：区块卡（设置分区的语言）----------
   卡片本身（.p-hp__block / __blockhd / __bt / __bnote）是三页共用的共享件，
   定义在 patient.css §3.11；这里只留第一页私有的额度与添加按钮。 */
.p-hp__gstat {
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 1px;
}
.p-hp__quota {
  font-family: var(--sans);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  color: var(--ink-3);
}
/* 「＋ 添加」挂在标题行右端：比在条目下面单独画一条虚线框省一整行。
   负外边距吃掉 44px 热区多出来的高度，标题行仍只有文字那么高。 */
.p-hp__add {
  flex: none;
  min-height: 44px;
  margin: -13px -4px -13px 0;
  padding: 0 4px;
  border: 0;
  background: transparent;
  font-family: var(--sans);
  font-size: 12px;
  font-weight: 600;
  color: var(--leaf-deep);
  cursor: pointer;
  transition: color .18s ease;
}
.p-hp__add:hover { color: var(--leaf); }

/* 基本信息：设置行（左标题 + 说明、右控件） */
.p-hp__rows { display: flex; flex-direction: column; }
.p-hp__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 0;
}
.p-hp__row + .p-hp__row { border-top: 1px solid var(--line); }
.p-hp__rowtext { min-width: 0; }
.p-hp__rowt {
  font-family: var(--sans);
  font-size: 13px;
  color: var(--ink);
}
.p-hp__rownote {
  margin: 2px 0 0;
  font-family: var(--sans);
  font-size: 10.5px;
  line-height: 1.5;
  color: var(--ink-3);
}
.p-hp__seg {
  flex: none;
  width: 180px;
  display: flex;
  gap: 8px;
}
.p-hp__segopt {
  flex: 1;
  min-width: 0;
  min-height: 44px; /* 触控目标 ≥ 44px */
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--surface);
  font-family: var(--sans);
  font-size: 13.5px;
  color: var(--ink-2);
  cursor: pointer;
  transition: border-color .18s ease, background .18s ease, color .18s ease;
}
.p-hp__segopt:hover { border-color: var(--line-2); }
.p-hp__segopt.is-on {
  border-color: var(--leaf);
  background: var(--leaf-soft);
  color: var(--leaf-deep);
  font-weight: 600;
}
.p-hp__segempty {
  font-family: var(--sans);
  font-size: 12px;
  color: var(--ink-3);
}
.p-hp__birth { flex: 0 0 200px; }
.p-hp__legacy {
  margin: 10px 0 0;
  font-family: var(--sans);
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-2);
}
/* 过敏字段的可见 label 由分组标题承担；这里留一份给读屏 */
.p-hp__srlabel {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

/* ---------- 第一页：条目行 ---------- */
.p-hp__entries {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.p-hp__entry {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto 44px;
  align-items: center;
  gap: 8px;
  padding: 4px 4px 4px 8px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--surface);
  transition: border-color .18s ease;
}
.p-hp__entry:hover { border-color: var(--line-2); }
.p-hp__ename {
  width: 100%;
  min-width: 0;
  height: 44px; /* 触控目标 ≥ 44px */
  box-sizing: border-box; /* content-box 会让 100% + 内边距撑出条目卡片 */
  padding: 0 8px;
  border: 0;
  outline: 0;
  background: transparent;
  font-family: var(--sans);
  font-size: 13.5px;
  color: var(--ink);
}
.p-hp__ename::placeholder { color: var(--ink-3); }
.p-hp__etimes {
  display: flex;
  align-items: center;
  gap: 4px;
}
/* 紧凑年月只写 `2024-03`（PatientDatePicker 的 compact 形态）。
   100px = 「2024-03」约 50 + 箭头 11 + 间隙 6 + 左右内边距 20 + 余量 */
.p-hp__mon { flex: 0 0 100px; }
.p-hp__arrow {
  flex: none;
  font-family: var(--sans);
  font-size: 10px;
  color: var(--ink-3);
}
/* 结束位留空时显示「至今」——它是状态，不是错误，用主色提一档。
   只对结束位生效：开始位没填时仍应是普通占位色（"还没填"与"还在用"不是一回事）。 */
.p-hp__mon--end :deep(.p-dp__btn.is-empty) { color: var(--leaf-deep); }
.p-hp__del {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px; /* 触控目标 ≥ 44px */
  height: 44px;
  border: 0;
  border-radius: var(--r-xs);
  background: transparent;
  color: var(--ink-3);
  cursor: pointer;
  transition: color .18s ease, background .18s ease;
}
.p-hp__del:hover { color: var(--alert); background: var(--alert-soft); }
.p-hp__del svg { width: 12px; height: 12px; }
.p-hp__empty {
  margin: 0;
  font-family: var(--sans);
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-3);
}

/* ---------- 第一页：常用词 ---------- */
.p-hp__quick { margin-top: 10px; }
.p-hp__quickt {
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 0;
  border: 0;
  background: transparent;
  font-family: var(--sans);
  font-size: 11.5px;
  color: var(--leaf-deep);
  cursor: pointer;
}
.p-hp__quickt:hover { text-decoration: underline; }
.p-hp__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}
.p-hp__chip {
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 0 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-full);
  background: var(--surface);
  font-family: var(--sans);
  font-size: 12.5px;
  color: var(--ink);
  cursor: pointer;
  transition: border-color .18s ease, color .18s ease;
}
.p-hp__chip:hover:not(:disabled) { border-color: var(--leaf); color: var(--leaf-deep); }
/* 已加过的词弱化：它只是"快捷添加"，不表示选中；删除在条目行里做 */
.p-hp__chip:disabled {
  color: var(--ink-3);
  border-color: var(--line);
  cursor: default;
  opacity: .6;
}

/* ---------- 第一页：底部保存条 ---------- */
.p-hp__foot {
  flex: none;
  padding: 12px 24px 16px;
  border-top: 1px solid var(--line);
  background: var(--surface);
}
/* 保存成功的就地确认：主色浅底，3 秒后自己收掉。
   形态与错误条对称——同一位置、同一尺寸，患者不用重新找"结果在哪"。 */
.p-hp__saved {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 10px;
  padding: 10px 14px;
  border-radius: var(--r-sm);
  background: var(--leaf-soft);
  font-family: var(--sans);
  font-size: 12px;
  line-height: 1.6;
  color: var(--leaf-deep);
}
.p-hp__saved svg { flex: none; }
.p-hp__err {
  margin: 0 0 10px;
  padding: 10px 14px;
  border-left: 4px solid var(--alert);
  border-radius: var(--r-sm);
  background: var(--alert-soft);
  font-family: var(--sans);
  font-size: 12px;
  line-height: 1.7;
  color: var(--alert);
}
.p-hp__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 44px;
}
.p-hp__barstate {
  font-family: var(--sans);
  font-size: 11.5px;
  color: var(--ink-3);
}
.p-hp__barstate.is-dirty {
  color: var(--ink-2);
  font-weight: 600;
}
.p-hp__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.p-hp__btn {
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 0 16px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
  background: var(--surface);
  font-family: var(--sans);
  font-size: 12.5px;
  color: var(--ink-2);
  cursor: pointer;
  transition: border-color .18s ease, color .18s ease, background .18s ease;
}
.p-hp__btn:hover:not(:disabled) { border-color: var(--ink-3); color: var(--ink); }
.p-hp__btn:disabled { color: var(--ink-3); cursor: not-allowed; }
.p-hp__btn--primary {
  border-color: transparent;
  background: var(--leaf);
  color: var(--on-accent);
  font-weight: 600;
  box-shadow: var(--sh-primary);
}
.p-hp__btn--primary:hover:not(:disabled) { background: var(--leaf-deep); color: var(--on-accent); }
.p-hp__btn--primary:disabled { background: var(--line); color: var(--ink-3); box-shadow: none; }
.p-hp__btn--danger { border-color: transparent; background: transparent; color: var(--alert); }
.p-hp__btn--danger:hover:not(:disabled) { border-color: var(--alert); color: var(--alert); }

/* 关闭确认：替换保存条内容，留在原位（错误出现在出错的地方旁边） */
.p-hp__closeconfirm {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.p-hp__closetext {
  margin: 0;
  font-family: var(--sans);
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--ink);
}

/* ---------- 窄屏：左栏变顶部一行，浮层全屏 ---------- */
@media (max-width: 720px) {
  .p-hp { flex-direction: column; }
  .p-hp__side {
    width: auto;
    padding: 12px 12px 8px;
    overflow: visible;
  }
  .p-hp__brand { padding: 0 4px 12px; padding-right: 66px; }
  .p-hp__title { font-size: 16px; }
  .p-hp__mark { width: 32px; height: 32px; }
  .p-hp__nav {
    flex-direction: row;
    gap: 6px;
    overflow: hidden;
  }
  /* 三枚药丸均分一行：窄屏没有左栏，书签必须一眼看全，不能让第三页藏在横滚里 */
  .p-hp__tab {
    flex: 1 1 0;
    min-width: 0;
    padding: 8px;
    border-radius: var(--r-full);
    justify-content: center;
  }
  .p-hp__tabicon { display: none; }
  .p-hp__tabtext { flex: 0 1 auto; }
  .p-hp__tabdesc { display: none; }
  .p-hp__navnote { display: none; }
  .p-hp__tabname { font-size: 12.5px; }
  .p-hp__close { top: 10px; right: 10px; }

  /* 设置行在窄屏改成上下：标签与说明一行，控件撑满下一行 */
  .p-hp__row {
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
  }
  .p-hp__seg, .p-hp__birth { flex: none; width: 100%; }
  /* 条目折成两行：药名 + 删除在第一行，时间控件单独一行 */
  .p-hp__entry { grid-template-columns: minmax(0, 1fr) 44px; }
  .p-hp__etimes { grid-column: 1 / -1; grid-row: 2; }
  .p-hp__foot { padding: 12px 16px; padding-bottom: max(12px, env(safe-area-inset-bottom)); }
  .p-hp__bar { flex-wrap: wrap; }
  .p-hp__banner { flex-wrap: wrap; }
}
</style>
