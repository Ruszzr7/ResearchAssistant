<template>
  <aside class="paper-workbench" aria-label="论文研究工作台">
    <header class="paper-workbench__header">
      <div>
        <strong>论文研究工作台</strong>
      </div>
      <el-tag v-if="trace" size="small" :type="runTagType(trace.status)" effect="plain">
        {{ runStatusLabel(trace.status) }}
      </el-tag>
    </header>

    <FormulaRegionCard
      v-if="formulaRegion"
      :region="formulaRegion"
      :recognition="formulaRecognition"
      :loading="formulaLoading"
      :confirming="formulaConfirming"
      :error="formulaError"
      @clear="$emit('clear-formula')"
      @retry="$emit('retry-formula')"
      @confirm="$emit('confirm-formula', $event)"
    />

    <section v-else-if="selection" class="selection-card">
      <div class="section-heading">
        <span>当前选区</span>
        <button type="button" class="selection-clear-action" aria-label="清除选区" @click="$emit('clear-selection')">×</button>
      </div>
      <p>{{ selection.text }}</p>
      <div class="selection-tools">
        <el-button
          type="primary"
          plain
          size="small"
          :loading="selectionTranslationLoading"
          @click="translateSelection"
        >翻译选区为{{ languageLabel(selectionTargetLanguage) }}</el-button>
      </div>
      <div v-if="selectionTranslation" class="selection-translation">
        <small>选区译文 · {{ languageLabel(selectionTranslation.targetLanguage) }}</small>
        <div>{{ selectionTranslation.text }}</div>
      </div>
      <div v-if="selectionTranslationError" class="error-state">{{ selectionTranslationError }}</div>
      <div v-if="selectionLoading" class="muted-state">正在建立证据锚点…</div>
      <div v-else-if="selectionError" class="error-state">{{ selectionError }}</div>
      <template v-else-if="selectionAnchor">
        <div class="selection-meta">
          {{ anchorLabel(selectionAnchor.kind) }} · 第 {{ selectionAnchor.page }} 页 ·
          {{ Math.round(selectionAnchor.confidence * 100) }}%
        </div>
      </template>
    </section>

    <section class="workflow-compose">
      <div class="workflow-tabs" role="tablist" aria-label="论文助手功能">
        <button
          v-for="item in modeOptions"
          :key="item.value"
          type="button"
          :class="{ active: isModeTabActive(item.value) }"
          :disabled="running || (item.needsSelection && !activeSelectionAnchor)"
          @click="selectProductMode(item.value)"
        >{{ item.label }}</button>
      </div>

      <div v-if="mode === WORKBENCH_MODES.SELECTION_QA" class="selection-chat">
        <div class="selection-chat__heading">
          <div>
            <b>选区对话</b>
            <small>以当前选区为焦点，并检索整篇论文的相关证据</small>
          </div>
          <button type="button" :disabled="running" @click="resetSelectionConversation">新对话</button>
        </div>

        <div ref="selectionChatMessages" class="selection-chat__messages" aria-live="polite">
          <div v-if="!selectionMessages.length" class="selection-chat__empty">
            针对选中文字提问；后续可以继续追问。
          </div>
          <article
            v-for="message in selectionMessages"
            :key="message.id"
            class="chat-message"
            :class="`is-${message.role}`"
          >
            <div class="chat-message__role">{{ message.role === 'user' ? '你' : '论文助手' }}</div>
            <div v-if="message.role === 'assistant'" class="answer-text" v-html="messageHtml(message)" />
            <div v-else class="chat-message__text">{{ message.content }}</div>
            <ol v-if="message.claims?.length" class="chat-claim-list">
              <li v-for="(claim, claimIndex) in message.claims" :key="claimIndex">
                <span>{{ claim.text }}</span>
                <div class="evidence-links">
                  <button
                    v-for="item in messageEvidenceForClaim(message, claim)"
                    :key="item.evidenceId"
                    type="button"
                    :title="item.text"
                    @click="jump(item)"
                  >p.{{ item.page }}</button>
                </div>
              </li>
            </ol>
          </article>
          <div v-if="running" class="chat-message is-assistant is-pending">
            <div class="chat-message__role">论文助手</div>
            <div class="trace-dots" role="list" aria-label="回答进度">
              <span
                v-for="phase in tracePhases"
                :key="phase.key"
                class="trace-dot-item"
                :class="`is-${String(phase.status).toLowerCase()}`"
                role="listitem"
                :title="phase.tooltip"
              ><span class="step-dot" aria-hidden="true" /></span>
            </div>
          </div>
        </div>

        <el-input
          v-model="question"
          type="textarea"
          :rows="3"
          maxlength="4000"
          show-word-limit
          placeholder="继续询问选区内容、公式含义或与全文的关系"
          @keydown.ctrl.enter.prevent="sendSelectionMessage"
        />
        <div class="compose-actions selection-chat__actions">
          <span>Ctrl + Enter 发送</span>
          <el-button
            type="primary"
            :loading="running"
            :disabled="selectionChatDisabled"
            @click="sendSelectionMessage"
          >发送</el-button>
        </div>
        <div v-if="selectionChatError || error" class="error-state">{{ selectionChatError || error }}</div>
      </div>

      <template v-else>
      <div v-if="isFieldGapMode" class="comparison-stage-banner">
        <div>
          <b>领域研究空白</b>
          <small>基于已完成的跨论文对比继续分析，不改变论文集合</small>
        </div>
        <button type="button" :disabled="running" @click="returnToComparison">返回对比结果</button>
      </div>

      <el-select
        v-if="isMultiPaperMode"
        v-model="comparisonPaperIds"
        class="paper-selector"
        multiple
        filterable
        collapse-tags
        collapse-tags-tooltip
        :placeholder="paperSelectorPlaceholder"
        :loading="papersLoading"
        :disabled="isFieldGapMode"
      >
        <el-option
          v-for="item in availablePapers"
          :key="item.id"
          :label="item.title || `论文 #${item.id}`"
          :value="Number(item.id)"
          :disabled="comparisonOptionDisabled(item)"
        />
      </el-select>

      <div v-if="isMultiPaperMode" class="comparison-config">
        <div class="comparison-base">
          <div>
            <small>基准论文</small>
            <b :title="paper.title">{{ paper.title || `论文 #${paper.id}` }}</b>
          </div>
          <el-tag size="small" effect="plain">
            {{ comparisonState.total }}/{{ comparisonState.max }} 篇
          </el-tag>
        </div>
        <div class="comparison-hint" :class="{ ready: comparisonState.canStart }">
          {{ comparisonSelectionHint }}
        </div>
        <div class="dimension-picker" :aria-label="isFieldGapMode ? '领域研究空白分析维度' : '跨论文比较维度'">
          <button
            v-for="dimension in comparisonDimensionOptions"
            :key="dimension"
            type="button"
            :class="{ active: comparisonDimensions.includes(dimension) }"
            :aria-pressed="comparisonDimensions.includes(dimension)"
            @click="toggleComparisonDimension(dimension)"
          >{{ dimension }}</button>
        </div>
      </div>

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
        <span v-else-if="mode === WORKBENCH_MODES.PAPER_IMPROVEMENT">仅分析当前论文</span>
        <el-button type="primary" :loading="running" :disabled="actionDisabled" @click="startRun">
          {{ actionLabel }}
        </el-button>
      </div>
      <div v-if="error" class="error-state">{{ error }}</div>
      </template>
    </section>

    <section v-if="mode !== WORKBENCH_MODES.SELECTION_QA && trace" class="run-card">
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
      <div class="trace-dots" role="list" aria-label="执行进度">
        <span
          v-for="phase in tracePhases"
          :key="phase.key"
          class="trace-dot-item"
          :class="`is-${String(phase.status).toLowerCase()}`"
          role="listitem"
          tabindex="0"
          :title="phase.tooltip"
          :aria-label="`${phase.description}：${phase.statusLabel}`"
        >
          <span class="step-dot" aria-hidden="true" />
        </span>
      </div>
      <div v-if="trace.errorMessage" class="error-state">{{ trace.errorMessage }}</div>
    </section>

    <section v-if="mode !== WORKBENCH_MODES.SELECTION_QA && trace?.result" class="result-card">
      <div class="section-heading">
        <span>分析结果</span>
        <div class="result-language-switch" role="group" aria-label="结果语言">
          <button
            v-for="language in resultLanguageOptions"
            :key="language.value"
            type="button"
            :class="{ active: resultLanguage === language.value }"
            :disabled="resultTranslationLoading"
            @click="selectResultLanguage(language.value)"
          >{{ language.label }}</button>
        </div>
      </div>
      <div v-if="resultTranslationLoading" class="muted-state translation-state">正在转换结果语言…</div>
      <div v-if="resultTranslationError" class="error-state translation-state">{{ resultTranslationError }}</div>
      <div class="result-selectable-content">
        <div class="answer-text" v-html="answerHtml" />

        <div v-if="isMultiPaperResult && comparisonCoverage.total" class="comparison-coverage">
          <div class="coverage-heading">
            <b>逐论文证据覆盖</b>
            <el-tag
              size="small"
              :type="comparisonCoverage.covered === comparisonCoverage.total ? 'success' : 'warning'"
              effect="plain"
            >{{ comparisonCoverage.covered }}/{{ comparisonCoverage.total }}</el-tag>
          </div>
          <button
            v-for="row in comparisonCoverage.rows"
            :key="row.paperId"
            type="button"
            class="coverage-row"
            :class="{ covered: row.covered }"
            :disabled="!row.firstEvidence"
            @click="jump(row.firstEvidence)"
          >
            <span class="coverage-state" aria-hidden="true">{{ row.covered ? '✓' : '!' }}</span>
            <span class="coverage-paper">
              <b :title="row.title">{{ row.title }}</b>
              <small>
                {{ row.evidenceCount }} 条证据 · {{ row.citedClaims }} 条结论引用
                <template v-if="row.pages.length"> · p.{{ row.pages.join(', ') }}</template>
              </small>
            </span>
          </button>
        </div>

        <ol v-if="displayedClaims.length" class="claim-list">
          <li v-for="(claim, index) in displayedClaims" :key="index">
            <span>{{ claim.text }}</span>
            <div class="evidence-links">
              <button
                v-for="item in evidenceForClaim(claim)"
                :key="item.evidenceId"
                type="button"
                :title="item.text"
                @click="jump(item)"
              >{{ paperDisplayName(item.paperId) }} · p.{{ item.page }}</button>
            </div>
          </li>
        </ol>
        <div v-if="trace.result.regionFallback" class="region-warning">
          该结果包含低置信度区域证据，请结合原页核对。
        </div>
      </div>

      <div v-if="completedComparisonResult" class="comparison-followup">
        <div>
          <b>继续分析领域研究空白</b>
          <small v-if="comparisonResultPaperIds.length >= 3">
            沿用本次 {{ comparisonResultPaperIds.length }} 篇论文及证据，寻找跨论文的候选空白。
          </small>
          <small v-else>领域研究空白至少需要 3 篇论文，请增加论文并重新对比。</small>
        </div>
        <el-button
          type="primary"
          plain
          :loading="running"
          :disabled="comparisonResultPaperIds.length < 3"
          @click="startFieldGapFromComparison"
        >分析领域研究空白</el-button>
      </div>
    </section>

  </aside>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listPapers } from '@/api/paper.js'
