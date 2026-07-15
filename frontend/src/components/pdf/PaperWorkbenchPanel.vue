<template>
  <aside class="paper-workbench" aria-label="论文助手">
    <header class="paper-workbench__header">
      <div>
        <strong>论文助手</strong>
        <span>所有结论均需通过证据门禁</span>
      </div>
      <el-tag v-if="trace" size="small" :type="runTagType(trace.status)" effect="plain">
        {{ runStatusLabel(trace.status) }}
      </el-tag>
    </header>

    <section v-if="selection" class="selection-card">
      <div class="section-heading">
        <span>当前选区</span>
        <button type="button" aria-label="清除选区" @click="$emit('clear-selection')">×</button>
      </div>
      <p>{{ selection.text }}</p>
      <div v-if="selectionLoading" class="muted-state">正在建立证据锚点…</div>
      <div v-else-if="selectionError" class="error-state">{{ selectionError }}</div>
      <template v-else-if="selectionAnchor">
        <div class="selection-meta">
          {{ anchorLabel(selectionAnchor.kind) }} · 第 {{ selectionAnchor.page }} 页 ·
          {{ Math.round(selectionAnchor.confidence * 100) }}%
        </div>
        <div class="compact-evidence-list">
          <button
            v-for="item in localEvidence"
            :key="item.evidenceId"
            type="button"
            :class="{ selected: item.selected }"
            @click="jump(item)"
          >
            <span>
              p.{{ item.page }} · {{ roleLabel(item.role) }} · {{ contentModeLabel(item.contentMode) }}
            </span>
            <b>{{ item.text }}</b>
          </button>
          <div v-if="!localEvidence.length" class="muted-state">该区域没有可安全引用的正文证据</div>
        </div>
      </template>
    </section>

    <section class="workflow-compose">
      <div class="workflow-tabs" role="tablist" aria-label="论文助手功能">
        <button
          v-for="item in modeOptions"
          :key="item.value"
          type="button"
          :class="{ active: mode === item.value }"
          :disabled="item.needsSelection && !selectionAnchor"
          @click="mode = item.value"
        >{{ item.label }}</button>
      </div>

      <el-select
        v-if="mode === WORKBENCH_MODES.PAPER_COMPARISON"
        v-model="comparisonPaperIds"
        class="paper-selector"
        multiple
        filterable
        collapse-tags
        collapse-tags-tooltip
        placeholder="至少再选择一篇论文"
        :loading="papersLoading"
      >
        <el-option
          v-for="item in availablePapers"
          :key="item.id"
          :label="item.title"
          :value="item.id"
        />
      </el-select>

      <el-input
        v-model="question"
        type="textarea"
        :rows="3"
        maxlength="4000"
        show-word-limit
        :placeholder="questionPlaceholder"
      />
      <div class="compose-actions">
        <span v-if="mode === WORKBENCH_MODES.PAPER_ANALYSIS">全文分析可能需要 1–2 分钟</span>
        <el-button type="primary" :loading="running" @click="startRun">
          {{ actionLabel }}
        </el-button>
      </div>
      <div v-if="error" class="error-state">{{ error }}</div>
    </section>

    <section v-if="trace" class="run-card">
      <div class="section-heading">
        <span>{{ workflowLabel(trace.plan?.workflow) }}</span>
        <el-select
          v-if="historyOptions.length > 1"
          :model-value="trace.runId"
          size="small"
          class="history-select"
          aria-label="最近运行"
          @change="selectHistory"
        >
          <el-option
            v-for="item in historyOptions"
            :key="item.runId"
            :label="`${workflowLabel(item.plan?.workflow)} · ${runStatusLabel(item.status)}`"
            :value="item.runId"
          />
        </el-select>
      </div>
      <div v-if="running" class="stage-line">
        <span class="stage-pulse" />{{ stageText || '正在执行固定工作流…' }}
      </div>
      <ol class="trace-steps">
        <li v-for="step in trace.steps" :key="step.index" :class="`is-${String(step.status).toLowerCase()}`">
          <span class="step-dot" />
          <div>
            <b>{{ step.name }}</b>
            <small>
              {{ stepStatusLabel(step.status) }}
              <template v-if="step.evidenceCount"> · {{ step.evidenceCount }} 条证据</template>
              <template v-if="step.totalTokens"> · {{ step.totalTokens }} tokens</template>
              <template v-if="step.retryCount"> · 重试 {{ step.retryCount }}</template>
            </small>
          </div>
        </li>
      </ol>
      <div v-if="trace.metrics" class="run-metrics">
        <span>证据 {{ trace.metrics.evidenceCount }}</span>
        <span>repair {{ trace.metrics.repairCount }}</span>
        <span>{{ trace.metrics.totalTokens }} tokens</span>
        <span>{{ formatDuration(trace.metrics.latencyMs) }}</span>
      </div>
      <div v-if="trace.errorMessage" class="error-state">{{ trace.errorMessage }}</div>
    </section>

    <section v-if="trace?.result" class="result-card">
      <div class="section-heading"><span>分析结果</span></div>
      <div class="answer-text" v-html="answerHtml" />

      <div v-if="trace.result.annotationSuggestion" class="annotation-suggestion">
        <div>
          <el-tag size="small" effect="plain">{{ annotationTypeLabel(trace.result.annotationSuggestion.type) }}</el-tag>
          <span>确认后才会写入 PDF 批注</span>
        </div>
        <p>{{ trace.result.annotationSuggestion.content }}</p>
        <div class="evidence-links">
          <button
            v-for="item in annotationEvidence"
            :key="item.evidenceId"
            type="button"
            @click="jump(item)"
          >p.{{ item.page }}</button>
        </div>
        <el-button
          type="primary"
          size="small"
          :loading="applyingAnnotation"
          :disabled="annotationIsApplied"
          @click="confirmAnnotation"
        >{{ annotationIsApplied ? '已添加批注' : '确认添加批注' }}</el-button>
      </div>

      <ol v-if="trace.result.claims?.length" class="claim-list">
        <li v-for="(claim, index) in trace.result.claims" :key="index">
          <span>{{ claim.text }}</span>
          <div class="evidence-links">
            <button
              v-for="item in evidenceForClaim(claim)"
              :key="item.evidenceId"
              type="button"
              :title="item.text"
              @click="jump(item)"
            >论文 {{ item.paperId }} · p.{{ item.page }}</button>
          </div>
        </li>
      </ol>
      <div v-if="trace.result.regionFallback" class="region-warning">
        该结果包含低置信度区域证据，请结合原页核对。
      </div>
    </section>
  </aside>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listPapers } from '@/api/paper.js'
