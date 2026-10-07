<template>
  <div class="p-hello">
    <div class="p-hello__hd">
      <span class="p-hello__ava" aria-hidden="true">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 6h16v11H8l-4 4z"/><path d="M12 9v5M9.5 11.5h5"/></svg>
      </span>
      <div>
        <h2 class="p-hello__t">您好，我是您的陪诊助手</h2>
        <p class="p-hello__lead">先跟我说说哪里不舒服，我帮您判断该去哪个科。</p>
      </div>
    </div>

    <div class="p-hello__label">说 清 这 三 件 事 就 够 了</div>
    <div class="p-hello__three">
      <div class="p-hello__bub"><b>① 部 位</b><span>哪里不舒服</span></div>
      <div class="p-hello__bub"><b>② 时 间</b><span>大概多久了</span></div>
      <div class="p-hello__bub"><b>③ 感 觉</b><span>是怎么个难受法</span></div>
    </div>

    <p class="p-hello__eg">"右下方肚子疼了两天，一阵一阵的，还伴着恶心，没发烧。"</p>

    <div class="p-hello__label">大 家 常 问 的</div>
    <div class="p-hello__chips">
      <button
        v-for="c in common"
        :key="c"
        type="button"
        class="p-hello__chip"
        @click="emit('pick', c)"
      >{{ c }}</button>
    </div>
  </div>
</template>

<script setup>
/**
 * 空状态：一张还没填的陪诊卡。**空状态是行动邀请，不是一句客套话**——
 * 它要告诉患者"说清三件事就够"，再给一句像人写的例子。
 *
 * 三个部件卡**不是按钮**：别让人以为要点。（人体图功能落地后，「部位」那一格会变成
 * 真入口，到那时才换。）
 *
 * 常见主诉点了只是**填进输入框**，不直接发——患者还能补一句"还伴着恶心"，
 * 也不至于误触就烧掉一次模型调用。
 */
defineProps({
  /** 常见主诉词条 */
  common: { type: Array, default: () => [] }
})
const emit = defineEmits(['pick'])
</script>

<style scoped>
/* ---------- 空状态：陪诊卡 ----------
   问候行 + 「说清这三件事」+ 三张部件卡 + 示例句 + 常见主诉。见设计文档 §3.6。 */
.p-hello { display: flex; flex-direction: column; max-width: 640px; }
.p-hello__hd { display: flex; align-items: center; gap: 12px; }
.p-hello__ava {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: var(--r-sm);
  background: var(--leaf);
  color: var(--on-accent);
}
.p-hello__t {
  font-size: 26px; /* 巨型：一屏最大的那句话 */
  font-weight: 600;
  letter-spacing: .01em;
  line-height: 1.4;
}
.p-hello__lead { margin-top: 4px; font-size: 13.5px; line-height: 1.85; color: var(--ink-2); }
.p-hello__label {
  margin: 28px 0 12px;
  font-family: var(--sans);
  font-size: 10px;
  letter-spacing: .18em;
  color: var(--ink-2);
}
.p-hello__three { display: flex; gap: 12px; }
.p-hello__bub {
  flex: 1;
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--surface);
}
.p-hello__bub b {
  display: block;
  font-family: var(--sans);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: .14em;
  color: var(--leaf-deep);
}
.p-hello__bub span { display: block; margin-top: 8px; font-size: 12.5px; line-height: 1.7; color: var(--ink-2); }
.p-hello__eg {
  margin-top: 16px;
  padding: 12px 16px;
  border-left: 4px solid var(--leaf-soft);
  border-radius: var(--r-sm);
  background: var(--surface);
  font-size: 12.5px;
  line-height: 1.85;
  color: var(--ink-2);
}
.p-hello__chips { display: flex; flex-wrap: wrap; gap: 8px; }
.p-hello__chip {
  display: inline-flex;
  align-items: center;
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 0 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-full);
  background: var(--surface);
  font-family: var(--sans);
  font-size: 12.5px;
  color: var(--ink-2);
  cursor: pointer;
  transition: border-color .18s ease, color .18s ease, background .18s ease;
}
.p-hello__chip:hover {
  border-color: var(--leaf);
  background: var(--leaf-soft);
  color: var(--leaf-deep);
}
</style>