import { translateTexts } from '@/api/workbench.js'
import FormulaRegionCard from '@/components/pdf/FormulaRegionCard.vue'
import { usePaperWorkbench } from '@/composables/usePaperWorkbench.js'
import {
  detectTextLanguage,
  languageLabel,
  oppositeLanguage,
  TRANSLATION_LANGUAGES,
} from '@/utils/translation.js'
import {
  buildComparisonCoverage,
  buildComparisonQuestion,
  buildWorkbenchPlanRequest,
  compactTracePhases,
  comparisonSelectionState,
  evidenceIndex,
  workbenchMarkdownToHtml,
  WORKBENCH_MODES,
} from '@/utils/workbenchRun.js'

const props = defineProps({
  paper: { type: Object, required: true },
  selection: { type: Object, default: null },
  selectionAnchor: { type: Object, default: null },
  selectionLoading: { type: Boolean, default: false },
  selectionError: { type: String, default: '' },
  formulaRegion: { type: Object, default: null },
  formulaRecognition: { type: Object, default: null },
  formulaLoading: { type: Boolean, default: false },
  formulaConfirming: { type: Boolean, default: false },
  formulaError: { type: String, default: '' },
  initialMode: { type: String, default: '' },
  initialPaperIds: { type: Array, default: () => [] },
})

