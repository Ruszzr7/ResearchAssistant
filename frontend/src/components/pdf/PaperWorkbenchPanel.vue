<template>
  <aside class="paper-workbench" aria-label="论文分析工作台">
    <nav class="product-tabs" role="tablist" aria-label="论文分析功能">
      <button
        v-for="item in productTabs"
        :key="item.value"
        type="button"
        role="tab"
        :aria-selected="activeProductTab === item.value"
        :class="{ active: activeProductTab === item.value }"
        :disabled="running"
        @click="selectProductTab(item.value)"
      >{{ item.label }}</button>
    </nav>

    <template v-if="activeProductTab === 'reading'">
      <section
        v-if="showMemoryStatus"
        class="memory-status"
        :class="`is-${String(memoryStatus?.status || '').toLowerCase()}`"
        aria-live="polite"
      >
        <div v-if="memoryActive" class="memory-orbit" aria-hidden="true"><span /></div>
        <div v-else class="memory-status__mark" aria-hidden="true">{{ memoryStatus?.status === 'PARTIAL' ? '!' : '✦' }}</div>
        <div class="memory-status__body">
          <b>{{ memoryStatus?.stageText || '正在准备论文记忆…' }}</b>
          <small v-if="memoryStatus?.totalChunks">
            已处理 {{ memoryProcessedChunks }}/{{ memoryStatus.totalChunks }} 个分块
            <template v-if="memoryStatus.failedChunks"> · {{ memoryStatus.failedChunks }} 个待重试</template>
          </small>
          <small v-else>全文理解在后台运行，不影响先选取内容提问。</small>
          <div v-if="memoryActive && memoryStatus?.totalChunks" class="memory-progress" aria-hidden="true">
            <span :style="{ width: `${memoryStatus.progress || 0}%` }" />
          </div>
        </div>
        <button
          v-if="memoryStatus?.canStart"
          type="button"
          class="memory-status__action"
          :disabled="memoryStarting"
          @click="startMemoryUnderstanding"
        >{{ memoryStarting ? '启动中…' : (memoryStatus?.canRetry ? '重试' : '开始理解') }}</button>
      </section>

      <section class="capture-section">
        <div class="capture-switch" :class="{ 'is-formula': captureMode === 'formula' }" role="tablist" aria-label="精读内容选取方式">
          <span class="capture-switch__indicator" aria-hidden="true" />
          <button
            type="button"
            role="tab"
            :aria-selected="captureMode === 'text'"
            :class="{ active: captureMode === 'text' }"
            @click="selectCaptureMode('text')"
          >内容选取</button>
          <button
            type="button"
            role="tab"
            :aria-selected="captureMode === 'formula'"
            :class="{ active: captureMode === 'formula' }"
            @click="selectCaptureMode('formula')"
          >公式框选</button>
        </div>
        <p class="capture-hint">
          {{ captureMode === 'formula'
            ? '在 PDF 页面拖框截取公式，识别后核对 LaTeX。'
            : '直接在 PDF 中拖动选择文字，再确认固定为本轮内容。' }}
        </p>
      </section>

      <div class="content-stage" aria-live="polite">
        <FormulaRegionCard
          v-if="formulaRegion"
          :region="formulaRegion"
          :recognition="formulaRecognition"
          :loading="formulaLoading"
          :confirming="formulaConfirming"
          :error="formulaError"
          @clear="clearFormula"
          @retry="$emit('retry-formula')"
          @confirm="$emit('confirm-formula', $event)"
        />

        <section v-else-if="selection" class="selection-card">
          <div class="section-heading">
            <div>
              <b>选取内容</b>
              <small v-if="selectionAnchor?.page">第 {{ selectionAnchor.page }} 页</small>
            </div>
            <button type="button" class="selection-clear-action" aria-label="清除选取内容" @click="clearTextSelection">×</button>
          </div>
          <p class="selection-card__text">{{ selection.text }}</p>
          <figure v-if="selection.visualFallback?.dataUrl" class="selection-source-preview">
            <img :src="selection.visualFallback.dataUrl" alt="PDF 原始选区图像" />
            <figcaption>{{ selection.visualFallback.reason }}</figcaption>
          </figure>

          <div class="selection-tools">
            <el-button
              size="small"
              plain
              :loading="selectionTranslationLoading"
              @click="translateSelection"
            >翻译为{{ languageLabel(selectionTargetLanguage) }}</el-button>
            <el-button
              v-if="!textSelectionConfirmed"
              type="primary"
              size="small"
              :loading="selectionLoading"
              :disabled="!selectionAnchor || Boolean(selectionError)"
              @click="confirmTextSelection"
            >确认并固定</el-button>
            <el-tag v-else size="small" type="success" effect="plain">已固定</el-tag>
          </div>

          <div v-if="selectionTranslation" class="selection-translation">
            <small>译文 · {{ languageLabel(selectionTranslation.targetLanguage) }}</small>
            <div>{{ selectionTranslation.text }}</div>
          </div>
          <div v-if="selectionTranslationError" class="error-state">{{ selectionTranslationError }}</div>
          <div v-if="selectionLoading" class="muted-state">正在准备所选内容…</div>
          <div v-else-if="selectionError" class="error-state">{{ selectionError }}</div>
          <div v-else-if="selectionMappingIsRegion" class="warning-state" role="status">
            当前选区只能定位到页面区域，未建立可信的精确文本映射。可重新选择更清晰的文字；若继续固定，回答会明确要求回原页核对。
          </div>
          <div v-else-if="selectionIsMathRich" class="math-rich-state" role="status">
            已精确定位文字；检测到多个行内数学片段。提问时会同时提供 PDF 原文和本地 LaTeX 辅助，近似转写仍以原页排版为准。
          </div>
          <div v-else-if="!textSelectionConfirmed" class="content-confirm-hint">确认后才会作为对话依据，继续拖选可重新调整范围。</div>
        </section>

        <section v-else class="content-empty">
          <div class="content-empty__icon" aria-hidden="true">⌁</div>
          <b>{{ captureMode === 'formula' ? '框选一个公式' : '选择一段论文内容' }}</b>
          <p>{{ captureMode === 'formula' ? '识别结果和 LaTeX 编辑器会显示在这里。' : '原文会直接显示在这里，确认后即可开始提问。' }}</p>
        </section>
      </div>

      <section class="selection-chat" aria-label="论文精读对话">
        <div class="selection-chat__heading">
          <div>
            <b>论文精读对话</b>
            <small v-if="activeSelectionAnchor">已使用上方固定内容作为本轮依据</small>
            <small v-else>请先选取并确认内容</small>
          </div>
          <button type="button" :disabled="running" @click="resetSelectionConversation">新对话</button>
        </div>

        <div ref="selectionChatMessages" class="selection-chat__messages" aria-live="polite">
          <div v-if="!selectionMessages.length && !running" class="selection-chat__empty">
            <span aria-hidden="true">✦</span>
            <b>围绕论文内容继续追问</b>
            <p>可以询问概念含义、推导过程、实验结论，或它与全文的关系。</p>
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

        <div class="assistant-composer" :class="{ disabled: !activeSelectionAnchor }">
          <el-input
            v-model="question"
            class="assistant-composer__input"
            type="textarea"
            :rows="3"
            maxlength="4000"
            resize="none"
            :placeholder="activeSelectionAnchor ? '向论文助手提问…' : '确认上方内容后即可提问'"
            @keydown.ctrl.enter.prevent="sendSelectionMessage"
          />
          <div class="assistant-composer__footer">
            <span>Ctrl + Enter 发送</span>
            <el-button
              type="primary"
              size="small"
              :loading="running"
              :disabled="selectionChatDisabled"
              @click="sendSelectionMessage"
            >发送</el-button>
          </div>
        </div>
        <div v-if="selectionChatError || error" class="error-state">{{ selectionChatError || error }}</div>
      </section>
    </template>

    <section v-else class="future-feature">
      <span class="future-feature__badge">后续阶段</span>
      <h3>{{ activeProductTab === 'defect' ? '缺陷分析' : '论文对比' }}</h3>
      <p v-if="activeProductTab === 'defect'">单篇论文精读稳定后，再接入假设、方法、实验与证据边界的系统检查。</p>
      <p v-else>单篇论文记忆与引用溯源稳定后，再接入多文献对齐和对比分析。</p>
    </section>
  </aside>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getPaperMemoryStatus, startPaperUnderstanding } from '@/api/paperMemory.js'
