<template>
  <section class="a-panel">
    <div class="a-panel__head">
      <span class="a-panel__title">审核队列 · 错误模式聚合桶</span>
      <span class="a-panel__hint">待审列表 · 证据回放 · 根因归因 · approve 回流</span>
    </div>

    <!-- 空表不渲染表头（前端设计方案 §3.5），整块换成空态并说清"为什么没有"（§3.8） -->
    <div class="a-empty">
      审核队列还没有接通：当前<b>没有任何待审条目</b>，因为这个页面背后的三块都还没做——
      <div class="rev-todo">
        <p>① <b>聚合归桶</b>（离线侧，链路 C 后半）：把同方向的错误记录聚成 <code>cluster_bucket</code>、达阈值升 <code>review_task</code>。这两张表现在是<b>空表</b>，没有它就没有"待审"这件事</p>
        <p>② <b>读与审</b>：待审列表、证据快照回放、根因归因、驳回 / 忽略 / 修正重审的后端接口</p>
        <p>③ <b>approve 产出</b>：写映射台账 + 生成合成 chunk 走链路 B 入库（闭环生效的唯一机制）</p>
      </div>
      <p class="a-panel__hint" style="margin-top: 14px">
        在此之前本页只作占位：不放任何假数据，页面上的数字会直接被当成待办数去处理。
        设计与施工顺序见《总体架构与链路设计.md》链路 C 与 进度.md「按依赖顺序开工」。
      </p>
    </div>
  </section>
</template>

<script setup>
// 本页暂无可交互逻辑：审核队列的读与审依赖链路 C 后半（聚合归桶）先产出 cluster_bucket / review_task。
//
// 2026-09-26 清掉的是**整页假数据**（科室池、聚合桶、回流预览模板，以及一份本地镜像的根因字典）。
// 镜像那份字典的唯一定义源本来就是后端 `feedback/enums/RootCauseKey`，重新开工时从后端取，
// 不要在页面里再抄一份——抄一份就会与看板口径漂移（见 .claude/rules/数据库设计.md §0）。
</script>

<style scoped>
.rev-todo {
  max-width: 760px;
  margin: 16px auto 0;
  text-align: left;
  line-height: 1.9;
  color: var(--ink-2);
}
.rev-todo p {
  margin: 0 0 6px;
}
.rev-todo code {
  background: var(--wash);
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 12px;
}
</style>
