<template>
  <button
    type="button"
    class="p-act__btn"
    :class="{ 'is-copied': copied }"
    :aria-label="copied ? copiedAria : ariaLabel"
    @click="emit('copy')"
  >
    <svg v-if="!copied" width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><rect x="3.5" y="3.5" width="7" height="7" fill="none" stroke="currentColor"/><path d="M8.5 3.5v-2h-7v7h2" fill="none" stroke="currentColor"/></svg>
    <svg v-else width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><path d="M2 6.5 5 9.5 10 3.5" fill="none" stroke="currentColor" stroke-width="1.4"/></svg>
    {{ copied ? copiedText : text }}
  </button>
</template>

<script setup>
/**
 * 一键复制按钮（`.p-act__btn` 的唯一使用方式）。
 *
 * 外观与"桌面 hover 才浮现"在共享件 `patient.css` 里；本组件只管**文案与状态**，
 * 因为那两枚图标 + `aria-label` + 「复 制 / 已 复 制」在五个消费点（用户气泡 / 追问 /
 * 资料回答 / AI 回复 / 结论卡）里逐字重复——留一份在这里，改文案只改一处。
 *
 * **它不自己复制**：写剪贴板要 fallback（`navigator.clipboard` 在非安全上下文不可用），
 * 那是 `utils/clipboard.js` 的事；谁owns 那次调用、谁决定复位时机，由调用方管
 * （对话页按条目索引记 `copiedKey`，结论卡自己记一个布尔）。
 */
defineProps({
  /** 是否已复制（决定图标与文案） */
  copied: { type: Boolean, default: false },
  /** 未复制时的无障碍名，要说清复制的是**哪一条** */
  ariaLabel: { type: String, required: true },
  /** 已复制时的无障碍名；留空则沿用 ariaLabel */
  copiedAria: { type: String, default: '' },
  /** 未复制时的可见文案 */
  text: { type: String, default: '复 制' },
  /** 已复制时的可见文案 */
  copiedText: { type: String, default: '已 复 制' }
})
const emit = defineEmits(['copy'])
</script>