import { translateTexts } from '@/api/workbench.js'
import {
  appendResearchMessages,
  createResearchSession,
  getResearchSession,
} from '@/api/researchArchive.js'
import FormulaRegionCard from '@/components/pdf/FormulaRegionCard.vue'
import { usePaperWorkbench } from '@/composables/usePaperWorkbench.js'
import {
  detectTextLanguage,
  languageLabel,
  oppositeLanguage,
} from '@/utils/translation.js'
import {
  buildWorkbenchPlanRequest,
  compactTracePhases,
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
  captureMode: { type: String, default: 'text' },
  initialMode: { type: String, default: '' },
  initialPaperIds: { type: Array, default: () => [] },
  researchSessionId: { type: Number, default: null },
})

const emit = defineEmits([
  'clear-selection', 'clear-formula', 'retry-formula', 'confirm-formula',
  'capture-mode-change', 'jump-evidence', 'mode-change', 'paper-ids-change',
  'research-session-change',
])

const { trace, running, error, run, loadRecent } = usePaperWorkbench()
const productTabs = [
  { value: 'reading', label: '论文精读', mode: WORKBENCH_MODES.SELECTION_QA },
  { value: 'defect', label: '缺陷分析', mode: WORKBENCH_MODES.PAPER_IMPROVEMENT },
  { value: 'comparison', label: '论文对比', mode: WORKBENCH_MODES.PAPER_COMPARISON },
]
const activeProductTab = ref(productTabForMode(props.initialMode))
const question = ref('')
const confirmedText = ref(null)
const selectionTranslation = ref(null)
const selectionTranslationLoading = ref(false)
const selectionTranslationError = ref('')
const selectionMessages = ref([])
const selectionConversationId = ref('')
const selectionChatError = ref('')
const selectionChatMessages = ref(null)
const activeResearchSessionId = ref(positiveSessionId(props.researchSessionId))
let sessionCreatePromise = null
let selectionMessageSequence = 0
let selectionConversationSequence = 0
let memoryPollTimer = null

