<template>
  <main class="patient-root body-map-preview">
    <section class="body-map-preview__content">
      <h1>智能导诊</h1>
      <p>{{ result?.sentence || '请选择不适部位' }}</p>
      <button type="button" class="p-btn" @click="open = true">选择不适部位</button>
      <!-- 回执里那个 24×56 的缩略图：`show-sides=false` 关掉左右方位字（与 EmptyState 的锁定回执同一条路径） -->
      <p class="body-map-preview__mini" aria-label="回执缩略图">
        <HumanBodyFigure
          :face="lockedFace"
          :selected="(id) => pickedRegions.includes(id)"
          :enabled="() => true"
          :side-of="miniSideOf"
          :show-sides="false"
        />
      </p>
    </section>
    <BodyMapOverlay v-model:open="open" @submit="result = $event" />
  </main>
</template>

<script setup>
import { computed, ref } from 'vue'
import '../../styles/patient.css'
import BodyMapOverlay from './BodyMapOverlay.vue'
import HumanBodyFigure from './HumanBodyFigure.vue'
import { regionsOfLocations, sidesOfLocations } from '../../utils/bodyMap'

const open = ref(true)
const result = ref(null)
const pickedRegions = computed(() => regionsOfLocations(result.value?.locations || []))
const lockedFace = computed(() => pickedRegions.value.length && pickedRegions.value.every(id => ['back', 'waist'].includes(id)) ? 'back' : 'front')
const miniSides = computed(() => sidesOfLocations(result.value?.locations || []))
const miniSideOf = (id) => miniSides.value[id] || ''
</script>

<style>
.body-map-preview { display: grid; place-items: center; min-height: 100dvh; }
.body-map-preview__content { width: min(360px, calc(100% - 40px)); text-align: center; }
.body-map-preview__content h1 { margin-bottom: 16px; font-size: 24px; }
.body-map-preview__content p { margin-bottom: 20px; }
.body-map-preview__mini { width: 24px; height: 56px; margin: 20px auto 0; }
</style>