const emit = defineEmits([
  'clear-selection', 'clear-formula', 'retry-formula', 'confirm-formula',
  'jump-evidence', 'mode-change', 'paper-ids-change',
])
const {
  trace,
  recentRuns,
  running,
  error,
  run,
  loadRecent,
  selectRun,
} = usePaperWorkbench()

const productWorkbenchModes = [
  WORKBENCH_MODES.SELECTION_QA,
  WORKBENCH_MODES.PAPER_ANALYSIS,
  WORKBENCH_MODES.PAPER_COMPARISON,
  WORKBENCH_MODES.PAPER_IMPROVEMENT,
]
const supportedWorkbenchModes = [...productWorkbenchModes, WORKBENCH_MODES.RESEARCH_GAP]
const mode = ref(normalizeInitialMode(props.initialMode))
const questions = reactive({
  [WORKBENCH_MODES.SELECTION_QA]: '',
  [WORKBENCH_MODES.PAPER_ANALYSIS]: '请从研究问题、核心方法、实验结果、主要结论与局限五个方面分析这篇论文。',
  [WORKBENCH_MODES.PAPER_IMPROVEMENT]: '请识别这篇论文的改进空间，并从假设、方法、数据或场景、评价指标、实验设计与可复现性中提出可检验的后续研究切入点；区分论文自述局限与基于证据的推断。',
  [WORKBENCH_MODES.PAPER_COMPARISON]: '比较这些论文的研究问题、方法、关键结论与局限，并指出异同。',
  [WORKBENCH_MODES.RESEARCH_GAP]: '结合所选论文识别可检验的候选研究空白，并说明证据边界与下一步验证方案。',
})
const fieldGapSourceRunId = ref('')
const comparisonPaperIds = ref(normalizeInitialPaperIds(props.initialPaperIds))
const comparisonDimensions = ref(['研究问题', '核心方法', '实验与指标', '主要结论', '局限'])
const availablePapers = ref([])
const papersLoading = ref(false)
const selectionTranslation = ref(null)
const selectionTranslationLoading = ref(false)
const selectionTranslationError = ref('')
const selectionMessages = ref([])
const selectionConversationId = ref('')
const selectionChatError = ref('')
const selectionChatMessages = ref(null)
let selectionMessageSequence = 0
const resultLanguage = ref(detectTextLanguage(trace.value?.result?.answer))
const resultTranslationLoading = ref(false)
const resultTranslationError = ref('')
const resultTranslationCache = reactive(new Map())
const resultLanguageOptions = [
  { value: TRANSLATION_LANGUAGES.CHINESE, label: '中文' },
  { value: TRANSLATION_LANGUAGES.ENGLISH, label: 'English' },
]