const memoryStatus = ref(null)
const memoryStarting = ref(false)
const memoryActive = computed(() => memoryStatus.value?.status === 'UNDERSTANDING')
const memoryProcessedChunks = computed(() => (
  Number(memoryStatus.value?.completedChunks || 0) + Number(memoryStatus.value?.failedChunks || 0)
))
const showMemoryStatus = computed(() => Boolean(
  memoryStatus.value && memoryStatus.value.status !== 'READY',
))

const selectionTargetLanguage = computed(() => oppositeLanguage(detectTextLanguage(props.selection?.text)))
const selectionMappingIsRegion = computed(() => (
  props.selectionAnchor?.mappingStatus
    ? props.selectionAnchor.mappingStatus === 'REGION'
    : props.selectionAnchor?.kind === 'REGION'
))
const selectionIsMathRich = computed(() => (
  props.selectionAnchor?.contentType === 'MATH_RICH_TEXT'
))
const textSelectionIdentity = computed(() => {
  if (!props.selection?.text) return ''
  const page = props.selectionAnchor?.page || props.selection?.groups?.[0]?.pageNum || ''
  return `text:${page}:${props.selection.text}`
})
const textSelectionConfirmed = computed(() => Boolean(
  confirmedText.value?.identity
  && confirmedText.value.identity === textSelectionIdentity.value
  && confirmedText.value.anchor,
))
const confirmedFormula = computed(() => (
  props.formulaRegion && props.formulaRecognition?.confirmed && props.formulaRecognition?.anchor
    ? props.formulaRecognition : null
))
const activeSelectionAnchor = computed(() => (
  props.formulaRegion ? confirmedFormula.value?.anchor || null : confirmedText.value?.anchor || null
))
const activeSelectionText = computed(() => (
  props.formulaRegion ? confirmedFormula.value?.latex || '' : confirmedText.value?.text || ''
))
const activeSelectionIdentity = computed(() => {
  if (props.formulaRegion) {
    return confirmedFormula.value ? `formula:${confirmedFormula.value.id}:${confirmedFormula.value.latex}` : ''
  }
  return confirmedText.value?.identity || ''
})
const formulaDraftIdentity = computed(() => {
  if (!props.formulaRegion) return ''
  const box = props.formulaRegion.bbox || {}
  return `formula:${props.formulaRegion.page}:${box.x}:${box.y}:${box.width}:${box.height}:${props.formulaRecognition?.id || ''}:${Boolean(props.formulaRecognition?.confirmed)}`
})
const selectionChatDisabled = computed(() => (
  running.value || !activeSelectionAnchor.value || !question.value.trim()
))
const tracePhases = computed(() => compactTracePhases(trace.value))

