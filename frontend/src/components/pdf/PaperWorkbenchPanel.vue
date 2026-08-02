<template>
  <aside class="paper-workbench" aria-label="论文助手">
      <section class="assistant-context-header">
        <div>
          <b>论文助手</b>
          <small>以当前论文理解为基础的连续科研对话</small>
        </div>
        <button type="button" @click="$emit('add-comparison-paper')">＋ 添加对比文献</button>
      </section>

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
            <template v-if="memoryStatus.totalChunks === 1">全文理解</template>
            <template v-else>已处理 {{ memoryProcessedChunks }}/{{ memoryStatus.totalChunks }} 个分块</template>
            <template v-if="memoryStatus.failedChunks"> · {{ memoryStatus.failedChunks }} 个待重试</template>
          </small>
          <small v-else>理解完成后即可开始提问。</small>
          <small v-if="memoryTotalTokens">已消耗 {{ memoryTotalTokens.toLocaleString() }} Token</small>
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
        <div class="capture-switch" :class="{ 'is-formula': captureMode === 'formula' }" role="tablist" aria-label="论文内容选取方式">
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
          >公式精确框选</button>
        </div>
        <p class="capture-hint">
          {{ captureMode === 'formula'
            ? '直接选取不完整时可精确框选公式；点击固定内容后，系统会自动生成可编辑 LaTeX。'
            : '直接在 PDF 中拖动选择文字或公式，确认固定后作为当前对话焦点。' }}
        </p>
      </section>

      <div class="content-stage" aria-live="polite">
        <FormulaRegionCard
          v-if="formulaRegion"
          :region="formulaRegion"
          :recognition="formulaRecognition"
          :preview-data-url="formulaPreviewDataUrl"
          :loading="formulaLoading"
          :confirming="formulaConfirming"
          :error="formulaError"
          @clear="clearFormula"
          @retry="$emit('retry-formula')"
          @confirm="$emit('confirm-formula', $event)"
        />

        <section v-else-if="displayedSelection" class="selection-card">
          <div class="section-heading">
            <div>
              <b>选取内容</b>
              <small v-if="displayedSelectionAnchor?.page">第 {{ displayedSelectionAnchor.page }} 页</small>
            </div>
            <button type="button" class="selection-clear-action" aria-label="清除选取内容" @click="clearTextSelection">×</button>
          </div>
          <p class="selection-card__text">{{ displayedSelection.text }}</p>

          <div class="selection-tools">
            <el-button
              size="small"
              plain
              :loading="selectionTranslationLoading"
              @click="translateSelection"
            >翻译为{{ languageLabel(selectionTargetLanguage) }}</el-button>
            <el-button
              v-if="!fixedTextSelection"
              type="primary"
              size="small"
              :loading="displayedSelectionLoading"
              :disabled="!selectionAnchor || Boolean(displayedSelectionError)"
              @click="confirmTextSelection"
            >确认并固定</el-button>
            <template v-else>
              <el-tag size="small" type="success" effect="plain">已固定</el-tag>
              <el-button size="small" plain @click="clearTextSelection">重新选择</el-button>
            </template>
          </div>

          <div v-if="selectionTranslation" class="selection-translation">
            <small>译文 · {{ languageLabel(selectionTranslation.targetLanguage) }}</small>
            <div>{{ selectionTranslation.text }}</div>
          </div>
          <div v-if="selectionTranslationError" class="error-state">{{ selectionTranslationError }}</div>
          <div v-if="displayedSelectionLoading" class="muted-state">正在准备所选内容…</div>
          <div v-else-if="displayedSelectionError" class="error-state">{{ displayedSelectionError }}</div>
          <div v-else-if="selectionMappingIsRegion" class="warning-state" role="status">
            当前选区只能定位到页面区域，未建立可信的精确文本映射。可重新选择更清晰的文字；若继续提问，回答会明确要求回原页核对。
          </div>
          <div v-else-if="selectionIsMathRich" class="math-rich-state" role="status">
            已精确定位文字；检测到多个行内数学片段。提问时会同时提供 PDF 原文和本地 LaTeX 辅助，近似转写仍以原页排版为准。
          </div>
          <div v-else-if="!fixedTextSelection" class="content-confirm-hint">确认后才会作为当前对话焦点；继续拖选可调整范围。</div>
          <div v-else class="content-confirm-hint">固定内容会持续用于后续追问，直到你重新选择或点击 × 清除。</div>
        </section>

        <section v-else class="content-empty">
          <div class="content-empty__icon" aria-hidden="true">⌁</div>
          <b>{{ captureMode === 'formula' ? '框选一个公式' : '选择一段论文内容' }}</b>
          <p>{{ captureMode === 'formula' ? '框选预览和固定过程中生成的 LaTeX 会显示在这里。' : '原文会显示在这里，确认后固定为对话焦点。' }}</p>
        </section>
      </div>

      <section class="selection-chat" aria-label="论文对话">
        <div class="selection-chat__heading">
          <div>
            <b>论文对话</b>
            <small v-if="activeSelectionAnchor">
              当前焦点：第 {{ activeSelectionAnchor.page }} 页固定内容
            </small>
            <small v-else-if="canContinueSelectionConversation">
              当前未附加新选区；将沿用本对话历史与论文理解
            </small>
            <small v-else>基于论文理解开始对话；也可固定一段内容作为焦点</small>
          </div>
          <div class="selection-chat__actions">
            <button type="button" :disabled="running" @click="openConversationPicker">切换对话</button>
            <button type="button" :disabled="running" @click="startNewConversation">新对话</button>
          </div>
        </div>

        <section v-if="conversationPickerVisible" class="conversation-picker" role="dialog" aria-label="切换对话">
          <div class="conversation-picker__heading">
            <b>选择本篇论文的对话</b>
            <button type="button" aria-label="关闭对话列表" @click="conversationPickerVisible = false">×</button>
          </div>
          <button
            v-for="session in conversationSessions"
            :key="session.id"
            type="button"
            class="conversation-picker__item"
            :class="{ active: Number(session.id) === activeResearchSessionId }"
            :disabled="running"
            @click="switchConversation(session)"
          >
            <span>{{ session.title || '未命名对话' }}</span>
            <small>{{ session.messageCount || 0 }} 条消息 · {{ formatConversationTime(session.lastActivityAt) }}</small>
          </button>
          <div v-if="!conversationSessions.length" class="conversation-picker__empty">本篇论文还没有历史对话</div>
          <button type="button" class="conversation-picker__new" :disabled="running" @click="startNewConversation">＋ 开始新对话</button>
        </section>

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
            <ResearchMarkdown
              v-if="message.role === 'assistant'"
              class="answer-text"
              :content="citedAnswer(message)"
              @citation-click="jumpCitation(message, $event)"
            />
            <template v-else>
              <div class="chat-message__text">{{ message.content }}</div>
              <small v-if="message.selectionAnchor?.page" class="chat-message__context">
                引用第 {{ message.selectionAnchor.page }} 页选区
              </small>
              <small v-else class="chat-message__context">
                {{ message.contextInherited ? '沿用对话上下文' : '基于论文理解' }}
              </small>
            </template>
            <details v-if="message.claims?.length" class="chat-claim-list">
              <summary>查看依据（{{ message.claims.length }}）</summary>
              <ol>
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
            </details>
          </article>
          <div v-if="running" class="chat-message is-assistant is-pending">
            <div class="chat-message__role">论文助手</div>
            <div class="answer-progress" role="status">
              <span aria-hidden="true" />
              正在基于论文证据生成回答…
            </div>
          </div>
        </div>

        <div class="assistant-composer" :class="{ disabled: !memoryReady }">
          <el-input
            v-model="question"
            class="assistant-composer__input"
            type="textarea"
            :rows="3"
            maxlength="4000"
            resize="none"
            :disabled="!memoryReady"
            :placeholder="!memoryReady
              ? '论文理解完成后即可提问'
              : (activeSelectionAnchor
                ? '向论文助手提问…'
                : (canContinueSelectionConversation
                  ? '继续当前对话，或附加新选区后提问…'
                  : '基于论文理解开始提问，也可先选择内容…'))"
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
  listResearchSessions,
} from '@/api/researchArchive.js'
import FormulaRegionCard from '@/components/pdf/FormulaRegionCard.vue'
import ResearchMarkdown from '@/components/ResearchMarkdown.vue'
import { buildCitedAnswer } from '@/utils/answerCitations.js'
import { usePaperWorkbench } from '@/composables/usePaperWorkbench.js'
import {
  detectTextLanguage,
  languageLabel,
  oppositeLanguage,
} from '@/utils/translation.js'
import {
  buildWorkbenchPlanRequest,
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
  formulaPreviewDataUrl: { type: String, default: '' },
  formulaLoading: { type: Boolean, default: false },
  formulaConfirming: { type: Boolean, default: false },
  formulaError: { type: String, default: '' },
  captureMode: { type: String, default: 'text' },
  researchSessionId: { type: Number, default: null },
})

