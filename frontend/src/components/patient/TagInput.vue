<template>
  <div class="p-tagbox">
    <!-- 标签就是已选值：点 × 就是删。× 的热区由 patient.css 的 .p-tag__x 撑到 44×44 -->
    <span v-for="term in modelValue" :key="term" class="p-tag">
      {{ term }}
      <button
        type="button"
        class="p-tag__x"
        :aria-label="`移除 ${term}`"
        @click="remove(term)"
      >
        <svg viewBox="0 0 10 10" fill="none" stroke="currentColor" stroke-width="1.8"
             stroke-linecap="round" aria-hidden="true">
          <path d="M1 1 9 9M9 1 1 9" />
        </svg>
      </button>
    </span>

    <!-- 焦点始终留在这个输入框上，建议列表用 aria-activedescendant 指向当前项
         （与 §3.9 自绘下拉同一套 ARIA 契约：Tab 的走向不被列表劫持） -->
    <input
      :id="id"
      ref="inputEl"
      class="p-tagbox__in"
      type="text"
      role="combobox"
      autocomplete="off"
      aria-autocomplete="list"
      :aria-labelledby="labelId"
      :aria-expanded="open ? 'true' : 'false'"
      :aria-controls="open ? listId : undefined"
      :aria-activedescendant="open && hl >= 0 ? optionId(hl) : undefined"
      :value="text"
      :placeholder="shownPlaceholder"
      @input="onInput"
      @keydown="onKeydown"
      @focus="focused = true"
      @blur="onBlur"
    />

    <!-- 建议列表**在流里**（相对定位会把浮层滚动容器的边裁掉）。词表以前是一排常驻 chips，
         现在退到这里：聚焦即列出、打字即过滤——它的用处是省打字，不是常驻版面。 -->
    <div v-if="open" class="p-tagbox__sugbox">
      <div class="p-tagbox__suglb" aria-hidden="true">常 见 的 选 项</div>
      <ul :id="listId" class="p-tagbox__sug" role="listbox" :aria-labelledby="labelId">
        <li
          v-for="(option, i) in candidates"
          :id="optionId(i)"
          :key="option.id || option.term"
          class="p-tagbox__opt"
          :class="{ 'is-hl': i === hl }"
          role="option"
          :aria-selected="i === hl"
          @mousedown.prevent="pick(option)"
          @mouseenter="hl = i"
        >{{ option.term }}</li>
      </ul>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref } from 'vue'

/**
 * 标签输入框（患者端自绘）：一个字段只有**一个**写入口。
 *
 * <p>为什么要它：过敏史原来有两个入口（一排常驻 chips + 一个「其他」自由文本），用药/手术
 * 有三个（chips + 条目 + 「其他」）——患者一个字段要面对两三个地方，而同一个值只能存一处。
 * 现在「写进去的字」与「已选的值」是同一个东西：按回车/逗号就成标签，点 × 就是删。
 *
 * <p>词表没有删，它换了位置：从常驻的一排 chips 变成**建议列表**（聚焦即列出、打字即过滤）。
 * 少了它，不知道药名怎么写的人在空态下会僵住；常驻它，同一信息就在屏幕上出现两遍
 * （chip 既表示可选项、又表示已选态）。
 *
 * <p>重复值不重复加、超上限不加（`overlimit` 交给调用方提示）——与后端二次校验同一口径。
 */
const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  /** 词表：`[{ id, term }]`，由调用方按类别过滤好再传进来（组件内不写死任何词条） */
  options: { type: Array, default: () => [] },
  /** 最多可选几条；到顶写不进并抛 `overlimit` */
  max: { type: Number, default: 10 },
  placeholder: { type: String, default: '写一个，回车落下' },
  id: { type: String, default: undefined },
  /** 外部那枚 label 的 id（同 PatientSelect 的写法） */
  labelId: { type: String, default: undefined }
})
const emit = defineEmits(['update:modelValue', 'overlimit'])

const uid = Math.random().toString(36).slice(2, 8)
const listId = `p-tag-list-${uid}`
const optionId = (i) => `${listId}-${i}`

const inputEl = ref(null)
const text = ref('')
const focused = ref(false)
const suppressed = ref(false)
const hl = ref(-1)

const shownPlaceholder = computed(() =>
  props.modelValue.length ? '还有别的就接着写' : props.placeholder
)

/** 建议 = 词表里**还没选过**的项，有输入时按子串过滤（中文短词，不做模糊） */
const candidates = computed(() => {
  const chosen = new Set(props.modelValue)
  const keyword = text.value.trim()
  return props.options
    .filter((o) => o && o.term && !chosen.has(o.term))
    .filter((o) => !keyword || o.term.includes(keyword))
})

const open = computed(() => focused.value && !suppressed.value && candidates.value.length > 0)

function onInput(event) {
  text.value = event.target.value
  suppressed.value = false
  hl.value = -1
  // 逗号/顿号/分号是中文里最自然的分隔符：打着打着就落下，不用按回车
  if (/[，,、;；]$/.test(text.value)) commit(text.value)
}

function onKeydown(event) {
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    if (!open.value) {
      suppressed.value = false
      return
    }
    event.preventDefault()
    const count = candidates.value.length
    const delta = event.key === 'ArrowDown' ? 1 : -1
    hl.value = hl.value < 0
      ? (delta > 0 ? 0 : count - 1)
      : (hl.value + delta + count) % count
    return
  }
  if (event.key === 'Enter') {
    event.preventDefault()
    if (open.value && hl.value >= 0) pick(candidates.value[hl.value])
    else commit(text.value)
    return
  }
  if (event.key === 'Tab' && text.value.trim()) {
    // Tab 照常走焦点，但先把手里这句落下——不让人以为「我明明写了」
    commit(text.value)
    return
  }
  if (event.key === 'Escape' && open.value) {
    event.preventDefault()
    suppressed.value = true
    hl.value = -1
  }
}

/**
 * 失焦即落下：不落下就等于**静默丢弃**患者刚写的字（保存时他只提交已选标签）。
 * 这条与「取不到不等于没有」同源——界面上的字必须真的进得去。
 */
function onBlur() {
  focused.value = false
  hl.value = -1
  if (text.value.trim()) commit(text.value)
}

function pick(option) {
  commit(option.term)
  nextTick(() => inputEl.value?.focus())
}

function remove(term) {
  emit('update:modelValue', props.modelValue.filter((t) => t !== term))
}

function commit(raw) {
  const value = String(raw || '').trim().replace(/[，,、;；]+$/, '').trim()
  text.value = ''
  hl.value = -1
  if (!value) return
  if (props.modelValue.includes(value)) return
  if (props.modelValue.length >= props.max) {
    emit('overlimit', props.max)
    return
  }
  emit('update:modelValue', [...props.modelValue, value])
}
</script>