const modeOptions = [
  { value: WORKBENCH_MODES.SELECTION_QA, label: '选区问答', needsSelection: true },
  { value: WORKBENCH_MODES.PAPER_ANALYSIS, label: '全文分析' },
  { value: WORKBENCH_MODES.PAPER_COMPARISON, label: '跨论文对比' },
  { value: WORKBENCH_MODES.PAPER_IMPROVEMENT, label: '论文改进空间' },
]
const comparisonDimensionOptions = ['研究问题', '核心方法', '实验与指标', '主要结论', '局限', '适用场景']
const question = computed({
  get: () => questions[mode.value],
  set: value => { questions[mode.value] = value },
})
const questionPlaceholder = computed(() => ({
  [WORKBENCH_MODES.SELECTION_QA]: '针对当前选区提问，例如：这一步推导为什么成立？',
  [WORKBENCH_MODES.PAPER_ANALYSIS]: '可补充你关注的研究问题；留空也可使用默认分析要求',
  [WORKBENCH_MODES.PAPER_IMPROVEMENT]: '可补充希望重点检查的方法、假设、指标或实验环节',
  [WORKBENCH_MODES.PAPER_COMPARISON]: '说明比较维度，例如方法、指标、场景或结论',
  [WORKBENCH_MODES.RESEARCH_GAP]: '说明希望进一步检验的领域方向，例如假设边界、指标缺口或场景覆盖',
}[mode.value]))
const actionLabel = computed(() => ({
  [WORKBENCH_MODES.SELECTION_QA]: '基于选区回答',
  [WORKBENCH_MODES.PAPER_ANALYSIS]: '分析全文',
  [WORKBENCH_MODES.PAPER_IMPROVEMENT]: '分析改进空间',
  [WORKBENCH_MODES.PAPER_COMPARISON]: '开始跨论文对比',
  [WORKBENCH_MODES.RESEARCH_GAP]: '分析领域研究空白',
}[mode.value]))
const resultEvidenceIndex = computed(() => evidenceIndex(trace.value))
const historyOptions = computed(() => {
  const matchingRuns = recentRuns.value.filter(item => item.plan?.workflow === mode.value)
  if (!trace.value || trace.value.plan?.workflow !== mode.value) return matchingRuns
  return [trace.value, ...matchingRuns.filter(item => item.runId !== trace.value.runId)]
})
const originalAnswer = computed(() => trace.value?.result?.answer || '')
const originalResultLanguage = computed(() => detectTextLanguage(originalAnswer.value))
const activeResultTranslation = computed(() => resultTranslationCache.get(resultTranslationKey(resultLanguage.value)))
const displayedAnswer = computed(() => {
  if (resultLanguage.value === originalResultLanguage.value) return originalAnswer.value
  return activeResultTranslation.value?.items?.[0]?.text || originalAnswer.value
})
const displayedClaims = computed(() => {
  const claims = trace.value?.result?.claims || []
  if (resultLanguage.value === originalResultLanguage.value) return claims
  const translated = activeResultTranslation.value?.items || []
  return claims.map((claim, index) => ({
    ...claim,
    text: translated[index + 1]?.text || claim.text,
  }))
})
const answerHtml = computed(() => workbenchMarkdownToHtml(displayedAnswer.value))
const activeSelectionAnchor = computed(() => {
  if (props.formulaRegion) {
    return props.formulaRecognition?.confirmed && props.formulaRecognition?.anchor
      ? props.formulaRecognition.anchor : null
  }
  return props.selectionAnchor
})
const activeSelectionText = computed(() => (
  props.formulaRegion
    ? props.formulaRecognition?.latex || ''
    : props.selection?.text || ''
))
const activeSelectionIdentity = computed(() => {
  if (props.formulaRegion) {
    const box = props.formulaRegion.bbox || {}
    return `formula:${props.formulaRegion.page}:${box.x}:${box.y}:${box.width}:${box.height}`
  }
  return props.selection?.text ? `text:${props.selection.text}` : ''
})
const selectionTargetLanguage = computed(() => oppositeLanguage(
  detectTextLanguage(props.selection?.text)))
const tracePhases = computed(() => compactTracePhases(trace.value))
const paperCatalog = computed(() => [props.paper, ...availablePapers.value])
const eligibleComparisonPapers = computed(() => availablePapers.value.filter(item => item.pdfPath))
const isMultiPaperMode = computed(() => mode.value === WORKBENCH_MODES.PAPER_COMPARISON
  || mode.value === WORKBENCH_MODES.RESEARCH_GAP)
const isFieldGapMode = computed(() => mode.value === WORKBENCH_MODES.RESEARCH_GAP)
const minimumPaperCount = computed(() => mode.value === WORKBENCH_MODES.RESEARCH_GAP ? 3 : 2)
const comparisonState = computed(() => comparisonSelectionState(
  props.paper.id, comparisonPaperIds.value, minimumPaperCount.value))
const paperSelectorPlaceholder = computed(() => mode.value === WORKBENCH_MODES.RESEARCH_GAP
  ? '至少再选择两篇论文' : '至少再选择一篇论文')
const comparisonSelectionHint = computed(() => {
  const requiredAdditional = minimumPaperCount.value - 1
  if (eligibleComparisonPapers.value.length < requiredAdditional) {
    return mode.value === WORKBENCH_MODES.RESEARCH_GAP
      ? '文库中至少需要三篇带 PDF 的论文才能分析领域研究空白'
      : '文库中至少需要两篇带 PDF 的论文才能跨论文对比'
  }
  if (!comparisonState.value.canStart) return `请至少再选择 ${requiredAdditional} 篇论文`
  if (comparisonState.value.atLimit) return '已达到单次对比上限'
  return `已选择 ${comparisonState.value.total} 篇论文，可以开始${mode.value === WORKBENCH_MODES.RESEARCH_GAP ? '分析领域研究空白' : '跨论文对比'}`
})
const actionDisabled = computed(() => running.value
  || (isMultiPaperMode.value && !comparisonState.value.canStart)
  || (isFieldGapMode.value && !fieldGapSourceRunId.value))
const selectionChatDisabled = computed(() => running.value
  || !activeSelectionAnchor.value
  || !String(question.value || '').trim())
const isMultiPaperResult = computed(() => [
  WORKBENCH_MODES.PAPER_COMPARISON,
  WORKBENCH_MODES.RESEARCH_GAP,
].includes(trace.value?.plan?.workflow))
const comparisonCoverage = computed(() => buildComparisonCoverage(trace.value, paperCatalog.value))
const completedComparisonResult = computed(() => trace.value?.status === 'COMPLETED'
  && trace.value?.plan?.workflow === WORKBENCH_MODES.PAPER_COMPARISON
  && Boolean(trace.value?.result))
const comparisonResultPaperIds = computed(() => {
  if (!completedComparisonResult.value) return []
  const ids = trace.value?.result?.paperIds?.length
    ? trace.value.result.paperIds : trace.value?.invocation?.paperIds || []
  return [...new Set(ids.map(Number))].filter(id => Number.isInteger(id) && id > 0)
})

