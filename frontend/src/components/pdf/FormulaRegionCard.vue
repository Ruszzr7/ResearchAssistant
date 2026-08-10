<template>
  <section class="formula-region-card" aria-label="当前公式选区">
    <div class="formula-region-card__heading">
      <div>
        <b>固定公式内容</b>
        <small>第 {{ region?.page }} 页</small>
      </div>
      <button type="button" aria-label="清除公式区域" @click="$emit('clear')">×</button>
    </div>

    <img
      v-if="visiblePreviewDataUrl"
      class="formula-region-card__preview"
      :src="visiblePreviewDataUrl"
      alt="从原始 PDF 裁剪的公式区域"
    />
    <div v-else class="formula-region-card__preview-placeholder">
      {{ loading ? '正在准备公式并转换为 LaTeX…' : '已框选公式；固定时会自动生成可编辑 LaTeX。' }}
    </div>
    <p v-if="!recognition && !loading" class="formula-region-card__message">
      点击“固定内容”后才会调用公式转换；未经确认的模型结果不会直接进入对话。
    </p>

    <div v-if="recognition" class="formula-region-card__meta">
      <div class="formula-region-card__meta-info">
        <el-tag size="small" :type="statusTagType" effect="plain">{{ statusLabel }}</el-tag>
        <span>{{ sourceLabel }}</span>
        <span v-if="recognition.source === 'MULTIMODAL'">
          {{ Math.round((recognition.confidence || 0) * 100) }}%
        </span>
      </div>
      <div class="formula-region-card__actions formula-region-card__actions--inline">
        <el-button
          size="small"
          :loading="loading"
          @click="$emit('retry')"
        >重新转换 LaTeX</el-button>
        <el-button
          type="primary"
          size="small"
          :loading="confirming"
          :disabled="!canConfirm"
          @click="$emit('confirm', confirmedFormulas)"
        >{{ recognition.confirmed ? '保存校正' : '确认固定' }}</el-button>
      </div>
    </div>

    <div v-if="renderedFormulas.length" class="formula-region-card__rendered-list">
      <div
        v-for="(rendered, index) in renderedFormulas"
        :key="`rendered-${index}`"
        class="formula-region-card__rendered"
        v-html="rendered"
      />
    </div>
    <div v-else-if="recognition && !loading" class="formula-region-card__empty">
      尚无可预览公式，可在下方手动填写 LaTeX。
    </div>

    <div v-if="recognition" class="formula-region-card__editor">
      <div
        v-for="(_, index) in draftFormulas"
        :key="`formula-${index}`"
        class="formula-region-card__formula-editor"
      >
        <small v-if="draftFormulas.length > 1">公式 {{ index + 1 }}</small>
        <el-input
          v-model="draftFormulas[index]"
          type="textarea"
          :rows="draftFormulas.length > 1 ? 2 : 3"
          maxlength="4000"
          show-word-limit
          placeholder="核对或填写 LaTeX，不要包含 $$ 分隔符"
          :aria-label="`公式 ${index + 1} LaTeX`"
        />
      </div>
    </div>
    <p v-if="error" class="formula-region-card__error">{{ error }}</p>

    <div v-if="!recognition" class="formula-region-card__actions">
      <el-button
        type="primary"
        size="small"
        :loading="loading"
        @click="$emit('retry')"
      >固定内容</el-button>
    </div>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import katex from 'katex'

const props = defineProps({
  region: { type: Object, required: true },
  recognition: { type: Object, default: null },
  previewDataUrl: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  confirming: { type: Boolean, default: false },
  error: { type: String, default: '' },
})

defineEmits(['clear', 'retry', 'confirm'])

const draftFormulas = ref([''])
const visiblePreviewDataUrl = computed(() => (
  props.recognition?.previewDataUrl || props.previewDataUrl || ''
))

watch(
  [() => props.recognition?.id, () => props.recognition?.formulas, () => props.recognition?.latex],
  () => {
    const formulas = Array.isArray(props.recognition?.formulas)
      ? props.recognition.formulas.filter(value => typeof value === 'string' && value.trim())
      : []
    draftFormulas.value = formulas.length
      ? formulas.slice(0, 8)
      : [props.recognition?.latex || '']
  },
  { immediate: true, deep: true },
)

const confirmedFormulas = computed(() => draftFormulas.value
  .map(value => value.trim())
  .filter(Boolean))
const renderedFormulas = computed(() => confirmedFormulas.value.map((latex) => {
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
}).filter(Boolean))
const draftTotalLength = computed(() => confirmedFormulas.value
  .reduce((total, value) => total + value.length, 0))

const canConfirm = computed(() => Boolean(
  props.recognition?.id && confirmedFormulas.value.length
    && draftTotalLength.value <= 4000 && !props.loading && !props.confirming,
))
const statusLabel = computed(() => ({
  CONFIRMED: '已固定',
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
.formula-region-card__preview { display: block; width: auto; max-width: 100%; max-height: 50px; margin: 0 auto; box-sizing: border-box; object-fit: contain; border: 1px solid var(--ra-border); border-radius: 6px; background: #fff; }
.formula-region-card__preview-placeholder { display: grid; min-height: 58px; place-items: center; border: 1px dashed var(--ra-border); border-radius: 6px; color: var(--ra-text-tertiary); font-size: 10px; }
.formula-region-card__meta { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin: 7px 0; color: var(--ra-text-tertiary); font-size: 10px; }
.formula-region-card__meta-info { display: flex; min-width: 0; align-items: center; gap: 7px; }
.formula-region-card__rendered-list { display: grid; gap: 6px; margin: 8px 0; }
.formula-region-card__rendered { overflow-x: auto; padding: 8px; border-radius: 6px; background: var(--ra-hover-bg); color: var(--ra-text); text-align: center; }
.formula-region-card__rendered :deep(.katex-display) { margin: 0; }
.formula-region-card__empty { margin: 8px 0; padding: 8px; border-radius: 6px; color: var(--ra-text-tertiary); background: var(--ra-hover-bg); font-size: 10px; line-height: 1.45; }
.formula-region-card__message, .formula-region-card__error { margin: 7px 0 0; color: var(--ra-text-tertiary); font-size: 10px; line-height: 1.45; }
.formula-region-card__error { color: var(--el-color-danger); }
.formula-region-card__editor { margin-top: 8px; }
.formula-region-card__formula-editor + .formula-region-card__formula-editor { margin-top: 7px; }
.formula-region-card__formula-editor > small { display: block; margin-bottom: 3px; color: var(--ra-text-tertiary); font-size: 10px; }
.formula-region-card__actions { display: flex; justify-content: flex-end; gap: 7px; margin-top: 7px; }
.formula-region-card__actions--inline { flex: 0 0 auto; margin-top: 0; }
</style>