const emit = defineEmits([
  'clear-selection', 'clear-formula', 'retry-formula', 'confirm-formula',
  'capture-mode-change', 'jump-evidence', 'research-session-change',
  'add-comparison-paper',
])

const { running, error, run, loadRecent } = usePaperWorkbench()
const question = ref('')
const fixedTextSelection = ref(null)
const selectionTranslation = ref(null)
const selectionTranslationLoading = ref(false)
const selectionTranslationError = ref('')
const selectionMessages = ref([])
const selectionConversationId = ref('')
const selectionChatError = ref('')
const selectionChatMessages = ref(null)
const activeResearchSessionId = ref(positiveSessionId(props.researchSessionId))
const conversationSessions = ref([])
const conversationPickerVisible = ref(false)
let sessionCreatePromise = null
let selectionMessageSequence = 0
let selectionConversationSequence = 0
let memoryPollTimer = null

const memoryStatus = ref(null)
const memoryStarting = ref(false)
const memoryActive = computed(() => memoryStatus.value?.status === 'UNDERSTANDING')
const memoryReady = computed(() => (
  memoryStatus.value?.status === 'READY' && Boolean(memoryStatus.value?.profileReady ?? true)
))
const memoryTotalTokens = computed(() => (
  Number(memoryStatus.value?.promptTokens || 0) + Number(memoryStatus.value?.completionTokens || 0)
))
const memoryProcessedChunks = computed(() => (
  Number(memoryStatus.value?.completedChunks || 0) + Number(memoryStatus.value?.failedChunks || 0)
))
const showMemoryStatus = computed(() => Boolean(
  memoryStatus.value && memoryStatus.value.status !== 'READY',
))

