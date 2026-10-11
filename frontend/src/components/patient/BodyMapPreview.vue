<template>
  <main class="patient-root body-map-preview">
    <section class="body-map-preview__content">
      <h1>智能导诊</h1>
      <p>{{ result?.sentence || '请选择不适部位' }}</p>
      <button type="button" class="p-btn" @click="open = true">选择不适部位</button>
    </section>
    <BodyMapOverlay v-model:open="open" @submit="result = $event" />
  </main>
</template>

<script setup>
import { ref } from 'vue'
import '../../styles/patient.css'
import BodyMapOverlay from './BodyMapOverlay.vue'

const open = ref(true)
const result = ref(null)
</script>

<style>
.body-map-preview { display: grid; place-items: center; min-height: 100dvh; }
.body-map-preview__content { width: min(360px, calc(100% - 40px)); text-align: center; }
.body-map-preview__content h1 { margin-bottom: 16px; font-size: 24px; }
.body-map-preview__content p { margin-bottom: 20px; }
</style>