watch(textSelectionIdentity, () => {
  confirmedText.value = null
  selectionTranslation.value = null
  selectionTranslationError.value = ''
  resetSelectionConversation()
})
watch(formulaDraftIdentity, () => resetSelectionConversation())
watch(() => props.initialMode, mode => { activeProductTab.value = productTabForMode(mode) })
watch(() => props.researchSessionId, nextId => {
  const normalized = positiveSessionId(nextId)
  if (normalized === activeResearchSessionId.value) return
  activeResearchSessionId.value = normalized
  if (normalized) void restoreResearchMessages(normalized)
})
watch(() => props.paper.id, () => { void loadMemoryStatus() })

onMounted(async () => {
  await loadMemoryStatus()
  try { await loadRecent(props.paper.id) } catch { /* History is optional. */ }
  if (activeResearchSessionId.value) await restoreResearchMessages(activeResearchSessionId.value)
})
onBeforeUnmount(() => clearTimeout(memoryPollTimer))

async function loadMemoryStatus() {
  clearTimeout(memoryPollTimer)
  memoryPollTimer = null
  const paperId = Number(props.paper.id)
  try {
    const status = await getPaperMemoryStatus(paperId)
    if (Number(props.paper.id) !== paperId) return
    memoryStatus.value = status
    if (status?.status === 'UNDERSTANDING') {
      memoryPollTimer = setTimeout(() => { void loadMemoryStatus() }, 1800)
    }
  } catch { /* Memory readiness must not block PDF reading. */ }
}

async function startMemoryUnderstanding() {
  if (memoryStarting.value || !memoryStatus.value?.canStart) return
  memoryStarting.value = true
  try {
    const revision = Number(memoryStatus.value?.revision || 0)
    await startPaperUnderstanding(
      props.paper.id,
      `paper-memory-ui:${props.paper.id}:${revision}`,
    )
    memoryStatus.value = {
      ...memoryStatus.value,
      status: 'UNDERSTANDING',
      stageText: '论文理解任务已提交…',
      canStart: false,
    }
    memoryPollTimer = setTimeout(() => { void loadMemoryStatus() }, 800)
  } catch (reason) {
    ElMessage.error(requestErrorMessage(reason, '论文理解任务启动失败'))
  } finally {
    memoryStarting.value = false
  }
}

function selectProductTab(tab) {
  if (running.value || tab === activeProductTab.value) return
  activeProductTab.value = tab
  const option = productTabs.find(item => item.value === tab)
  if (option) emit('mode-change', option.mode)
}

function selectCaptureMode(mode) {
  if (!['text', 'formula'].includes(mode) || mode === props.captureMode) return
  emit('capture-mode-change', mode)
}

function confirmTextSelection() {
  if (!props.selection?.text || !props.selectionAnchor || props.selectionLoading || props.selectionError) return
  confirmedText.value = {
    identity: textSelectionIdentity.value,
    text: props.selection.text,
    anchor: props.selectionAnchor,
  }
}

function clearTextSelection() {
  confirmedText.value = null
  resetSelectionConversation()
  emit('clear-selection')
}

function clearFormula() {
  resetSelectionConversation()
  emit('clear-formula')
}