import { usePaperWorkbench } from '@/composables/usePaperWorkbench.js'
import {
  buildWorkbenchPlanRequest,
  evidenceIndex,
  stepStatusLabel,
  workbenchMarkdownToHtml,
  WORKBENCH_MODES,
} from '@/utils/workbenchRun.js'

const props = defineProps({
  paper: { type: Object, required: true },
  selection: { type: Object, default: null },
  selectionAnchor: { type: Object, default: null },
  localEvidence: { type: Array, default: () => [] },
  selectionLoading: { type: Boolean, default: false },
  selectionError: { type: String, default: '' },
  applyAnnotation: { type: Function, default: null },
  appliedAnnotationRunIds: { type: Array, default: () => [] },
})

const emit = defineEmits(['clear-selection', 'jump-evidence'])
const {
  trace,
  recentRuns,
  running,
  stageText,
  error,
  run,
  loadRecent,
  selectRun,
} = usePaperWorkbench()

const mode = ref(WORKBENCH_MODES.PAPER_ANALYSIS)
const questions = reactive({
  [WORKBENCH_MODES.SELECTION_QA]: '',
  [WORKBENCH_MODES.PAPER_ANALYSIS]: '请从研究问题、核心方法、实验结果、主要结论与局限五个方面分析这篇论文。',
  [WORKBENCH_MODES.ANNOTATION_SUGGESTION]: '请为选中内容生成一条有价值的学术批注。',
  [WORKBENCH_MODES.PAPER_COMPARISON]: '比较这些论文的研究问题、方法、关键结论与局限，并指出异同。',
})
const comparisonPaperIds = ref([])
const availablePapers = ref([])
const papersLoading = ref(false)
const applyingAnnotation = ref(false)
const annotationAppliedRunId = ref('')

