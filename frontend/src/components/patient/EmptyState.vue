<template>
  <div class="p-hello">
    <div class="p-hello__hd">
      <span class="p-hello__ava" aria-hidden="true">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 6h16v11H8l-4 4z"/><path d="M12 9v5M9.5 11.5h5"/></svg>
      </span>
      <div>
        <h2 class="p-hello__t">您好，我是您的导诊助手</h2>
        <p class="p-hello__lead">先在人体图上点一下哪里不舒服，我帮您判断该去哪个科；细节我们后面慢慢说。</p>
      </div>
    </div>

    <!-- 锁定后：三格还在（① 打上勾），下面换成锁定回执 + 重选（草案 06 的 is-ready 形态） -->
    <div class="p-hello__label">说 清 这 三 件 事 就 够 了</div>
    <div class="p-hello__three">
      <div class="p-hello__bub p-hello__bub--req">
        <span class="p-hello__must">必填</span>
        <b>① 部 位</b>
        <span class="p-hello__bubval">{{ partLabel ? `${partLabel} ✓` : '在图上选，或说"说不清"' }}</span>
      </div>
      <div class="p-hello__bub">
        <span class="p-hello__opt">随后问</span>
        <b>② 时 间</b>
        <span>大概多久了</span>
      </div>
      <div class="p-hello__bub">
        <span class="p-hello__opt">随后问</span>
        <b>③ 感 觉</b>
        <span>是怎么个难受法</span>
      </div>
    </div>

    <!-- 等待态：大按钮 + 示例句 + 快捷词（草案 06 的 is-guide 形态）。
         锁定后这一整块换成下面的回执——「必填的下一步」已经完成，再摆着只会让人以为还没生效 -->
    <template v-if="stage === 'awaiting'">
      <button type="button" class="p-hello__pick" @click="emit('pickPart')">
        <span class="p-hello__pickic" aria-hidden="true">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"><circle cx="12" cy="5" r="2.4"/><path d="M12 7.6v6M12 13.6l-3.2 6M12 13.6l3.2 6M7.6 10h8.8"/></svg>
        </span>
        <span class="p-hello__pickt">
          <b>在人体图上选部位</b>
          <small>第一步 · 大约 10 秒</small>
        </span>
        <span class="p-hello__pickar" aria-hidden="true">→</span>
      </button>

      <p class="p-hello__eg">"右下方肚子疼了两天，一阵一阵的，还伴着恶心，没发烧。"</p>

      <div class="p-hello__label">常 见 主 诉 · 点 了 直 接 在 图 上 选 中</div>
      <div class="p-hello__chips">
        <button
          v-for="q in quickPicks"
          :key="q.label"
          type="button"
          class="p-hello__chip"
          @click="emit('quick', q)"
        >{{ q.label }} <em>→ {{ q.word }}</em></button>
      </div>
    </template>

    <!-- 锁定回执（草案 06）：选没选都算完成「声明」这一步，区别只写在回执里 -->
    <div v-else class="p-hello__locked">
      <div class="p-hello__lockedrow">
        <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true"><path d="M4 12.5l5 5L20 6.5"/></svg>
        <span>
          <template v-if="partLabel"><b>位置已锁定：{{ partLabel }}</b> —— 接着说一句就行，剩下来的我来问。</template>
          <template v-else><b>没选位置，直接描述也行</b> —— 我会边听边判断该往哪个科靠。</template>
        </span>
      </div>
      <button type="button" class="p-hello__relink" @click="emit('rechoose')">位置选错了？重新选</button>
    </div>
  </div>
</template>

<script setup>
import { QUICK_PICKS as quickPicks } from '../../utils/bodyMap'

/**
 * 空状态：一张还没填的导诊卡（草案 06 的引导 / 锁定两个形态）。
 *
 * **三格 slots 常驻**——它是"要说清什么"的说明；等待态下面是大按钮 + 示例句 + 快捷词，
 * 锁定后换成回执 + 重选。「必填的是这一步动作，不是必须点中某个区域」：
 * 回执里连"没选位置"也写成完成态（说不清在哪儿是出口，不是失败）。
 *
 * 快捷词点了**直接打开图并预选**对应区域（`quick` 事件带 `{ region, word }`），
 * 不再是旧的"填进输入框"——那会让必填形同虚设。词表见 bodyMap.js 的 QUICK_PICKS
 * （「发热咳嗽」刻意不在列：它对应不了任何体表区域，留着等于给必开开后门）。
 */
