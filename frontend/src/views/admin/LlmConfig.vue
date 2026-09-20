<template>
  <section class="a-grid" style="grid-template-columns: 1fr 1fr">
    <!-- 模型接入 -->
    <div class="a-panel">
      <div class="a-panel__head">
        <span class="a-panel__title">模型接入</span>
        <span class="a-panel__hint">密钥存 application-local.yml，不进库</span>
      </div>

      <div v-if="loading" class="a-empty">加载中…</div>
      <div v-else-if="loadError" class="a-empty">{{ loadError }}</div>
      <template v-else>
        <div class="a-field">
          <label class="a-field__label">对话模型（DeepSeek）</label>
          <input class="a-field__input" :value="models.chatModel || '—'" readonly />
          <div class="a-field__hint">
            {{ models.chatBaseUrl || '—' }} ·
            <span class="a-tag" :class="models.chatKeySet ? 'a-tag--ok' : 'a-tag--warn'">
              {{ models.chatKeySet ? '密钥已配置' : '密钥未配置' }}
            </span>
            <span v-if="probeOf('chat')" class="a-tag" :class="probeClass('chat')">
              {{ probeStatus('chat') }}
            </span>
          </div>
          <div v-if="probeFailed('chat')" class="a-field__hint">
            失败原因：{{ probeOf('chat').detail }}
          </div>
        </div>

        <div class="a-field">
          <label class="a-field__label">向量模型（阿里 · embedding）</label>
          <input class="a-field__input" :value="models.embeddingModel || '—'" readonly />
          <div class="a-field__hint">
            维度 {{ models.embeddingDim || '—' }} · 与 pgvector 表结构一致，换模型需重建向量
            <span v-if="probeOf('embedding')" class="a-tag" :class="probeClass('embedding')">
              {{ probeStatus('embedding') }}
            </span>
          </div>
          <div v-if="probeFailed('embedding')" class="a-field__hint">
            失败原因：{{ probeOf('embedding').detail }}
          </div>
        </div>

        <div class="a-field">
          <label class="a-field__label">重排模型（阿里 · rerank）</label>
          <input class="a-field__input" :value="models.rerankModel || '—'" readonly />
          <div class="a-field__hint">
            检索链路：召回 → RRF 融合 → 重排 Top-N
            <span v-if="probeOf('rerank')" class="a-tag" :class="probeClass('rerank')">
              {{ probeStatus('rerank') }}
            </span>
          </div>
          <div v-if="probeFailed('rerank')" class="a-field__hint">
            失败原因：{{ probeOf('rerank').detail }}
          </div>
        </div>
      </template>

      <button class="a-btn" :disabled="probing || loading" @click="runProbe">
        {{ probing ? '测试中…' : '连通性测试' }}
      </button>
      <div class="a-field__hint">三路各发一次最小请求，只测通不通，不写入任何数据</div>
    </div>

    <!-- 检索参数（sys_config 运行时字典表） -->
    <div class="a-panel">
      <div class="a-panel__head">
        <span class="a-panel__title">检索与聚合参数</span>
        <span class="a-panel__hint">存 sys_config，管理端可调</span>
      </div>

      <div v-if="loading" class="a-empty">加载中…</div>
      <div v-else-if="loadError" class="a-empty">{{ loadError }}</div>
      <template v-else>
        <div v-for="p in params" :key="p.key" class="a-field">
          <label class="a-field__label">{{ p.label }}</label>
          <div v-if="p.type === 'bool'" class="a-chips" style="margin: 0">
            <button class="a-chip" :class="{ 'a-chip--on': isTrue(p.value) }" @click="p.value = 'true'">
              开启
            </button>
            <button class="a-chip" :class="{ 'a-chip--on': !isTrue(p.value) }" @click="p.value = 'false'">
              关闭
            </button>
          </div>
          <input
            v-else
            v-model="p.value"
            class="a-field__input"
            type="number"
            :step="p.type === 'decimal' ? '0.01' : '1'"
          />
          <div class="a-field__hint">
            {{ p.remark }}
            <template v-if="p.range"> · 取值范围 {{ p.range }}</template>
            · 默认 {{ p.defaultValue }}
          </div>
        </div>

        <button class="a-btn" :disabled="saving || loading" @click="save">
          {{ saving ? '保存中…' : '保存配置' }}
        </button>
        <div class="a-field__hint">保存后立即生效（后端会失效参数缓存，不用等 60 秒）</div>
      </template>
    </div>
  </section>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getLlmModels, getLlmParams, probeLlm, saveLlmParams } from '../../api/admin'

const models = ref({})
const params = ref([])
const probes = ref([])
const loading = ref(true)
const loadError = ref('')
const probing = ref(false)
const saving = ref(false)

function errText(e) {
  return e?.message || '操作失败，请稍后重试'
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [modelInfo, paramList] = await Promise.all([getLlmModels(), getLlmParams()])
    models.value = modelInfo
    params.value = paramList
  } catch (e) {
    loadError.value = errText(e)
  } finally {
    loading.value = false
  }
}

function isTrue(value) {
  return String(value).toLowerCase() === 'true'
}

function probeOf(kind) {
  return probes.value.find((r) => r.kind === kind)
}

function probeStatus(kind) {
  const r = probeOf(kind)
  if (!r) return ''
  return r.ok ? `连通正常 ${r.latencyMs}ms` : '连通失败'
}

function probeClass(kind) {
  const r = probeOf(kind)
  return r && r.ok ? 'a-tag--ok' : 'a-tag--warn'
}

function probeFailed(kind) {
  const r = probeOf(kind)
  return !!r && !r.ok
}

async function runProbe() {
  probing.value = true
  try {
    probes.value = await probeLlm()
    const failed = probes.value.filter((r) => !r.ok)
    if (failed.length === 0) {
      ElMessage.success('三路模型连通性正常')
    } else {
      ElMessage.warning(`${failed.length} 路探测失败：${failed.map((r) => r.name).join('、')}`)
    }
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    probing.value = false
  }
}

async function save() {
  saving.value = true
  try {
    await saveLlmParams(params.value.map((p) => ({ key: p.key, value: String(p.value) })))
    ElMessage.success('配置已保存，即时生效')
    // 回读一次：后端会把值规范化（如 TRUE → true），页面对齐落库结果
    params.value = await getLlmParams()
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>