const displayedSelection = computed(() => fixedTextSelection.value?.selection || props.selection)
const displayedSelectionAnchor = computed(() => fixedTextSelection.value?.anchor || props.selectionAnchor)
const displayedSelectionLoading = computed(() => !fixedTextSelection.value && props.selectionLoading)
const displayedSelectionError = computed(() => fixedTextSelection.value ? '' : props.selectionError)
const selectionTargetLanguage = computed(() => oppositeLanguage(detectTextLanguage(displayedSelection.value?.text)))
const selectionMappingIsRegion = computed(() => (
  displayedSelectionAnchor.value?.mappingStatus
    ? displayedSelectionAnchor.value.mappingStatus === 'REGION'
    : displayedSelectionAnchor.value?.kind === 'REGION'
))
const selectionIsMathRich = computed(() => (
  displayedSelectionAnchor.value?.contentType === 'MATH_RICH_TEXT'
))
const confirmedFormula = computed(() => (
  props.formulaRegion && props.formulaRecognition?.confirmed && props.formulaRecognition?.anchor
    ? props.formulaRecognition : null
))
const activeSelectionAnchor = computed(() => (
  fixedTextSelection.value?.anchor || confirmedFormula.value?.anchor || null
))
const canContinueSelectionConversation = computed(() => Boolean(
  selectionConversationId.value
  && selectionMessages.value.length
))
const selectionChatDisabled = computed(() => (
  running.value || !memoryReady.value || !question.value.trim()
))

