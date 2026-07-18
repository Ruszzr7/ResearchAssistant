<template>
  <section class="formula-region-card" aria-label="当前公式区域">
    <div class="formula-region-card__heading">
      <div>
        <b>识别公式</b>
        <small>第 {{ region?.page }} 页</small>
      </div>
      <button type="button" aria-label="清除公式区域" @click="$emit('clear')">×</button>
    </div>

    <img
      v-if="recognition?.previewDataUrl"
      class="formula-region-card__preview"
      :src="recognition.previewDataUrl"
      alt="从原始 PDF 裁剪的公式区域"
    />
    <div v-else class="formula-region-card__preview-placeholder">
      {{ loading ? '正在从原始 PDF 裁剪并识别…' : '公式区域预览' }}
    </div>

    <div v-if="recognition" class="formula-region-card__meta">
      <el-tag size="small" :type="statusTagType" effect="plain">{{ statusLabel }}</el-tag>
      <span>{{ sourceLabel }}</span>
      <span v-if="recognition.source === 'MULTIMODAL'">
        {{ Math.round((recognition.confidence || 0) * 100) }}%
      </span>
    </div>

    <div v-if="renderedLatex" class="formula-region-card__rendered" v-html="renderedLatex" />
    <div v-else-if="recognition && !loading" class="formula-region-card__empty">
      尚无可预览公式，可在下方手动填写 LaTeX。
    </div>

    <el-input
      v-if="recognition"
      v-model="draftLatex"
      type="textarea"
      :rows="3"
      maxlength="4000"
      show-word-limit
      placeholder="核对或填写 LaTeX，不要包含 $$ 分隔符"
      aria-label="公式 LaTeX"
    />
    <p v-if="recognition?.message" class="formula-region-card__message">{{ recognition.message }}</p>
    <p v-if="error" class="formula-region-card__error">{{ error }}</p>

    <div class="formula-region-card__actions">
      <el-button size="small" :loading="loading" @click="$emit('retry')">重新识别</el-button>
      <el-button
        v-if="recognition"
        type="primary"
        size="small"
        :loading="confirming"
        :disabled="!canConfirm"
        @click="$emit('confirm', draftLatex.trim())"
      >{{ recognition.confirmed ? '保存校正并固定' : '确认并固定' }}</el-button>
    </div>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import katex from 'katex'

const props = defineProps({
  region: { type: Object, required: true },
  recognition: { type: Object, default: null },
  loading: { type: Boolean, default: false },
  confirming: { type: Boolean, default: false },
  error: { type: String, default: '' },
})

defineEmits(['clear', 'retry', 'confirm'])

const draftLatex = ref('')

watch(
  () => [props.recognition?.id, props.recognition?.latex],
  () => { draftLatex.value = props.recognition?.latex || '' },
  { immediate: true },
)

const renderedLatex = computed(() => {
  const latex = draftLatex.value.trim()
  if (!latex) return ''
  try {
    return katex.renderToString(latex, {
      displayMode: true,
      throwOnError: false,
      trust: false,
      strict: 'ignore',
      maxExpand: 1000,
      output: 'htmlAndMathml',
    })
  } catch {
    return ''
  }
})

const canConfirm = computed(() => Boolean(
  props.recognition?.id && draftLatex.value.trim() && !props.loading && !props.confirming,
))
const statusLabel = computed(() => ({
  CONFIRMED: '已确认',
  CANDIDATE: '待确认',
  REGION: '区域模式',
}[props.recognition?.status] || '待识别'))
const statusTagType = computed(() => ({
  CONFIRMED: 'success',
  CANDIDATE: 'warning',
  REGION: 'info',
}[props.recognition?.status] || 'info'))
const sourceLabel = computed(() => ({
  LAYOUT: '版面 LaTeX',
  MULTIMODAL: '图像识别候选',
  USER: '用户已校正',
}[props.recognition?.source] || ''))
</script>

<style scoped>
.formula-region-card { padding: 13px 14px; border-bottom: 1px solid var(--ra-border); }
.formula-region-card__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; margin-bottom: 9px; }
.formula-region-card__heading > div { display: flex; flex-direction: column; gap: 2px; }
.formula-region-card__heading b { font-size: 13px; }
.formula-region-card__heading small { color: var(--ra-text-tertiary); font-size: 10px; }
.formula-region-card__heading button { border: 0; color: var(--ra-text-secondary); background: transparent; cursor: pointer; font-size: 18px; }
.formula-region-card__preview { display: block; width: 100%; max-height: 150px; box-sizing: border-box; object-fit: contain; border: 1px solid var(--ra-border); border-radius: 6px; background: #fff; }
.formula-region-card__preview-placeholder { display: grid; min-height: 72px; place-items: center; border: 1px dashed var(--ra-border); border-radius: 6px; color: var(--ra-text-tertiary); font-size: 10px; }
.formula-region-card__meta { display: flex; align-items: center; gap: 7px; margin: 8px 0; color: var(--ra-text-tertiary); font-size: 10px; }
.formula-region-card__rendered { overflow-x: auto; margin: 8px 0; padding: 8px; border-radius: 6px; background: var(--ra-hover-bg); color: var(--ra-text); text-align: center; }
.formula-region-card__rendered :deep(.katex-display) { margin: 0; }
.formula-region-card__empty { margin: 8px 0; padding: 8px; border-radius: 6px; color: var(--ra-text-tertiary); background: var(--ra-hover-bg); font-size: 10px; line-height: 1.45; }
.formula-region-card__message, .formula-region-card__error { margin: 7px 0 0; color: var(--ra-text-tertiary); font-size: 10px; line-height: 1.45; }
.formula-region-card__error { color: var(--el-color-danger); }
.formula-region-card__actions { display: flex; justify-content: flex-end; gap: 7px; margin-top: 9px; }
</style>