const modeOptions = [
  { value: WORKBENCH_MODES.SELECTION_QA, label: '选区问答', needsSelection: true },
  { value: WORKBENCH_MODES.PAPER_ANALYSIS, label: '全文分析' },
  { value: WORKBENCH_MODES.ANNOTATION_SUGGESTION, label: '批注建议', needsSelection: true },
  { value: WORKBENCH_MODES.PAPER_COMPARISON, label: '多篇对比' },
]
const question = computed({
  get: () => questions[mode.value],
  set: value => { questions[mode.value] = value },
})
const questionPlaceholder = computed(() => ({
  [WORKBENCH_MODES.SELECTION_QA]: '针对当前选区提问，例如：这一步推导为什么成立？',
  [WORKBENCH_MODES.PAPER_ANALYSIS]: '可补充你关注的研究问题；留空也可使用默认分析要求',
  [WORKBENCH_MODES.ANNOTATION_SUGGESTION]: '说明希望得到总结、质疑、问题或批判性批注',
  [WORKBENCH_MODES.PAPER_COMPARISON]: '说明比较维度，例如方法、指标、场景或结论',
}[mode.value]))
const actionLabel = computed(() => ({
  [WORKBENCH_MODES.SELECTION_QA]: '基于选区回答',
  [WORKBENCH_MODES.PAPER_ANALYSIS]: '分析全文',
  [WORKBENCH_MODES.ANNOTATION_SUGGESTION]: '生成批注建议',
  [WORKBENCH_MODES.PAPER_COMPARISON]: '开始对比',
}[mode.value]))
const resultEvidenceIndex = computed(() => evidenceIndex(trace.value))
const annotationEvidence = computed(() => (trace.value?.result?.annotationSuggestion?.evidenceIds || [])
  .map(id => resultEvidenceIndex.value.get(id)).filter(Boolean))
const annotationIsApplied = computed(() => Boolean(trace.value?.runId)
  && (annotationAppliedRunId.value === trace.value.runId
    || props.appliedAnnotationRunIds.includes(trace.value.runId)))
const historyOptions = computed(() => {
  if (!trace.value) return recentRuns.value
  return [trace.value, ...recentRuns.value.filter(item => item.runId !== trace.value.runId)]
})
const answerHtml = computed(() => workbenchMarkdownToHtml(trace.value?.result?.answer))

watch(() => props.selectionAnchor, next => {
  if (next && !running.value) mode.value = WORKBENCH_MODES.SELECTION_QA
})
watch(() => trace.value?.runId, () => { annotationAppliedRunId.value = '' })

onMounted(async () => {
  papersLoading.value = true
  try {
    const papers = await listPapers()
    availablePapers.value = papers.filter(item => Number(item.id) !== Number(props.paper.id))
  } catch {
    availablePapers.value = []
  } finally {
    papersLoading.value = false
  }
  try { await loadRecent(props.paper.id) } catch { /* history is optional */ }
})

async function startRun() {
  try {
    const request = buildWorkbenchPlanRequest({
      mode: mode.value,
      paperId: props.paper.id,
      comparisonPaperIds: comparisonPaperIds.value,
      question: question.value,
      selectionAnchor: props.selectionAnchor,
    })
    await run(request)
    await loadRecent(props.paper.id)
    ElMessage.success('论文助手已完成')
  } catch (reason) {
    if (reason?.message !== 'aborted') {
      ElMessage.error(reason?.response?.data?.message || reason?.message || '论文助手执行失败')
    }
  }
}

function selectHistory(runId) {
  selectRun(historyOptions.value.find(item => item.runId === runId))
}

function evidenceForClaim(claim) {
  return (claim?.evidenceIds || []).map(id => resultEvidenceIndex.value.get(id)).filter(Boolean)
}

function jump(item) {
  if (item) emit('jump-evidence', item)
}