watch(() => displayedSelection.value?.text, () => {
  selectionTranslation.value = null
  selectionTranslationError.value = ''
})
watch(() => props.researchSessionId, nextId => {
  const normalized = positiveSessionId(nextId)
  if (normalized === activeResearchSessionId.value) return
  activeResearchSessionId.value = normalized
  if (normalized) void restoreResearchMessages(normalized)
  else {
    selectionConversationId.value = ''
    selectionMessages.value = []
  }
})
watch(() => props.paper.id, () => {
  fixedTextSelection.value = null
  activeResearchSessionId.value = positiveSessionId(props.researchSessionId)
  selectionConversationId.value = ''
  selectionMessages.value = []
  conversationSessions.value = []
  conversationPickerVisible.value = false
  void loadMemoryStatus()
  void loadConversationSessions(!activeResearchSessionId.value)
})

onMounted(async () => {
  await loadMemoryStatus()
  try { await loadRecent(props.paper.id) } catch { /* History is optional. */ }
  await loadConversationSessions(!activeResearchSessionId.value)
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

function selectCaptureMode(mode) {
  if (!['text', 'formula'].includes(mode) || mode === props.captureMode) return
  emit('capture-mode-change', mode)
}

function clearTextSelection() {
  fixedTextSelection.value = null
  emit('clear-selection')
}

function confirmTextSelection() {
  if (!props.selection || !props.selectionAnchor || props.selectionError) return
  fixedTextSelection.value = {
    selection: props.selection,
    anchor: props.selectionAnchor,
  }
}

function clearFormula() {
  emit('clear-formula')
}

async function sendSelectionMessage() {
  const content = question.value.trim()
  const anchor = activeSelectionAnchor.value
  if (!content || running.value || !memoryReady.value) return
  const contextInherited = !anchor && selectionMessages.value.length > 0

  let sessionId
  try { sessionId = await ensureResearchSession(content) }
  catch (reason) {
    selectionChatError.value = requestErrorMessage(reason, '研究档案创建失败')
    return
  }
  if (!selectionConversationId.value) {
    selectionConversationId.value = freshSelectionConversationId(sessionId)
  }
  const conversationId = selectionConversationId.value
  const userMessage = {
    id: `user-${++selectionMessageSequence}`,
    role: 'user',
    content,
    selectionAnchor: anchor,
    contextInherited,
  }
  selectionMessages.value.push(userMessage)
  selectionChatError.value = ''
  question.value = ''
  await scrollSelectionChat()

  try {
    const request = buildWorkbenchPlanRequest({
      paperId: props.paper.id,
      question: content,
      selectionAnchor: anchor,
      conversationId,
    })
    const completed = await run(request)
    if (selectionConversationId.value !== conversationId) return
    selectionMessages.value.push({
      id: completed.runId || `assistant-${++selectionMessageSequence}`,
      role: 'assistant',
      content: completed.result?.answer || '',
      claims: completed.result?.claims || [],
      answerBlocks: completed.result?.answerBlocks || [],
      evidence: completed.result?.evidence || [],
      regionFallback: Boolean(completed.result?.regionFallback),
    })
    try {
      await appendResearchMessages(sessionId, [
        {
          messageKey: `${completed.runId}:user`, role: 'USER', content,
          runId: completed.runId, selectionAnchor: anchor,
          evidence: { contextInherited, conversationId },
        },
        {
          messageKey: `${completed.runId}:assistant`, role: 'ASSISTANT',
          content: completed.result?.answer || '', runId: completed.runId,
          evidence: {
            claims: completed.result?.claims || [],
            answerBlocks: completed.result?.answerBlocks || [],
            evidence: completed.result?.evidence || [],
            regionFallback: Boolean(completed.result?.regionFallback),
            conversationId,
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
  const text = displayedSelection.value?.text
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
    if (displayedSelection.value?.text === text) selectionTranslation.value = { text: item.text, targetLanguage }
  } catch (reason) {
    if (displayedSelection.value?.text === text) selectionTranslationError.value = requestErrorMessage(reason, '翻译失败，请重试')
  } finally {
    selectionTranslationLoading.value = false
  }
}

async function ensureResearchSession(firstQuestion = '') {
  if (activeResearchSessionId.value) return activeResearchSessionId.value
  if (sessionCreatePromise) return sessionCreatePromise
  sessionCreatePromise = createResearchSession({
    paperIds: [Number(props.paper.id)],
    primaryPaperId: Number(props.paper.id),
    title: firstQuestion.slice(0, 120) || props.paper.title || '论文对话',
    mode: WORKBENCH_MODES.SELECTION_QA,
    lastPage: 1,
    outputLanguage: 'ZH',
  }).then(session => {
    activeResearchSessionId.value = Number(session.id)
    selectionConversationId.value = freshSelectionConversationId(session.id)
    emit('research-session-change', Number(session.id))
    void loadConversationSessions(false)
    return Number(session.id)
  }).finally(() => { sessionCreatePromise = null })
  return sessionCreatePromise
}

async function restoreResearchMessages(sessionId) {
  try {
    const detail = await getResearchSession(sessionId)
    if (activeResearchSessionId.value !== sessionId) return
    if (detail?.session && !sessionBelongsToCurrentPaper(detail.session)) {
      startNewConversation()
      conversationPickerVisible.value = conversationSessions.value.length > 0
      return
    }
    const latestSelectionRun = (detail?.runs || []).find(item => (
      item?.plan?.workflow === WORKBENCH_MODES.SELECTION_QA
      && item?.invocation?.conversationId
    ))
    selectionConversationId.value = latestSelectionRun?.invocation?.conversationId
      || freshSelectionConversationId(sessionId)
    const conversationByRunId = new Map((detail?.runs || [])
      .filter(item => item?.runId && item?.invocation?.conversationId)
      .map(item => [item.runId, item.invocation.conversationId]))
    selectionMessages.value = (detail?.messages || [])
      .filter(message => (
        (message.evidence?.conversationId || conversationByRunId.get(message.runId))
          === selectionConversationId.value
      ))
      .map(message => ({
      id: message.messageKey || String(message.id),
      role: message.role === 'USER' ? 'user' : 'assistant',
      content: message.content || '',
      claims: message.evidence?.claims || [],
      answerBlocks: message.evidence?.answerBlocks || [],
      evidence: message.evidence?.evidence || [],
      regionFallback: Boolean(message.evidence?.regionFallback),
      selectionAnchor: message.selectionAnchor || null,
      contextInherited: Boolean(message.evidence?.contextInherited),
    }))
    await scrollSelectionChat()
  } catch { /* A missing archive must not prevent PDF reading. */ }
}

async function loadConversationSessions(showWhenAvailable = false) {
  try {
    const sessions = await listResearchSessions({ archived: false, limit: 200 })
    conversationSessions.value = (sessions || []).filter(sessionBelongsToCurrentPaper)
    if (showWhenAvailable && conversationSessions.value.length) conversationPickerVisible.value = true
  } catch { /* Conversation switching is optional while PDF reading remains available. */ }
}

function sessionBelongsToCurrentPaper(session) {
  const paperId = Number(props.paper.id)
  return Number(session?.primaryPaperId) === paperId
    || (session?.papers || []).some(paper => Number(paper.id) === paperId)
}

function openConversationPicker() {
  conversationPickerVisible.value = true
  void loadConversationSessions(false)
}

async function switchConversation(session) {
  if (running.value) return
  const sessionId = positiveSessionId(session?.id)
  if (!sessionId || sessionId === activeResearchSessionId.value) {
    conversationPickerVisible.value = false
    return
  }
  activeResearchSessionId.value = sessionId
  selectionConversationId.value = ''
  selectionMessages.value = []
  selectionChatError.value = ''
  question.value = ''
  conversationPickerVisible.value = false
  emit('research-session-change', sessionId)
  await restoreResearchMessages(sessionId)
}

function startNewConversation() {
  if (running.value) return
  activeResearchSessionId.value = null
  selectionConversationId.value = ''
  selectionMessages.value = []
  selectionChatError.value = ''
  question.value = ''
  conversationPickerVisible.value = false
  emit('research-session-change', null)
}

function formatConversationTime(value) {
  if (!value) return '暂无记录'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function freshSelectionConversationId(sessionId) {
  const id = positiveSessionId(sessionId)
  if (!id) return ''
  selectionConversationSequence += 1
  return `session-${id}-${Date.now().toString(36)}-${selectionConversationSequence}`.slice(0, 64)
}

function messageEvidenceForClaim(message, claim) {
  const index = new Map((message?.evidence || []).map(item => [item.evidenceId, item]))
  return (claim?.evidenceIds || []).map(id => index.get(id)).filter(Boolean)
}

function citedAnswer(message) {
  return buildCitedAnswer(message.content, message.claims, message.evidence, message.answerBlocks)
}

function jumpCitation(message, citationTarget) {
  const parts = String(citationTarget || '').split('~')
  const evidenceId = parts[0]
  const item = message.evidence?.find(candidate => candidate.evidenceId === evidenceId)
  if (!item) return
  const blockIndex = Number(parts[1])
  const citationIndex = Number(parts[2])
  const citation = Number.isInteger(blockIndex) && Number.isInteger(citationIndex)
    ? message.answerBlocks?.[blockIndex]?.citations?.[citationIndex]
    : null
  const targetText = citation?.evidenceId === evidenceId ? citation.quote : ''
  jump(targetText ? {
    ...item,
    locator: { ...(item.locator || {}), targetText },
  } : item)
}

function jump(item) {
  if (item) emit('jump-evidence', item)
}

async function scrollSelectionChat() {
  await nextTick()
  const container = selectionChatMessages.value
  if (container) container.scrollTop = container.scrollHeight
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
  margin-top: calc(-1 * var(--pdf-toolbar-height));
  border-top: 1px solid var(--ra-border);
}
section { padding: 13px 14px; border-bottom: 1px solid var(--ra-border); }
.assistant-context-header {
  position: sticky;
  z-index: 4;
  top: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding-block: 10px;
  background: var(--ra-panel-bg);
}
.assistant-context-header > div { display: flex; min-width: 0; flex-direction: column; gap: 2px; }
.assistant-context-header b { font-size: 13px; }
.assistant-context-header small { overflow: hidden; color: var(--ra-text-tertiary); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.assistant-context-header button {
  flex: 0 0 auto;
  padding: 4px 7px;
  border: 1px solid var(--ra-border);
  border-radius: 5px;
  color: var(--ra-link);
  background: transparent;
  cursor: pointer;
  font-size: 9px;
}
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
.selection-chat__actions { display: flex !important; flex-direction: row !important; gap: 2px !important; }
.selection-chat__actions button { padding: 3px 5px; border: 0; color: var(--ra-link); background: transparent; cursor: pointer; font-size: 10px; white-space: nowrap; }
.selection-chat__actions button:disabled { cursor: wait; opacity: .5; }
.conversation-picker { display: flex; max-height: 240px; flex-direction: column; gap: 5px; overflow-y: auto; padding: 8px; border: 1px solid var(--ra-border); border-radius: 8px; background: color-mix(in srgb, var(--ra-link) 4%, var(--ra-panel-bg)); }
.conversation-picker__heading { display: flex; align-items: center; justify-content: space-between; padding: 2px 3px 5px; }
.conversation-picker__heading b { font-size: 11px; }
.conversation-picker__heading button { border: 0; color: var(--ra-text-tertiary); background: transparent; cursor: pointer; font-size: 15px; }
.conversation-picker__item { display: flex; flex-direction: column; gap: 3px; padding: 8px; border: 1px solid var(--ra-border); border-radius: 6px; color: var(--ra-text); background: var(--ra-panel-bg); text-align: left; cursor: pointer; }
.conversation-picker__item:hover, .conversation-picker__item.active { border-color: var(--ra-link); background: color-mix(in srgb, var(--ra-link) 7%, var(--ra-panel-bg)); }
.conversation-picker__item:disabled, .conversation-picker__new:disabled { cursor: wait; opacity: .5; }
.conversation-picker__item span { overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.conversation-picker__item small, .conversation-picker__empty { color: var(--ra-text-tertiary); font-size: 9px; }
.conversation-picker__empty { padding: 12px 4px; text-align: center; }
.conversation-picker__new { padding: 7px; border: 1px dashed color-mix(in srgb, var(--ra-link) 55%, var(--ra-border)); border-radius: 6px; color: var(--ra-link); background: transparent; cursor: pointer; font-size: 10px; }
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
.chat-message__context { display: block; margin-top: 5px; color: var(--ra-text-tertiary); font-size: 9px; }
.chat-message.is-pending { width: 82%; }
.chat-claim-list { margin: 9px 0 0; font-size: 10px; }
.chat-claim-list summary { color: var(--ra-link); cursor: pointer; }
.chat-claim-list ol { display: flex; flex-direction: column; gap: 7px; margin: 7px 0 0; padding-left: 17px; }
.chat-claim-list li { font-size: 10px; line-height: 1.45; }
.evidence-links { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 5px; }
.evidence-links button { padding: 2px 6px; border: 1px solid color-mix(in srgb, var(--ra-link) 45%, var(--ra-border)); border-radius: 999px; color: var(--ra-link); background: transparent; font-size: 10px; cursor: pointer; }
.assistant-composer { padding: 8px; border: 1px solid var(--ra-border); border-radius: 10px; background: var(--ra-panel-bg); box-shadow: 0 4px 14px rgb(0 0 0 / 5%); }
.assistant-composer.disabled { background: var(--ra-hover-bg); }
.assistant-composer :deep(.el-textarea__inner) { padding: 4px; border: 0; background: transparent; box-shadow: none; }
.assistant-composer__footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 5px; }
.assistant-composer__footer span { color: var(--ra-text-tertiary); font-size: 9px; }
.answer-progress { display: flex; align-items: center; gap: 7px; color: var(--ra-text-secondary); font-size: 10px; }
.answer-progress > span { width: 8px; height: 8px; border-radius: 50%; background: var(--ra-link); animation: trace-pulse 1.2s ease-out infinite; }
@keyframes trace-pulse { 0% { box-shadow: 0 0 0 0 color-mix(in srgb, var(--ra-link) 30%, transparent); } 75%, 100% { box-shadow: 0 0 0 6px transparent; } }
.answer-text { overflow-wrap: anywhere; font-size: 12px; line-height: 1.65; }
.muted-state, .error-state { padding-top: 8px; color: var(--ra-text-tertiary); font-size: 10px; line-height: 1.45; }
.error-state { color: var(--el-color-danger); }
.warning-state { margin-top: 8px; padding: 7px 8px; border-radius: 5px; color: #8a5a00; background: #fff7e6; font-size: 10px; line-height: 1.45; }
.math-rich-state { margin-top: 8px; padding: 7px 8px; border-radius: 5px; color: #245f73; background: #edf8fb; font-size: 10px; line-height: 1.45; }
@media (max-width: 1180px) { .paper-workbench { flex-basis: 320px; } }
</style>