watch(activeSelectionAnchor, next => {
  if (next && !running.value) mode.value = WORKBENCH_MODES.SELECTION_QA
})
watch([activeSelectionIdentity, activeSelectionText], () => {
  selectionTranslation.value = null
  selectionTranslationError.value = ''
  resetSelectionConversation()
})
watch(mode, nextMode => {
  emit('mode-change', nextMode)
  if (nextMode === WORKBENCH_MODES.PAPER_COMPARISON || nextMode === WORKBENCH_MODES.RESEARCH_GAP) {
    emit('paper-ids-change', [Number(props.paper.id), ...normalizeInitialPaperIds(comparisonPaperIds.value)])
  }
  if (nextMode === WORKBENCH_MODES.RESEARCH_GAP && fieldGapSourceRunId.value) {
    selectRun(null)
    return
  }
  if (running.value || trace.value?.plan?.workflow === nextMode) return
  const matchingRun = recentRuns.value.find(item => item.plan?.workflow === nextMode)
  selectRun(matchingRun || null)
})
watch(trace, nextTrace => {
  if (nextTrace?.plan?.workflow === WORKBENCH_MODES.RESEARCH_GAP) {
    fieldGapSourceRunId.value = nextTrace.invocation?.sourceRunId || ''
  }
  resultLanguage.value = detectTextLanguage(nextTrace?.result?.answer)
  resultTranslationError.value = ''
})
watch(() => props.initialMode, nextMode => {
  const normalized = normalizeInitialMode(nextMode)
  if (!running.value && normalized !== mode.value) mode.value = normalized
})
watch(() => props.initialPaperIds, nextIds => {
  comparisonPaperIds.value = normalizeInitialPaperIds(nextIds)
}, { deep: true })
watch(comparisonPaperIds, nextIds => {
  if (!isMultiPaperMode.value) return
  emit('paper-ids-change', [Number(props.paper.id), ...normalizeInitialPaperIds(nextIds)])
}, { deep: true })

onMounted(async () => {
  papersLoading.value = true
  try {
    const papers = await listPapers()
    availablePapers.value = papers.filter(item => Number(item.id) !== Number(props.paper.id))
    const eligibleIds = new Set(eligibleComparisonPapers.value.map(item => Number(item.id)))
    comparisonPaperIds.value = comparisonPaperIds.value.filter(id => eligibleIds.has(Number(id)))
  } catch {
    availablePapers.value = []
  } finally {
    papersLoading.value = false
  }
  try {
    await loadRecent(props.paper.id)
    if (props.initialMode) {
      const requestedMode = normalizeInitialMode(props.initialMode)
      mode.value = requestedMode
      const matchingRun = recentRuns.value.find(item => item.plan?.workflow === requestedMode)
      if (trace.value?.plan?.workflow !== requestedMode) selectRun(matchingRun || null)
    } else {
      const restoredMode = trace.value?.plan?.workflow
      if (supportedWorkbenchModes.includes(restoredMode)) mode.value = restoredMode
    }
  } catch { /* history is optional */ }
})