defineProps({
  /** awaiting = 还没声明（等待态）；locked = 已声明（选了或点了"说不清"都算） */
  stage: {
    type: String,
    default: 'awaiting',
    validator: (v) => ['awaiting', 'locked'].includes(v)
  },
  /** 已声明的部位串（如「左腹部」）；空串 = 走了"说不清"出口 */
  partLabel: { type: String, default: '' }
})
const emit = defineEmits(['pickPart', 'quick', 'rechoose'])
</script>

<style scoped>
/* ---------- 空状态：导诊卡 ----------
   问候行 + 三格 slots + （等待态：大按钮/示例/快捷词 ｜ 锁定态：回执/重选）。见设计文档 §3.6。 */
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

/* 三格 slots（草案 06）：必填格描主色，另两格带「随后问」角标 */
.p-hello__three { display: flex; gap: 12px; }
.p-hello__bub {
  position: relative;
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
.p-hello__bub--req { border-color: var(--leaf); background: var(--leaf-soft); }
.p-hello__bub--req b { color: var(--leaf-deep); }
.p-hello__bub--req span { color: var(--leaf-deep); font-weight: 600; }
.p-hello__must,
.p-hello__opt {
  position: absolute;
  right: 11px;
  top: 11px;
  padding: 2px 7px;
  border-radius: var(--r-full);
  font-size: 9.5px;
  font-weight: 600;
  letter-spacing: .08em;
}
.p-hello__must { background: var(--leaf); color: var(--on-accent); }
.p-hello__opt { border: 1px solid var(--line-2); color: var(--ink-3); font-weight: 400; }

/* 大按钮（草案 06 的 bigpick）：这一屏唯一的实心主色块——它是必填的第一步 */
.p-hello__pick {
  display: flex;
  align-items: center;
  gap: 14px;
  width: 100%;
  margin-top: 20px;
  padding: 17px 20px;
  border: 0;
  border-radius: var(--r-md);
  background: var(--leaf);
  color: var(--on-accent);
  font-family: var(--sans);
  text-align: left;
  cursor: pointer;
  box-shadow: var(--sh-primary);
  transition: background .18s ease, transform .18s ease;
}
.p-hello__pick:hover { background: var(--leaf-deep); transform: translateY(-1px); }
.p-hello__pick:focus-visible { outline: 2px solid var(--leaf-deep); outline-offset: 2px; }
.p-hello__pickic {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: var(--r-sm);
  background: rgba(255, 255, 255, .18);
}
.p-hello__pickt { flex: 1; min-width: 0; }
.p-hello__pickt b { display: block; font-size: 15px; font-weight: 600; }
.p-hello__pickt small { display: block; margin-top: 5px; font-size: 11.5px; color: rgba(255, 255, 255, .82); }
.p-hello__pickar { flex: none; font-size: 18px; opacity: .85; }

.p-hello__eg {
  margin-top: 20px;
  padding: 12px 16px;
  border-left: 4px solid var(--leaf-soft);
  border-radius: var(--r-sm);
  background: var(--surface);
  font-size: 12.5px;
  line-height: 1.85;
  color: var(--ink-2);
}

/* 快捷词（草案 06）：点了直接在图上选中，箭头指向的就是会预选的词 */
.p-hello__chips { display: flex; flex-wrap: wrap; gap: 8px; }
.p-hello__chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
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
.p-hello__chip em { font-style: normal; color: var(--leaf-deep); font-weight: 600; }
.p-hello__chip:hover {
  border-color: var(--leaf);
  background: var(--leaf-soft);
  color: var(--leaf-deep);
}

/* 锁定回执（草案 06）：完成信号 + 重选入口 */
.p-hello__locked { margin-top: 20px; }
.p-hello__lockedrow {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 11px 15px;
  border-radius: var(--r-sm);
  background: var(--leaf-soft);
  color: var(--leaf-deep);
  font-size: 13px;
  line-height: 1.7;
}
.p-hello__lockedrow svg { flex: none; margin-top: 3px; }
.p-hello__lockedrow b { font-weight: 600; }
.p-hello__relink {
  min-height: 44px; /* 触控目标 ≥ 44px */
  margin-top: 6px;
  padding: 0 2px;
  background: none;
  border: 0;
  border-bottom: 1px dashed var(--line-2);
  font-family: var(--sans);
  font-size: 11.5px;
  color: var(--ink-3);
  cursor: pointer;
  transition: color .18s ease, border-color .18s ease;
}
.p-hello__relink:hover { color: var(--leaf-deep); border-color: var(--leaf); }
</style>