async function sendSelectionMessage() {
  const content = question.value.trim()
  const anchor = activeSelectionAnchor.value
  const selectedText = activeSelectionText.value
  const selectedIdentity = activeSelectionIdentity.value
  if (!content || !anchor || running.value) return

  let sessionId
  try { sessionId = await ensureResearchSession() }
  catch (reason) {
    selectionChatError.value = requestErrorMessage(reason, '研究档案创建失败')
    return
  }
  if (!selectionConversationId.value) {
    selectionConversationId.value = freshSelectionConversationId(sessionId)
  }
  const conversationId = selectionConversationId.value
  const userMessage = { id: `user-${++selectionMessageSequence}`, role: 'user', content }
  selectionMessages.value.push(userMessage)
  selectionChatError.value = ''
  question.value = ''
  await scrollSelectionChat()

  try {
    const request = buildWorkbenchPlanRequest({
      mode: WORKBENCH_MODES.SELECTION_QA,
      paperId: props.paper.id,
      question: content,
      selectionAnchor: anchor,
      conversationId,
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
    try {
      await appendResearchMessages(sessionId, [
        {
          messageKey: `${completed.runId}:user`, role: 'USER', content,
          runId: completed.runId, selectionAnchor: anchor,
        },
        {
          messageKey: `${completed.runId}:assistant`, role: 'ASSISTANT',
          content: completed.result?.answer || '', runId: completed.runId,
          evidence: {
            claims: completed.result?.claims || [],
            evidence: completed.result?.evidence || [],
            regionFallback: Boolean(completed.result?.regionFallback),
          },
        },
      ])
    } catch { ElMessage.warning('回答已完成，研究档案将在后台补全') }
    await scrollSelectionChat()
    try { await loadRecent(props.paper.id) } catch { /* History is optional. */ }
  } catch (reason) {
    if (selectionConversationId.value === conversationId) {
      const index = selectionMessages.value.findIndex(item => item.id === userMessage.id)
      if (index >= 0) selectionMessages.value.splice(index, 1)
      question.value = content
      selectionChatError.value = requestErrorMessage(reason, '选区对话失败')
    }
  }
}

async function translateSelection() {
  const text = props.selection?.text
  if (!text || selectionTranslationLoading.value) return
  const targetLanguage = selectionTargetLanguage.value
  selectionTranslationLoading.value = true
  selectionTranslationError.value = ''
  try {
    const response = await translateTexts({
      texts: [text], sourceLanguage: detectTextLanguage(text), targetLanguage,
    })
    const item = response?.items?.[0]
    if (!item?.text) throw new Error('翻译服务未返回内容')
    if (props.selection?.text === text) selectionTranslation.value = { text: item.text, targetLanguage }
  } catch (reason) {
    if (props.selection?.text === text) selectionTranslationError.value = requestErrorMessage(reason, '翻译失败，请重试')
  } finally {
    selectionTranslationLoading.value = false
  }
}

async function ensureResearchSession() {
  if (activeResearchSessionId.value) return activeResearchSessionId.value
  if (sessionCreatePromise) return sessionCreatePromise
  sessionCreatePromise = createResearchSession({
    paperIds: [Number(props.paper.id)],
    primaryPaperId: Number(props.paper.id),
    title: props.paper.title || '论文精读',
    mode: WORKBENCH_MODES.SELECTION_QA,
    lastPage: 1,
    outputLanguage: 'ZH',
  }).then(session => {
    activeResearchSessionId.value = Number(session.id)
    selectionConversationId.value = freshSelectionConversationId(session.id)
    emit('research-session-change', Number(session.id))
    return Number(session.id)
  }).finally(() => { sessionCreatePromise = null })
  return sessionCreatePromise
}

async function restoreResearchMessages(sessionId) {
  try {
    const detail = await getResearchSession(sessionId)
    if (activeResearchSessionId.value !== sessionId) return
    const latestSelectionRun = (detail?.runs || []).find(item => (
      item?.plan?.workflow === WORKBENCH_MODES.SELECTION_QA
      && item?.invocation?.conversationId
    ))
    selectionConversationId.value = latestSelectionRun?.invocation?.conversationId
      || freshSelectionConversationId(sessionId)
    selectionMessages.value = (detail?.messages || []).map(message => ({
      id: message.messageKey || String(message.id),
      role: message.role === 'USER' ? 'user' : 'assistant',
      content: message.content || '',
      claims: message.evidence?.claims || [],
      evidence: message.evidence?.evidence || [],
      regionFallback: Boolean(message.evidence?.regionFallback),
    }))
    await scrollSelectionChat()
  } catch { /* A missing archive must not prevent PDF reading. */ }
}

function resetSelectionConversation() {
  selectionConversationId.value = activeResearchSessionId.value
    ? freshSelectionConversationId(activeResearchSessionId.value) : ''
  selectionMessages.value = []
  selectionChatError.value = ''
  question.value = ''
}

function freshSelectionConversationId(sessionId) {
  const id = positiveSessionId(sessionId)
  if (!id) return ''
  selectionConversationSequence += 1
  return `session-${id}-${Date.now().toString(36)}-${selectionConversationSequence}`.slice(0, 64)
}

function messageHtml(message) {
  return workbenchMarkdownToHtml(message?.content || '')
}

function messageEvidenceForClaim(message, claim) {
  const index = new Map((message?.evidence || []).map(item => [item.evidenceId, item]))
  return (claim?.evidenceIds || []).map(id => index.get(id)).filter(Boolean)
}

function jump(item) {
  if (item) emit('jump-evidence', item)
}

async function scrollSelectionChat() {
  await nextTick()
  const container = selectionChatMessages.value
  if (container) container.scrollTop = container.scrollHeight
}

function productTabForMode(mode) {
  if (mode === WORKBENCH_MODES.PAPER_COMPARISON) return 'comparison'
  if ([WORKBENCH_MODES.PAPER_IMPROVEMENT, WORKBENCH_MODES.RESEARCH_GAP].includes(mode)) return 'defect'
  return 'reading'
}

function positiveSessionId(value) {
  const id = Number(value)
  return Number.isInteger(id) && id > 0 ? id : null
}

function requestErrorMessage(reason, fallback) {
  return reason?.response?.data?.message || reason?.message || fallback
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
.product-tabs {
  position: sticky;
  z-index: 4;
  top: 0;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  padding: 0 12px;
  border-bottom: 1px solid var(--ra-border);
  background: var(--ra-panel-bg);
}
.product-tabs button {
  position: relative;
  min-width: 0;
  padding: 14px 4px 12px;
  border: 0;
  color: var(--ra-text-tertiary);
  background: transparent;
  cursor: pointer;
  font-size: 12px;
}
.product-tabs button::after {
  position: absolute;
  right: 18%;
  bottom: -1px;
  left: 18%;
  height: 2px;
  border-radius: 2px;
  background: transparent;
  content: '';
}
.product-tabs button.active { color: var(--ra-link); font-weight: 600; }
.product-tabs button.active::after { background: var(--ra-link); }
.product-tabs button:disabled { cursor: wait; opacity: .55; }
section { padding: 13px 14px; border-bottom: 1px solid var(--ra-border); }
.memory-status { display: flex; align-items: center; gap: 9px; padding-block: 9px; background: color-mix(in srgb, var(--ra-link) 5%, var(--ra-panel-bg)); }
.memory-orbit { position: relative; flex: 0 0 24px; width: 24px; height: 24px; border: 1px solid color-mix(in srgb, var(--ra-link) 28%, transparent); border-radius: 50%; animation: memory-orbit 1.4s linear infinite; }
.memory-orbit::before, .memory-orbit span { position: absolute; border-radius: 50%; background: var(--ra-link); content: ''; }
.memory-orbit::before { top: 1px; left: 9px; width: 5px; height: 5px; }
.memory-orbit span { top: 8px; left: 8px; width: 7px; height: 7px; opacity: .32; animation: memory-pulse 1.2s ease-in-out infinite; }
.memory-status__mark { display: grid; flex: 0 0 24px; width: 24px; height: 24px; border-radius: 50%; place-items: center; color: var(--ra-link); background: color-mix(in srgb, var(--ra-link) 10%, transparent); font-size: 12px; font-weight: 700; }
.memory-status.is-partial .memory-status__mark, .memory-status.is-failed .memory-status__mark { color: var(--el-color-warning); background: color-mix(in srgb, var(--el-color-warning) 12%, transparent); }
.memory-status__body { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 2px; }
.memory-status__body b { overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.memory-status__body small { color: var(--ra-text-tertiary); font-size: 9px; line-height: 1.35; }
.memory-progress { height: 2px; margin-top: 4px; overflow: hidden; border-radius: 2px; background: var(--ra-border); }
.memory-progress span { display: block; height: 100%; border-radius: inherit; background: var(--ra-link); transition: width .25s ease; }
.memory-status__action { flex: 0 0 auto; padding: 4px 7px; border: 1px solid color-mix(in srgb, var(--ra-link) 40%, var(--ra-border)); border-radius: 6px; color: var(--ra-link); background: var(--ra-panel-bg); cursor: pointer; font-size: 9px; }
.memory-status__action:disabled { cursor: wait; opacity: .55; }
@keyframes memory-orbit { to { transform: rotate(360deg); } }
@keyframes memory-pulse { 50% { opacity: .7; transform: scale(1.25); } }
@media (prefers-reduced-motion: reduce) {
  .memory-orbit, .memory-orbit span { animation: none; }
}
.capture-section { padding-bottom: 10px; }
.capture-switch {
  position: relative;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  padding: 3px;
  overflow: hidden;
  border: 1px solid var(--ra-border);
  border-radius: 9px;
  background: var(--ra-hover-bg);
}
.capture-switch__indicator {
  position: absolute;
  top: 3px;
  bottom: 3px;
  left: 3px;
  width: calc(50% - 3px);
  border: 1px solid color-mix(in srgb, var(--ra-link) 28%, var(--ra-border));
  border-radius: 6px;
  background: var(--ra-panel-bg);
  box-shadow: 0 1px 3px rgb(0 0 0 / 8%);
  transition: transform .18s ease;
}
.capture-switch.is-formula .capture-switch__indicator { transform: translateX(100%); }
.capture-switch button {
  z-index: 1;
  padding: 7px 5px;
  border: 0;
  color: var(--ra-text-tertiary);
  background: transparent;
  cursor: pointer;
  font-size: 11px;
}
.capture-switch button.active { color: var(--ra-link); font-weight: 600; }
.capture-hint { margin: 8px 2px 0; color: var(--ra-text-tertiary); font-size: 10px; line-height: 1.45; }
.content-stage { border-bottom: 1px solid var(--ra-border); }
.content-stage :deep(.formula-region-card) { border-bottom: 0; }
.section-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; margin-bottom: 9px; }
.section-heading > div { display: flex; flex-direction: column; gap: 2px; }
.section-heading b { font-size: 12px; }
.section-heading small { color: var(--ra-text-tertiary); font-size: 10px; }
.section-heading button { border: 0; color: var(--ra-text-secondary); background: transparent; cursor: pointer; font-size: 18px; }
.selection-card { border-bottom: 0; }
.selection-card__text {
  max-height: 120px;
  overflow: auto;
  margin: 0 0 9px;
  padding: 10px;
  border-left: 3px solid var(--ra-link);
  border-radius: 0 6px 6px 0;
  background: var(--ra-hover-bg);
  font-size: 12px;
  line-height: 1.55;
  white-space: normal;
}
.selection-tools { display: flex; align-items: center; justify-content: flex-end; gap: 7px; }
.selection-source-preview {
  margin: 0 0 9px;
  padding: 8px;
  border: 1px solid var(--ra-border);
  border-radius: 6px;
  background: #fff;
}
.selection-source-preview img {
  display: block;
  width: 100%;
  max-height: 220px;
  object-fit: contain;
  object-position: left center;
}
.selection-source-preview figcaption {
  margin-top: 6px;
  color: var(--ra-text-tertiary);
  font-size: 10px;
  line-height: 1.4;
}
.selection-translation { margin-top: 9px; padding: 9px; border-radius: 6px; background: color-mix(in srgb, var(--ra-link) 7%, var(--ra-panel-bg)); font-size: 11px; line-height: 1.55; white-space: pre-wrap; }
.selection-translation small { display: block; margin-bottom: 3px; color: var(--ra-text-tertiary); font-size: 9px; }
.content-confirm-hint { margin-top: 8px; color: var(--ra-text-tertiary); font-size: 10px; line-height: 1.4; }
.content-empty { display: grid; min-height: 132px; border-bottom: 0; place-items: center; align-content: center; text-align: center; }
.content-empty__icon { display: grid; width: 34px; height: 34px; margin-bottom: 8px; border-radius: 50%; place-items: center; color: var(--ra-link); background: color-mix(in srgb, var(--ra-link) 10%, transparent); font-size: 20px; }
.content-empty b { font-size: 12px; }
.content-empty p { max-width: 260px; margin: 5px 0 0; color: var(--ra-text-tertiary); font-size: 10px; line-height: 1.5; }
.selection-chat { display: flex; min-height: 360px; flex-direction: column; gap: 10px; border-bottom: 0; }
.selection-chat__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.selection-chat__heading > div { display: flex; flex-direction: column; gap: 2px; }
.selection-chat__heading b { font-size: 12px; }
.selection-chat__heading small { color: var(--ra-text-tertiary); font-size: 9px; line-height: 1.4; }
.selection-chat__heading > button { padding: 3px 5px; border: 0; color: var(--ra-link); background: transparent; cursor: pointer; font-size: 10px; }
.selection-chat__heading > button:disabled { cursor: wait; opacity: .5; }
.selection-chat__messages { display: flex; min-height: 150px; max-height: 420px; flex: 1; flex-direction: column; gap: 9px; overflow-y: auto; padding: 2px; }
.selection-chat__empty { display: grid; min-height: 140px; padding: 12px; border: 1px dashed var(--ra-border); border-radius: 9px; place-items: center; align-content: center; color: var(--ra-text-tertiary); text-align: center; }
.selection-chat__empty > span { margin-bottom: 6px; color: var(--ra-link); font-size: 20px; }
.selection-chat__empty b { color: var(--ra-text-secondary); font-size: 11px; }
.selection-chat__empty p { max-width: 260px; margin: 5px 0 0; font-size: 10px; line-height: 1.5; }
.chat-message { max-width: 92%; padding: 10px; border: 1px solid var(--ra-border); border-radius: 10px; background: var(--ra-panel-bg); }
.chat-message.is-user { align-self: flex-end; border-color: color-mix(in srgb, var(--ra-link) 30%, var(--ra-border)); background: color-mix(in srgb, var(--ra-link) 8%, var(--ra-panel-bg)); }
.chat-message.is-assistant { align-self: flex-start; }
.chat-message__role { margin-bottom: 4px; color: var(--ra-text-tertiary); font-size: 9px; }
.chat-message__text { font-size: 12px; line-height: 1.55; white-space: pre-wrap; }
.chat-message.is-pending { width: 82%; }
.chat-claim-list { display: flex; flex-direction: column; gap: 7px; margin: 9px 0 0; padding-left: 17px; }
.chat-claim-list li { font-size: 10px; line-height: 1.45; }
.evidence-links { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 5px; }
.evidence-links button { padding: 2px 6px; border: 1px solid color-mix(in srgb, var(--ra-link) 45%, var(--ra-border)); border-radius: 999px; color: var(--ra-link); background: transparent; font-size: 10px; cursor: pointer; }
.assistant-composer { padding: 8px; border: 1px solid var(--ra-border); border-radius: 10px; background: var(--ra-panel-bg); box-shadow: 0 4px 14px rgb(0 0 0 / 5%); }
.assistant-composer.disabled { background: var(--ra-hover-bg); }
.assistant-composer :deep(.el-textarea__inner) { padding: 4px; border: 0; background: transparent; box-shadow: none; }
.assistant-composer__footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 5px; }
.assistant-composer__footer span { color: var(--ra-text-tertiary); font-size: 9px; }
.trace-dots { display: grid; grid-template-columns: repeat(4, 1fr); align-items: center; margin: 2px 10px 4px; }
.trace-dot-item { position: relative; display: grid; min-width: 28px; height: 28px; place-items: center; }
.trace-dot-item:not(:last-child)::after { position: absolute; z-index: 0; top: 50%; left: calc(50% + 7px); width: calc(100% - 14px); height: 1px; background: var(--ra-border); content: ''; }
.step-dot { z-index: 1; width: 9px; height: 9px; box-sizing: border-box; border: 2px solid var(--ra-border); border-radius: 50%; background: var(--ra-panel-bg); }
.trace-dot-item.is-running .step-dot { border-color: var(--ra-link); background: var(--ra-link); animation: trace-pulse 1.2s ease-out infinite; }
.trace-dot-item.is-completed .step-dot { border-color: #4caf50; background: #4caf50; }
.trace-dot-item.is-failed .step-dot { border-color: var(--el-color-danger); background: var(--el-color-danger); }
@keyframes trace-pulse { 0% { box-shadow: 0 0 0 0 color-mix(in srgb, var(--ra-link) 30%, transparent); } 75%, 100% { box-shadow: 0 0 0 6px transparent; } }
.answer-text { overflow-wrap: anywhere; font-size: 12px; line-height: 1.65; }
.answer-text :deep(p) { margin: 5px 0; }
.answer-text :deep(ul), .answer-text :deep(ol) { margin: 5px 0; padding-left: 19px; }
.answer-text :deep(code) { padding: 1px 3px; border-radius: 3px; background: var(--ra-hover-bg); }
.muted-state, .error-state { padding-top: 8px; color: var(--ra-text-tertiary); font-size: 10px; line-height: 1.45; }
.error-state { color: var(--el-color-danger); }
.warning-state { margin-top: 8px; padding: 7px 8px; border-radius: 5px; color: #8a5a00; background: #fff7e6; font-size: 10px; line-height: 1.45; }
.math-rich-state { margin-top: 8px; padding: 7px 8px; border-radius: 5px; color: #245f73; background: #edf8fb; font-size: 10px; line-height: 1.45; }
.future-feature { display: grid; min-height: 420px; border-bottom: 0; place-items: center; align-content: center; text-align: center; }
.future-feature__badge { padding: 3px 8px; border-radius: 999px; color: var(--ra-link); background: color-mix(in srgb, var(--ra-link) 10%, transparent); font-size: 9px; }
.future-feature h3 { margin: 10px 0 5px; font-size: 15px; }
.future-feature p { max-width: 280px; margin: 0; color: var(--ra-text-tertiary); font-size: 11px; line-height: 1.6; }
@media (max-width: 1180px) { .paper-workbench { flex-basis: 320px; } }
</style>