async function startRun() {
  try {
    const effectiveQuestion = isMultiPaperMode.value
      ? buildComparisonQuestion(question.value, comparisonDimensions.value)
      : question.value
    const request = buildWorkbenchPlanRequest({
      mode: mode.value,
      paperId: props.paper.id,
      comparisonPaperIds: comparisonPaperIds.value,
      question: effectiveQuestion,
      selectionAnchor: activeSelectionAnchor.value,
      sourceRunId: isFieldGapMode.value ? fieldGapSourceRunId.value : '',
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

async function sendSelectionMessage() {
  const content = String(question.value || '').trim()
  const anchor = activeSelectionAnchor.value
  const selectedText = activeSelectionText.value
  const selectedIdentity = activeSelectionIdentity.value
  if (!content || !anchor || running.value) return
  if (!selectionConversationId.value) selectionConversationId.value = createConversationId()
  const conversationId = selectionConversationId.value
  const conversationContext = buildSelectionConversationContext()
  const userMessage = {
    id: `user-${++selectionMessageSequence}`,
    role: 'user',
    content,
  }
  selectionMessages.value.push(userMessage)
  selectionChatError.value = ''
  await scrollSelectionChat()

  try {
    const request = buildWorkbenchPlanRequest({
      mode: WORKBENCH_MODES.SELECTION_QA,
      paperId: props.paper.id,
      question: content,
      selectionAnchor: anchor,
      conversationId,
      conversationContext,
    })
    const completed = await run(request)
    if (selectionConversationId.value !== conversationId
        || activeSelectionIdentity.value !== selectedIdentity
        || activeSelectionText.value !== selectedText) return
    selectionMessages.value.push({
      id: completed.runId || `assistant-${++selectionMessageSequence}`,
      role: 'assistant',
      content: completed.result?.answer || '',
      claims: completed.result?.claims || [],
      evidence: completed.result?.evidence || [],
      regionFallback: Boolean(completed.result?.regionFallback),
    })
    await scrollSelectionChat()
    questions[WORKBENCH_MODES.SELECTION_QA] = ''
    await loadRecent(props.paper.id)
  } catch (reason) {
    if (selectionConversationId.value === conversationId) {
      const index = selectionMessages.value.findIndex(item => item.id === userMessage.id)
      if (index >= 0) selectionMessages.value.splice(index, 1)
      selectionChatError.value = reason?.response?.data?.message || reason?.message || '选区对话失败'
    }
  }
}

function resetSelectionConversation() {
  selectionConversationId.value = ''
  selectionMessages.value = []
  selectionChatError.value = ''
  questions[WORKBENCH_MODES.SELECTION_QA] = ''
  if (!running.value && mode.value === WORKBENCH_MODES.SELECTION_QA) selectRun(null)
}

function createConversationId() {
  if (globalThis.crypto?.randomUUID) return `selection-${globalThis.crypto.randomUUID()}`
  return `selection-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

function buildSelectionConversationContext() {
  return selectionMessages.value.slice(-8).map(message =>
    `${message.role === 'user' ? '用户' : '论文助手'}：${message.content}`)
    .join('\n\n')
    .slice(-6000)
}

function messageHtml(message) {
  return workbenchMarkdownToHtml(message?.content || '')
}

function messageEvidenceForClaim(message, claim) {
  const index = new Map((message?.evidence || []).map(item => [item.evidenceId, item]))
  return (claim?.evidenceIds || []).map(id => index.get(id)).filter(Boolean)
}

async function scrollSelectionChat() {
  await nextTick()
  const container = selectionChatMessages.value
  if (container) container.scrollTop = container.scrollHeight
}

async function translateSelection() {
  const text = props.selection?.text
  if (!text || selectionTranslationLoading.value) return
  const targetLanguage = selectionTargetLanguage.value
  selectionTranslationLoading.value = true
  selectionTranslationError.value = ''
  try {
    const response = await translateTexts({
      texts: [text],
      sourceLanguage: detectTextLanguage(text),
      targetLanguage,
    })
    const item = response?.items?.[0]
    if (!item?.text) throw new Error('翻译服务未返回内容')
    if (props.selection?.text !== text) return
    selectionTranslation.value = { text: item.text, targetLanguage }
  } catch (reason) {
    if (props.selection?.text === text) {
      selectionTranslationError.value = translationErrorMessage(reason)
    }
  } finally {
    selectionTranslationLoading.value = false
  }
}

async function selectResultLanguage(targetLanguage) {
  if (!originalAnswer.value || resultTranslationLoading.value) return
  resultTranslationError.value = ''
  if (targetLanguage === originalResultLanguage.value
      || resultTranslationCache.has(resultTranslationKey(targetLanguage))) {
    resultLanguage.value = targetLanguage
    return
  }
  const sourceAnswer = originalAnswer.value
  const sourceClaims = trace.value?.result?.claims || []
  const runId = trace.value?.runId || 'result'
  const cacheKey = `${runId}:${targetLanguage}`
  resultTranslationLoading.value = true
  try {
    const response = await translateTexts({
      texts: [sourceAnswer, ...sourceClaims.map(claim => claim.text)],
      sourceLanguage: originalResultLanguage.value,
      targetLanguage,
    })
    if (!response?.items?.[0]?.text) throw new Error('翻译服务未返回内容')
    resultTranslationCache.set(cacheKey, response)
    if ((trace.value?.runId || 'result') !== runId || originalAnswer.value !== sourceAnswer) return
    resultLanguage.value = targetLanguage
  } catch (reason) {
    if ((trace.value?.runId || 'result') === runId) {
      resultTranslationError.value = translationErrorMessage(reason)
    }
  } finally {
    resultTranslationLoading.value = false
  }
}

function resultTranslationKey(targetLanguage) {
  return `${trace.value?.runId || 'result'}:${targetLanguage}`
}

function translationErrorMessage(reason) {
  return reason?.response?.data?.message || reason?.message || '翻译失败，请重试'
}

async function startFieldGapFromComparison() {
  const source = trace.value
  if (!completedComparisonResult.value || !source?.runId) return
  if (comparisonResultPaperIds.value.length < 3) {
    ElMessage.warning('领域研究空白至少需要三篇论文')
    return
  }
  fieldGapSourceRunId.value = source.runId
  comparisonPaperIds.value = comparisonResultPaperIds.value
    .filter(id => id !== Number(props.paper.id))
  mode.value = WORKBENCH_MODES.RESEARCH_GAP
  await nextTick()
  await startRun()
}

async function returnToComparison() {
  const sourceRunId = fieldGapSourceRunId.value || trace.value?.invocation?.sourceRunId || ''
  const sourceRun = recentRuns.value.find(item => item.runId === sourceRunId) || null
  mode.value = WORKBENCH_MODES.PAPER_COMPARISON
  await nextTick()
  if (sourceRun) {
    comparisonPaperIds.value = (sourceRun.invocation?.paperIds || [])
      .map(Number).filter(id => id !== Number(props.paper.id))
    selectRun(sourceRun)
  }
}

function selectProductMode(nextMode) {
  if (running.value) return
  if (isFieldGapMode.value && nextMode === WORKBENCH_MODES.PAPER_COMPARISON) {
    void returnToComparison()
    return
  }
  mode.value = nextMode
}

function isModeTabActive(tabMode) {
  return mode.value === tabMode
    || (tabMode === WORKBENCH_MODES.PAPER_COMPARISON && isFieldGapMode.value)
}

function comparisonOptionDisabled(paper) {
  if (!paper?.pdfPath) return true
  const normalizedId = Number(paper.id)
  return comparisonState.value.atLimit
    && !comparisonState.value.additionalIds.includes(normalizedId)
}

function toggleComparisonDimension(dimension) {
  const index = comparisonDimensions.value.indexOf(dimension)
  if (index >= 0) comparisonDimensions.value.splice(index, 1)
  else comparisonDimensions.value.push(dimension)
}

function paperDisplayName(paperId) {
  const paper = paperCatalog.value.find(item => Number(item.id) === Number(paperId))
  return paper?.title || `论文 #${paperId}`
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

function workflowLabel(workflow) {
  return {
    SELECTION_QA: '选区问答',
    PAPER_ANALYSIS: '全文分析',
    PAPER_IMPROVEMENT: '论文改进空间',
    ANNOTATION_SUGGESTION: '批注建议',
    PAPER_COMPARISON: '跨论文对比',
    RESEARCH_GAP: '领域研究空白',
  }[workflow] || '论文助手运行'
}

function normalizeInitialMode(value) {
  return supportedWorkbenchModes.includes(value)
    ? value : WORKBENCH_MODES.PAPER_ANALYSIS
}

function normalizeInitialPaperIds(values) {
  return [...new Set((values || []).map(Number))]
    .filter(id => Number.isInteger(id) && id > 0 && id !== Number(props.paper.id))
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

</script>

<style scoped>
.paper-workbench {
  flex: 0 0 360px;
  min-width: 0;
  overflow-y: auto;
  box-sizing: border-box;
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
.section-heading .selection-clear-action { font-size: 18px; }
.selection-card p { max-height: 76px; overflow: auto; margin: 0 0 8px; padding: 8px; border-left: 3px solid var(--ra-link); background: var(--ra-hover-bg); font-size: 12px; line-height: 1.45; white-space: pre-wrap; }
.selection-tools { display: flex; justify-content: flex-start; margin-bottom: 8px; }
.selection-translation { margin: 0 0 8px; padding: 8px; border-radius: 6px; background: color-mix(in srgb, var(--ra-link) 7%, var(--ra-panel-bg)); font-size: 12px; line-height: 1.5; white-space: pre-wrap; }
.selection-translation small { display: block; margin-bottom: 3px; color: var(--ra-text-tertiary); font-size: 9px; }
.selection-meta { color: var(--ra-text-tertiary); font-size: 11px; }
.workflow-tabs { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 5px; margin-bottom: 9px; }
.workflow-tabs button { min-width: 0; padding: 7px 4px; border: 1px solid var(--ra-border); border-radius: 6px; color: var(--ra-text-secondary); background: transparent; cursor: pointer; }
.workflow-tabs button.active { border-color: var(--ra-link); color: var(--ra-link); background: var(--ra-hover-bg); }
.workflow-tabs button:disabled { opacity: .45; cursor: not-allowed; }
.selection-chat { display: flex; flex-direction: column; gap: 9px; }
.selection-chat__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.selection-chat__heading > div { display: flex; flex-direction: column; min-width: 0; gap: 2px; }
.selection-chat__heading b { font-size: 12px; }
.selection-chat__heading small { color: var(--ra-text-tertiary); font-size: 9px; line-height: 1.4; }
.selection-chat__heading > button { flex: 0 0 auto; padding: 3px 6px; border: 0; color: var(--ra-link); background: transparent; font-size: 10px; cursor: pointer; }
.selection-chat__heading > button:disabled { opacity: .5; cursor: not-allowed; }
.selection-chat__messages { display: flex; flex-direction: column; gap: 8px; max-height: 420px; overflow-y: auto; padding: 2px; }
.selection-chat__empty { padding: 18px 10px; border: 1px dashed var(--ra-border); border-radius: 7px; color: var(--ra-text-tertiary); font-size: 11px; line-height: 1.5; text-align: center; }
.chat-message { max-width: 94%; padding: 9px; border: 1px solid var(--ra-border); border-radius: 8px; background: var(--ra-panel-bg); }
.chat-message.is-user { align-self: flex-end; border-color: color-mix(in srgb, var(--ra-link) 32%, var(--ra-border)); background: color-mix(in srgb, var(--ra-link) 8%, var(--ra-panel-bg)); }
.chat-message.is-assistant { align-self: flex-start; }
.chat-message__role { margin-bottom: 4px; color: var(--ra-text-tertiary); font-size: 9px; }
.chat-message__text { font-size: 12px; line-height: 1.55; white-space: pre-wrap; }
.chat-message.is-pending { width: 82%; }
.chat-message.is-pending .trace-dots { margin: 0 4px; }
.chat-claim-list { display: flex; flex-direction: column; gap: 7px; margin: 9px 0 0; padding-left: 17px; }
.chat-claim-list li { font-size: 10px; line-height: 1.45; }
.selection-chat__actions { margin-top: 0; }
.comparison-stage-banner { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 9px; padding: 8px 9px; border: 1px solid color-mix(in srgb, var(--ra-link) 42%, var(--ra-border)); border-radius: 7px; background: color-mix(in srgb, var(--ra-link) 7%, var(--ra-panel-bg)); }
.comparison-stage-banner > div { display: flex; flex-direction: column; min-width: 0; gap: 2px; }
.comparison-stage-banner b { color: var(--ra-link); font-size: 11px; }
.comparison-stage-banner small { color: var(--ra-text-tertiary); font-size: 9px; line-height: 1.35; }
.comparison-stage-banner button { flex: 0 0 auto; padding: 3px 6px; border: 0; color: var(--ra-link); background: transparent; font-size: 9px; cursor: pointer; }
.paper-selector { width: 100%; margin-bottom: 9px; }
.comparison-config { margin: -1px 0 10px; padding: 9px; border: 1px solid var(--ra-border); border-radius: 7px; background: var(--ra-hover-bg); }
.comparison-base { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.comparison-base > div { display: flex; flex-direction: column; min-width: 0; gap: 2px; }
.comparison-base small, .comparison-hint { color: var(--ra-text-tertiary); font-size: 10px; }
.comparison-base b { overflow: hidden; color: var(--ra-text); font-size: 11px; font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }
.comparison-hint { margin-top: 7px; }
.comparison-hint.ready { color: #3a8b3d; }
.dimension-picker { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 8px; }
.dimension-picker button { padding: 3px 7px; border: 1px solid var(--ra-border); border-radius: 999px; color: var(--ra-text-secondary); background: var(--ra-panel-bg); font-size: 10px; cursor: pointer; }
.dimension-picker button.active { border-color: color-mix(in srgb, var(--ra-link) 60%, var(--ra-border)); color: var(--ra-link); background: color-mix(in srgb, var(--ra-link) 9%, var(--ra-panel-bg)); }
.compose-actions { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 9px; }
.compose-actions span { color: var(--ra-text-tertiary); font-size: 10px; }
.history-select { width: 150px; }
.trace-dots { display: grid; grid-template-columns: repeat(4, 1fr); align-items: center; margin: 2px 10px 4px; }
.trace-dot-item { position: relative; display: grid; min-width: 28px; height: 28px; place-items: center; outline: none; }
.trace-dot-item:not(:last-child)::after { position: absolute; z-index: 0; top: 50%; left: calc(50% + 7px); width: calc(100% - 14px); height: 1px; background: var(--ra-border); content: ''; }
.step-dot { z-index: 1; width: 9px; height: 9px; box-sizing: border-box; border: 2px solid var(--ra-border); border-radius: 50%; background: var(--ra-panel-bg); }
.trace-dot-item:focus-visible .step-dot { outline: 3px solid color-mix(in srgb, var(--ra-link) 22%, transparent); outline-offset: 3px; }
.trace-dot-item.is-running .step-dot { border-color: var(--ra-link); background: var(--ra-link); animation: trace-pulse 1.2s ease-out infinite; }
.trace-dot-item.is-completed .step-dot { border-color: #4caf50; background: #4caf50; }
.trace-dot-item.is-failed .step-dot { border-color: var(--el-color-danger); background: var(--el-color-danger); }
@keyframes trace-pulse { 0% { box-shadow: 0 0 0 0 color-mix(in srgb, var(--ra-link) 30%, transparent); } 75%, 100% { box-shadow: 0 0 0 6px transparent; } }
.answer-text { font-size: 12px; line-height: 1.65; overflow-wrap: anywhere; }
.result-language-switch { display: flex; padding: 2px; border: 1px solid var(--ra-border); border-radius: 6px; background: var(--ra-hover-bg); }
.section-heading .result-language-switch button { padding: 3px 7px; border-radius: 4px; color: var(--ra-text-tertiary); font-size: 10px; }
.section-heading .result-language-switch button.active { color: var(--ra-link); background: var(--ra-panel-bg); box-shadow: 0 0 0 1px var(--ra-border); }
.section-heading .result-language-switch button:disabled { cursor: wait; opacity: .6; }
.translation-state { padding-top: 0; }
.result-selectable-content { user-select: text; }
.answer-text :deep(h3), .answer-text :deep(h4), .answer-text :deep(h5) { margin: 12px 0 5px; font-size: 13px; line-height: 1.4; }
.answer-text :deep(h3:first-child), .answer-text :deep(h4:first-child) { margin-top: 0; }
.answer-text :deep(p) { margin: 5px 0; }
.answer-text :deep(ul), .answer-text :deep(ol) { margin: 5px 0; padding-left: 19px; }
.answer-text :deep(li) { margin: 3px 0; }
.answer-text :deep(code) { padding: 1px 3px; border-radius: 3px; background: var(--ra-hover-bg); }
.claim-list { display: flex; flex-direction: column; gap: 10px; margin: 12px 0 0; padding-left: 19px; }
.comparison-coverage { display: flex; flex-direction: column; gap: 6px; margin-top: 13px; padding: 9px; border: 1px solid var(--ra-border); border-radius: 7px; background: var(--ra-hover-bg); }
.comparison-followup { display: flex; align-items: center; justify-content: space-between; gap: 9px; margin-top: 13px; padding: 10px; border: 1px solid color-mix(in srgb, var(--ra-link) 38%, var(--ra-border)); border-radius: 7px; background: color-mix(in srgb, var(--ra-link) 6%, var(--ra-panel-bg)); }
.comparison-followup > div { display: flex; flex-direction: column; min-width: 0; gap: 3px; }
.comparison-followup b { font-size: 11px; }
.comparison-followup small { color: var(--ra-text-tertiary); font-size: 9px; line-height: 1.4; }
.coverage-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 2px; }
.coverage-heading b { font-size: 11px; font-weight: 600; }
.coverage-row { display: flex; align-items: center; gap: 8px; width: 100%; padding: 7px; border: 1px solid var(--ra-border); border-radius: 6px; color: var(--ra-text); background: var(--ra-panel-bg); text-align: left; cursor: pointer; }
.coverage-row:disabled { cursor: default; opacity: .75; }
.coverage-row.covered { border-color: color-mix(in srgb, #4caf50 45%, var(--ra-border)); }
.coverage-state { display: grid; flex: 0 0 auto; width: 18px; height: 18px; place-items: center; border-radius: 50%; color: #a66000; background: color-mix(in srgb, #e6a23c 15%, transparent); font-size: 11px; font-weight: 700; }
.coverage-row.covered .coverage-state { color: #36883a; background: color-mix(in srgb, #4caf50 15%, transparent); }
.coverage-paper { display: flex; flex: 1; flex-direction: column; min-width: 0; gap: 2px; }
.coverage-paper b { overflow: hidden; font-size: 11px; font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }
.coverage-paper small { color: var(--ra-text-tertiary); font-size: 9px; }
.claim-list li { padding-left: 2px; font-size: 11px; line-height: 1.5; }
.evidence-links { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 5px; }
.evidence-links button { padding: 2px 6px; border: 1px solid color-mix(in srgb, var(--ra-link) 45%, var(--ra-border)); border-radius: 999px; color: var(--ra-link); background: transparent; font-size: 10px; cursor: pointer; }
.muted-state, .error-state, .region-warning { padding: 7px 0; color: var(--ra-text-tertiary); font-size: 11px; line-height: 1.45; }
.error-state { color: var(--el-color-danger); }
.region-warning { color: #a66000; }
@media (max-width: 1180px) { .paper-workbench { flex-basis: 320px; } }
</style>