async function confirmAnnotation() {
  if (!props.applyAnnotation || !trace.value?.result?.annotationSuggestion) return
  applyingAnnotation.value = true
  try {
    await props.applyAnnotation({
      trace: trace.value,
      suggestion: trace.value.result.annotationSuggestion,
      evidence: annotationEvidence.value,
    })
    annotationAppliedRunId.value = trace.value.runId
  } catch (reason) {
    ElMessage.error(reason?.message || '批注添加失败')
  } finally {
    applyingAnnotation.value = false
  }
}

function workflowLabel(workflow) {
  return {
    SELECTION_QA: '选区问答',
    PAPER_ANALYSIS: '全文分析',
    ANNOTATION_SUGGESTION: '批注建议',
    PAPER_COMPARISON: '多篇对比',
  }[workflow] || '论文助手运行'
}

function runStatusLabel(status) {
  return {
    PLANNED: '已规划', QUEUED: '排队中', RUNNING: '执行中',
    COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消',
  }[status] || status
}

function runTagType(status) {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED' || status === 'CANCELLED') return 'danger'
  return 'info'
}

function anchorLabel(kind) {
  return { TEXT: '正文已映射', FORMULA: '公式已映射', TABLE: '表格已映射', REGION: '区域理解' }[kind] || '已建立锚点'
}

function roleLabel(role) {
  return { ABSTRACT: '摘要', HEADING: '标题', BODY: '正文', CAPTION: '图表说明', FORMULA: '公式', TABLE: '表格' }[role] || role
}

function contentModeLabel(mode) {
  return { TEXT: '精确文本', STRUCTURED: '结构化', REGION: '仅区域' }[mode] || '精确文本'
}

function annotationTypeLabel(type) {
  return { COMMENT: '评论', SUMMARY: '总结', QUESTION: '问题', CRITIQUE: '批判性批注' }[type] || '批注'
}

function formatDuration(milliseconds) {
  const value = Math.max(0, Number(milliseconds) || 0)
  return value >= 1000 ? `${(value / 1000).toFixed(value >= 10000 ? 0 : 1)}s` : `${value}ms`
}
</script>

<style scoped>
.paper-workbench {
  flex: 0 0 360px;
  min-width: 0;
  overflow-y: auto;
  box-sizing: border-box;
  border-left: 1px solid var(--ra-border);
  background: var(--ra-panel-bg);
  color: var(--ra-text);
}
.paper-workbench__header {
  position: sticky;
  top: 0;
  z-index: 3;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  padding: 14px;
  border-bottom: 1px solid var(--ra-border);
  background: var(--ra-panel-bg);
}
.paper-workbench__header > div { display: flex; flex-direction: column; gap: 3px; }
.paper-workbench__header strong { font-size: 15px; }
.paper-workbench__header span { color: var(--ra-text-tertiary); font-size: 11px; }
section { padding: 13px 14px; border-bottom: 1px solid var(--ra-border); }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 9px; font-size: 13px; font-weight: 600; }
.section-heading button { border: 0; color: var(--ra-text-secondary); background: transparent; cursor: pointer; font-size: 18px; }
.selection-card p { max-height: 76px; overflow: auto; margin: 0 0 8px; padding: 8px; border-left: 3px solid var(--ra-link); background: var(--ra-hover-bg); font-size: 12px; line-height: 1.45; white-space: pre-wrap; }
.selection-meta { margin-bottom: 7px; color: var(--ra-text-tertiary); font-size: 11px; }
.compact-evidence-list { display: flex; flex-direction: column; gap: 5px; }
.compact-evidence-list button { display: flex; flex-direction: column; gap: 3px; padding: 7px 8px; border: 1px solid var(--ra-border); border-radius: 6px; color: var(--ra-text); background: transparent; text-align: left; cursor: pointer; }
.compact-evidence-list button:hover, .compact-evidence-list button.selected { border-color: var(--ra-link); background: var(--ra-hover-bg); }
.compact-evidence-list span { color: var(--ra-text-tertiary); font-size: 10px; }
.compact-evidence-list b { display: -webkit-box; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 2; font-size: 11px; font-weight: 400; line-height: 1.4; }
.workflow-tabs { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 5px; margin-bottom: 9px; }
.workflow-tabs button { min-width: 0; padding: 7px 4px; border: 1px solid var(--ra-border); border-radius: 6px; color: var(--ra-text-secondary); background: transparent; cursor: pointer; }
.workflow-tabs button.active { border-color: var(--ra-link); color: var(--ra-link); background: var(--ra-hover-bg); }
.workflow-tabs button:disabled { opacity: .45; cursor: not-allowed; }
.paper-selector { width: 100%; margin-bottom: 9px; }
.compose-actions { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 9px; }
.compose-actions span { color: var(--ra-text-tertiary); font-size: 10px; }
.history-select { width: 150px; }
.stage-line { display: flex; align-items: center; gap: 7px; margin-bottom: 9px; color: var(--ra-link); font-size: 12px; }
.stage-pulse { width: 7px; height: 7px; border-radius: 50%; background: var(--ra-link); box-shadow: 0 0 0 4px color-mix(in srgb, var(--ra-link) 18%, transparent); }
.trace-steps { display: flex; flex-direction: column; gap: 7px; margin: 0; padding: 0; list-style: none; }
.trace-steps li { display: flex; gap: 8px; color: var(--ra-text-tertiary); }
.trace-steps li > div { display: flex; flex-direction: column; min-width: 0; }
.trace-steps b { color: var(--ra-text-secondary); font-size: 12px; font-weight: 500; }
.trace-steps small { font-size: 10px; }
.step-dot { flex: 0 0 auto; width: 8px; height: 8px; margin-top: 4px; border: 2px solid var(--ra-border); border-radius: 50%; }
.trace-steps .is-running .step-dot { border-color: var(--ra-link); background: var(--ra-link); }
.trace-steps .is-completed .step-dot { border-color: #4caf50; background: #4caf50; }
.trace-steps .is-failed .step-dot { border-color: var(--el-color-danger); background: var(--el-color-danger); }
.run-metrics { display: flex; flex-wrap: wrap; gap: 5px 10px; margin-top: 10px; color: var(--ra-text-tertiary); font-size: 10px; }
.answer-text { font-size: 12px; line-height: 1.65; overflow-wrap: anywhere; }
.answer-text :deep(h3), .answer-text :deep(h4), .answer-text :deep(h5) { margin: 12px 0 5px; font-size: 13px; line-height: 1.4; }
.answer-text :deep(h3:first-child), .answer-text :deep(h4:first-child) { margin-top: 0; }
.answer-text :deep(p) { margin: 5px 0; }
.answer-text :deep(ul), .answer-text :deep(ol) { margin: 5px 0; padding-left: 19px; }
.answer-text :deep(li) { margin: 3px 0; }
.answer-text :deep(code) { padding: 1px 3px; border-radius: 3px; background: var(--ra-hover-bg); }
.claim-list { display: flex; flex-direction: column; gap: 10px; margin: 12px 0 0; padding-left: 19px; }
.claim-list li { padding-left: 2px; font-size: 11px; line-height: 1.5; }
.evidence-links { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 5px; }
.evidence-links button { padding: 2px 6px; border: 1px solid color-mix(in srgb, var(--ra-link) 45%, var(--ra-border)); border-radius: 999px; color: var(--ra-link); background: transparent; font-size: 10px; cursor: pointer; }
.annotation-suggestion { margin-top: 12px; padding: 10px; border: 1px solid color-mix(in srgb, var(--ra-link) 40%, var(--ra-border)); border-radius: 7px; background: var(--ra-hover-bg); }
.annotation-suggestion > div:first-child { display: flex; align-items: center; gap: 7px; color: var(--ra-text-tertiary); font-size: 10px; }
.annotation-suggestion p { margin: 8px 0; font-size: 12px; line-height: 1.5; white-space: pre-wrap; }
.annotation-suggestion > .el-button { margin-top: 9px; }
.muted-state, .error-state, .region-warning { padding: 7px 0; color: var(--ra-text-tertiary); font-size: 11px; line-height: 1.45; }
.error-state { color: var(--el-color-danger); }
.region-warning { color: #a66000; }
@media (max-width: 1180px) { .paper-workbench { flex-basis: 320px; } }
</style>
