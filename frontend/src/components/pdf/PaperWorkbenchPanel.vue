<template>
  <aside class="paper-workbench" aria-label="论文助手">
      <section class="assistant-context-header">
        <div class="assistant-context-header__title">
          <b>论文助手</b>
          <small>以当前论文理解为基础的连续科研对话</small>
        </div>
        <div class="assistant-context-header__actions">
          <div class="capture-switch capture-switch--compact" :class="{ 'is-formula': captureMode === 'formula' }" role="tablist" aria-label="论文内容选取方式">
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
          <button type="button" class="comparison-paper-action" @click="$emit('add-comparison-paper')">＋ 添加对比文献</button>
        </div>
      </section>

      <section
        v-if="showMemoryStatus"
        class="memory-status"
        :class="`is-${String(memoryStatus?.status || '').toLowerCase()}`"
        aria-live="polite"
      >
        <div v-if="memoryActive" class="memory-orbit" aria-hidden="true"><span /></div>
        <div v-else class="memory-status__mark" aria-hidden="true">{{ memoryStatus?.status === 'RETRY_REQUIRED' ? '!' : '✦' }}</div>
        <div class="memory-status__body">
          <b>{{ memoryStatus?.statusText || '正在准备论文…' }}</b>
          <small v-if="!memoryReady">理解完成后即可开始提问。</small>
        </div>
        <button
          v-if="memoryCanStart"
          type="button"
          class="memory-status__action"
          :disabled="memoryStarting"
          @click="startMemoryUnderstanding"
        >{{ memoryStarting ? '启动中…' : (memoryStatus?.status === 'RETRY_REQUIRED' ? '重试' : '开始理解') }}</button>
      </section>

      <div
        v-if="confirmedContentPresent && !contentStageExpanded"
        class="content-stage-collapsed"
        aria-live="polite"
      >
        <span>{{ collapsedContentLabel }}</span>
        <button type="button" @click="contentStageExpanded = true">重新展开</button>
      </div>

      <div
        v-else
        class="content-stage"
        :class="{ 'is-formula': formulaRegion }"
        aria-live="polite"
      >
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
          @confirm="handleFormulaConfirm"
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
            >附加到下一条消息</el-button>
            <template v-else>
              <el-tag size="small" type="success" effect="plain">待发送</el-tag>
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
          <div v-else-if="!fixedTextSelection" class="content-confirm-hint">确认后只附加到下一条消息；继续拖选可调整范围。</div>
          <div v-else class="content-confirm-hint">该内容只用于下一条消息，发送后自动移除；对话历史会继续保留。</div>
        </section>

        <section v-else class="content-empty">
          <b>{{ captureMode === 'formula' ? '框选一个公式' : '选择一段论文内容' }}</b>
          <p>{{ captureMode === 'formula' ? '框选预览和固定过程中生成的 LaTeX 会显示在这里。' : '原文会显示在这里，确认后附加到下一条消息。' }}</p>
        </section>
      </div>

      <section class="selection-chat" aria-label="论文对话">
        <div class="selection-chat__heading">
          <div>
            <b>论文对话</b>
            <small v-if="activeSelectionAnchor">
              下一条消息已附加：第 {{ activeSelectionAnchor.page }} 页内容
            </small>
            <small v-else-if="canContinueSelectionConversation">
              当前未附加新内容；Agent 会结合本对话历史判断是否需要查阅论文
            </small>
            <small v-else>可直接提问，也可附加论文选区、公式或文件</small>
          </div>
          <div class="selection-chat__actions">
            <button type="button" :disabled="running" @click="openConversationPicker">切换对话</button>
            <button type="button" :disabled="running" @click="startNewConversation">新对话</button>
          </div>
        </div>

        <section v-if="conversationPickerVisible" class="conversation-picker" role="dialog" aria-label="切换对话">
          <div class="conversation-picker__heading">
            <b>选择对话</b>
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

        <div
          ref="selectionChatMessages"
          class="selection-chat__messages"
          aria-live="polite"
          @scroll="updateChatScrollState"
        >
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
              <ResearchMarkdown class="chat-message__text" :content="message.content" />
              <div v-if="message.attachments?.length" class="chat-message__attachments">
                <span v-for="attachment in message.attachments" :key="attachment.name">
                  {{ attachment.mimeType === 'application/x-latex' ? 'x²' : '📎' }} {{ attachment.name }}
                </span>
              </div>
              <small v-if="message.selectionAnchor?.page" class="chat-message__context">
                引用第 {{ message.selectionAnchor.page }} 页选区
              </small>
              <small v-else-if="contextModeLabel(message)" class="chat-message__context">
                {{ contextModeLabel(message) }}
              </small>
            </template>
            <EvidenceSourceList
              :sources="messageCitationSources(message)"
              @jump="jump"
            />
          </article>
          <div v-if="running" class="chat-message is-assistant is-pending">
            <div class="chat-message__role">论文助手</div>
            <div class="answer-progress" role="status">
              <span aria-hidden="true" />
              {{ progress?.label || '思考中…' }}
            </div>
          </div>
        </div>

        <div class="scroll-to-latest-anchor">
          <button
            v-if="showScrollToLatest"
            type="button"
            class="scroll-to-latest"
            title="跳转到最新消息"
            aria-label="跳转到最新消息"
            @click="scrollSelectionChat"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
          </button>
        </div>

        <div class="assistant-composer" :class="{ disabled: !memoryReady }">
          <div v-if="pendingAttachments.length || pendingFormulas.length" class="assistant-composer__attachments">
            <span v-for="(attachment, index) in pendingAttachments" :key="`${attachment.name}-${index}`">
              <span class="assistant-composer__attachment-name">{{ attachment.name }}</span>
              <small v-if="attachment.truncated">已截取</small>
              <button type="button" :aria-label="`移除附件 ${attachment.name}`" @click="removeAttachment(index)">×</button>
            </span>
            <span v-for="(latex, index) in pendingFormulas" :key="`formula-${index}`" class="is-formula">
              <button type="button" class="assistant-composer__formula-name" @click="editLatexFormula(index)">x²&nbsp; 公式{{ index + 1 }}</button>
              <button type="button" :aria-label="`移除公式 ${index + 1}`" @click="removeLatexFormula(index)">×</button>
            </span>
          </div>
          <div v-if="latexEditorVisible" class="assistant-composer__latex" role="dialog" aria-label="输入 LaTeX 公式">
            <textarea
              ref="latexInputRef"
              v-model="latexDraft"
              maxlength="2000"
              rows="3"
              placeholder="输入 LaTeX，例如：\frac{a}{b}"
              @keydown.ctrl.enter.prevent="insertLatex"
            />
            <div>
              <small>{{ editingFormulaIndex == null ? '将作为独立公式附件发送' : `正在编辑公式 ${editingFormulaIndex + 1}` }}</small>
              <button type="button" @click="closeLatexEditor">取消</button>
              <button type="button" class="is-primary" :disabled="!latexDraft.trim()" @click="insertLatex">{{ editingFormulaIndex == null ? '添加公式' : '保存修改' }}</button>
            </div>
          </div>
          <el-input
            v-model="question"
            class="assistant-composer__input"
            type="textarea"
            :rows="3"
            maxlength="2000"
            resize="none"
            :disabled="!memoryReady"
            :placeholder="!memoryReady
              ? '论文理解完成后即可提问'
              : (activeSelectionAnchor
                ? '针对已附加内容提问…'
                : (canContinueSelectionConversation
                  ? '继续当前对话，或附加新选区后提问…'
                  : '可以询问论文，也可以像普通对话一样提问…'))"
            @keydown.enter="handleComposerEnter"
          />
          <div class="assistant-composer__footer">
            <div class="assistant-composer__tools">
              <input
                ref="attachmentInputRef"
                class="assistant-composer__file-input"
                type="file"
                multiple
                :accept="CHAT_ATTACHMENT_ACCEPT"
                @change="handleAttachmentFiles"
              />
              <button
                type="button"
                title="添加附件（PDF、Word、JPG、PNG）"
                aria-label="添加附件"
                :disabled="!memoryReady || preparingAttachment || pendingContextCount >= MAX_CHAT_ATTACHMENTS"
                @click="openAttachmentPicker"
              >
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8.5 12.8 14.9 6.4a3.3 3.3 0 0 1 4.7 4.7l-8.1 8.1a5 5 0 0 1-7.1-7.1l8.3-8.3" /></svg>
              </button>
              <button
                type="button"
                title="输入 LaTeX"
                aria-label="输入 LaTeX"
                :disabled="!memoryReady || (pendingContextCount >= MAX_CHAT_ATTACHMENTS && editingFormulaIndex == null)"
                @click="toggleLatexEditor"
              >x<sup>2</sup></button>
              <span>Shift+Enter 换行</span>
            </div>
            <button
              type="button"
              class="assistant-composer__send"
              :disabled="running ? cancelling : selectionChatDisabled"
              :aria-label="running ? (cancelling ? '正在停止' : '停止回答') : '发送'"
              :title="running ? (cancelling ? '正在停止' : '停止回答') : '发送'"
              @click="running ? cancelAnswer() : sendSelectionMessage()"
            >
              <svg v-if="!running" viewBox="0 0 24 24" aria-hidden="true"><path d="m4 5 16 7-16 7 3-7-3-7Zm3 7h13" /></svg>
              <svg v-else viewBox="0 0 24 24" aria-hidden="true"><rect x="6" y="6" width="12" height="12" rx="2" /></svg>
              <span class="visually-hidden">{{ running ? (cancelling ? '正在停止' : '停止回答') : '发送' }}</span>
            </button>
          </div>
        </div>
        <div v-if="selectionChatError" class="error-state">{{ selectionChatError }}</div>
      </section>
  </aside>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getPaperMemoryStatus, startPaperUnderstanding } from '@/api/paperMemory.js'
import { translateTexts } from '@/api/workbench.js'
import {
  createResearchSession,
  getResearchSession,
  listResearchSessions,
} from '@/api/researchArchive.js'
import FormulaRegionCard from '@/components/pdf/FormulaRegionCard.vue'
import ResearchMarkdown from '@/components/ResearchMarkdown.vue'
import EvidenceSourceList from '@/components/EvidenceSourceList.vue'
import { buildCitationSources, buildCitedAnswer } from '@/utils/answerCitations.js'
import { mapResearchMessageView } from '@/utils/researchMessageView.js'
import {
  CHAT_ATTACHMENT_ACCEPT,
  MAX_CHAT_ATTACHMENTS,
  MAX_CHAT_ATTACHMENTS_BYTES,
  prepareChatAttachment,
} from '@/utils/chatAttachments.js'
import { usePaperAgent } from '@/composables/usePaperAgent.js'
import {
  detectTextLanguage,
  languageLabel,
  oppositeLanguage,
} from '@/utils/translation.js'
import {
  buildAgentTurnRequest,
  PAPER_AGENT_MODE,
} from '@/utils/agentTurn.js'

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
  'add-comparison-paper', 'execute-actions',
])

const { running, progress: agentProgress, run, watchRun, cancelRun } = usePaperAgent()
const progress = agentProgress || ref({ status: '', phase: 'IDLE', label: '' })
const cancelling = ref(false)
const question = ref('')
const pendingAttachments = ref([])
const pendingFormulas = ref([])
const preparingAttachment = ref(false)
const attachmentInputRef = ref(null)
const latexEditorVisible = ref(false)
const latexDraft = ref('')
const latexInputRef = ref(null)
const editingFormulaIndex = ref(null)
const fixedTextSelection = ref(null)
const contentStageExpanded = ref(true)
const collapseAfterFormulaConfirm = ref(false)
const selectionTranslation = ref(null)
const selectionTranslationLoading = ref(false)
const selectionTranslationError = ref('')
const selectionMessages = ref([])
const selectionConversationId = ref('')
const selectionChatError = ref('')
const selectionChatMessages = ref(null)
const selectionChatAtBottom = ref(true)
const selectionChatScrollable = ref(false)
const activeResearchSessionId = ref(positiveSessionId(props.researchSessionId))
const conversationSessions = ref([])
const conversationPickerVisible = ref(false)
let sessionCreatePromise = null
let selectionMessageSequence = 0
let selectionConversationSequence = 0
let memoryPollTimer = null
const waitingRunId = ref(null)
const recoveredRunIds = new Set()
const conversationErrors = new Map()

const memoryStatus = ref({
  status: 'LOADING',
  statusText: '正在读取论文理解状态',
  conversationReady: false,
})
const memoryStarting = ref(false)
const memoryActive = computed(() => memoryStatus.value?.status === 'UNDERSTANDING')
const memoryReady = computed(() => Boolean(memoryStatus.value?.conversationReady))
const memoryCanStart = computed(() => ['NOT_STARTED', 'SOURCE_READY', 'RETRY_REQUIRED', 'UNAVAILABLE'].includes(memoryStatus.value?.status))
const showMemoryStatus = computed(() => true)

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
const confirmedContentPresent = computed(() => Boolean(
  fixedTextSelection.value || confirmedFormula.value
))
const collapsedContentLabel = computed(() => {
  const page = activeSelectionAnchor.value?.page
  const contentType = confirmedFormula.value ? '公式内容' : '选取内容'
  return page ? `已固定第 ${page} 页${contentType}` : `已固定${contentType}`
})
const canContinueSelectionConversation = computed(() => Boolean(
  selectionConversationId.value
  && selectionMessages.value.length
))
const selectionChatDisabled = computed(() => (
  running.value || !memoryReady.value
    || (!question.value.trim() && !pendingAttachments.value.length && !pendingFormulas.value.length)
))
const pendingContextCount = computed(() => (
  pendingAttachments.value.length + pendingFormulas.value.length
))
const showScrollToLatest = computed(() => (
  selectionChatScrollable.value && !selectionChatAtBottom.value
))

watch(() => displayedSelection.value?.text, () => {
  selectionTranslation.value = null
  selectionTranslationError.value = ''
})
watch(() => props.selectionAnchor, value => {
  if (value && !fixedTextSelection.value) contentStageExpanded.value = true
})
watch(() => props.formulaRegion, value => {
  if (value && !props.formulaRecognition?.confirmed) contentStageExpanded.value = true
})
watch(() => props.formulaConfirming, (confirming, wasConfirming) => {
  if (!wasConfirming || confirming || !collapseAfterFormulaConfirm.value) return
  if (!props.formulaError && confirmedFormula.value) contentStageExpanded.value = false
  collapseAfterFormulaConfirm.value = false
})
watch(() => props.researchSessionId, nextId => {
  const normalized = positiveSessionId(nextId)
  if (normalized === activeResearchSessionId.value) return
  activeResearchSessionId.value = normalized
  selectionChatError.value = normalized ? (conversationErrors.get(normalized) || '') : ''
  if (normalized) void restoreResearchMessages(normalized)
  else {
    selectionConversationId.value = ''
    selectionMessages.value = []
  }
})
watch(() => props.paper.id, () => {
  fixedTextSelection.value = null
  contentStageExpanded.value = true
  collapseAfterFormulaConfirm.value = false
  clearComposerExtras()
  activeResearchSessionId.value = positiveSessionId(props.researchSessionId)
  selectionConversationId.value = ''
  selectionMessages.value = []
  conversationSessions.value = []
  conversationErrors.clear()
  selectionChatError.value = ''
  conversationPickerVisible.value = false
  void loadMemoryStatus()
  void initializeConversation()
})

onMounted(async () => {
  await loadMemoryStatus()
  await initializeConversation()
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
  } catch {
    if (Number(props.paper.id) !== paperId) return
    memoryStatus.value = {
      status: 'UNAVAILABLE',
      statusText: '论文理解尚未启动',
      conversationReady: false,
    }
  }
}

async function startMemoryUnderstanding() {
  if (memoryStarting.value || !memoryCanStart.value) return
  memoryStarting.value = true
  try {
    await startPaperUnderstanding(
      props.paper.id,
      `paper-memory-ui:${props.paper.id}:${Date.now()}`,
    )
    memoryStatus.value = {
      ...memoryStatus.value,
      status: 'UNDERSTANDING',
      statusText: '论文理解任务已提交',
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
  contentStageExpanded.value = true
  emit('clear-selection')
}

function confirmTextSelection() {
  if (!props.selection || !props.selectionAnchor || props.selectionError) return
  fixedTextSelection.value = {
    selection: props.selection,
    anchor: props.selectionAnchor,
  }
  contentStageExpanded.value = false
}

function clearFormula() {
  contentStageExpanded.value = true
  collapseAfterFormulaConfirm.value = false
  emit('clear-formula')
}

function handleFormulaConfirm(formulas) {
  collapseAfterFormulaConfirm.value = true
  emit('confirm-formula', formulas)
}

function handleComposerEnter(event) {
  if (event?.isComposing || event?.shiftKey) return
  event?.preventDefault()
  void sendSelectionMessage()
}

function openAttachmentPicker() {
  if (!memoryReady.value || preparingAttachment.value || pendingContextCount.value >= MAX_CHAT_ATTACHMENTS) return
  attachmentInputRef.value?.click()
}

async function handleAttachmentFiles(event) {
  const input = event?.target
  const remaining = Math.max(0, MAX_CHAT_ATTACHMENTS - pendingContextCount.value)
  const files = Array.from(input?.files || []).slice(0, remaining)
  if (!files.length) return
  preparingAttachment.value = true
  try {
    for (const file of files) {
      try {
        const attachment = await prepareChatAttachment(file)
        const totalBytes = [...pendingAttachments.value, attachment]
          .reduce((sum, item) => sum + Number(item.size || 0), 0)
        if (totalBytes > MAX_CHAT_ATTACHMENTS_BYTES) throw new Error('附件总大小不能超过 10 MB')
        pendingAttachments.value.push(attachment)
      } catch (reason) {
        ElMessage.warning(requestErrorMessage(reason, `附件「${file.name}」读取失败`))
      }
    }
    if (Number(input?.files?.length || 0) > remaining) ElMessage.info('每条消息最多添加 2 个附件')
  } finally {
    preparingAttachment.value = false
    if (input) input.value = ''
  }
}

function removeAttachment(index) {
  pendingAttachments.value.splice(index, 1)
}

async function toggleLatexEditor() {
  if (latexEditorVisible.value && editingFormulaIndex.value == null) {
    closeLatexEditor()
    return
  }
  if (pendingContextCount.value >= MAX_CHAT_ATTACHMENTS) return
  editingFormulaIndex.value = null
  latexDraft.value = ''
  latexEditorVisible.value = true
  await focusLatexEditor()
}

function insertLatex() {
  const latex = latexDraft.value.trim()
  if (!latex) return
  const index = editingFormulaIndex.value
  if (Number.isInteger(index) && index >= 0 && index < pendingFormulas.value.length) {
    pendingFormulas.value[index] = latex
  } else if (pendingContextCount.value < MAX_CHAT_ATTACHMENTS) {
    pendingFormulas.value.push(latex)
  } else {
    ElMessage.info('每条消息最多添加 2 项附件或公式')
    return
  }
  closeLatexEditor()
}

async function editLatexFormula(index) {
  if (!Number.isInteger(index) || index < 0 || index >= pendingFormulas.value.length) return
  editingFormulaIndex.value = index
  latexDraft.value = pendingFormulas.value[index]
  latexEditorVisible.value = true
  await focusLatexEditor()
}

function removeLatexFormula(index) {
  if (!Number.isInteger(index) || index < 0 || index >= pendingFormulas.value.length) return
  pendingFormulas.value.splice(index, 1)
  if (editingFormulaIndex.value === index) closeLatexEditor()
  else if (editingFormulaIndex.value > index) editingFormulaIndex.value -= 1
}

function closeLatexEditor() {
  latexDraft.value = ''
  editingFormulaIndex.value = null
  latexEditorVisible.value = false
}

async function focusLatexEditor() {
  await nextTick()
  latexInputRef.value?.focus()
}

function formulaAttachments(formulas) {
  return (formulas || []).map((latex, index) => ({
    name: `公式${index + 1}`,
    mimeType: 'application/x-latex',
    content: latex,
    truncated: false,
  }))
}

function attachmentViews(attachments) {
  return (attachments || []).map(item => ({
    name: item.name,
    mimeType: item.mimeType,
    truncated: Boolean(item.truncated),
  }))
}

function clearComposerExtras() {
  pendingAttachments.value = []
  pendingFormulas.value = []
  closeLatexEditor()
}

async function sendSelectionMessage() {
  const fileAttachments = pendingAttachments.value.map(item => ({ ...item }))
  const formulas = [...pendingFormulas.value]
  const attachments = [...fileAttachments, ...formulaAttachments(formulas)]
  const content = question.value.trim() || (attachments.length ? '请分析所附附件。' : '')
  const anchor = activeSelectionAnchor.value
  if (!content || running.value || !memoryReady.value) return
  const selectedText = String(anchor?.targetText || anchor?.text || '')
  const selectionLimit = String(anchor?.contentType || '').toUpperCase().includes('FORMULA') ? 2000 : 4000
  if (selectedText.length > selectionLimit) {
    selectionChatError.value = '选取内容过长'
    return
  }
  let contextInherited = false

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
    contextMode: anchor ? 'SELECTION' : '',
    attachments: attachmentViews(attachments),
  }
  selectionMessages.value.push(userMessage)
  selectionChatError.value = ''
  conversationErrors.delete(sessionId)
  question.value = ''
  pendingAttachments.value = []
  pendingFormulas.value = []
  closeLatexEditor()
  await scrollSelectionChat()

  try {
    const request = buildAgentTurnRequest({
      paperId: props.paper.id,
      userMessage: content,
      selectionAnchor: anchor,
      attachments,
    })
    request.researchSessionId = sessionId
    if (waitingRunId.value) request.resumeRunId = waitingRunId.value
    const completed = await run(request, {
      onAccepted: accepted => {
        userMessage.runId = accepted?.runId || null
        detachSubmittedSelection(anchor)
      },
      onActionRequired: actions => {
        // Page actions are dispatched while the run is WAITING_CLIENT.  Do not
        // wait for the final answer (or emit them a second time after receipt).
        emit('execute-actions', actions.map(action => ({
          ...action, runId: userMessage.runId, evidence: null,
        })))
      },
    })
    if (selectionConversationId.value !== conversationId) return
    contextInherited = Boolean(completed.result?.contextInherited)
    waitingRunId.value = completed.status === 'WAITING_USER' ? completed.runId : null
    userMessage.contextInherited = contextInherited
    userMessage.contextMode = completed.result?.contextMode || ''
    selectionMessages.value.push({
      id: completed.runId || `assistant-${++selectionMessageSequence}`,
      runId: completed.runId || null,
      role: 'assistant',
      content: completed.result?.answer || '',
      claims: completed.result?.claims || [],
      answerBlocks: completed.result?.answerBlocks || [],
      evidence: completed.result?.evidence || [],
      regionFallback: Boolean(completed.result?.regionFallback),
      actions: completed.result?.actions || [],
    })
    await scrollSelectionChat()
  } catch (reason) {
    if (reason?.message !== 'aborted' && selectionConversationId.value === conversationId) {
      const index = selectionMessages.value.findIndex(item => item.id === userMessage.id)
      if (index >= 0) selectionMessages.value.splice(index, 1)
      question.value = content
      pendingAttachments.value = fileAttachments
      pendingFormulas.value = formulas
      setConversationError(sessionId, requestErrorMessage(reason, '选区对话失败'))
    }
  }
}

async function cancelAnswer() {
  if (!running.value || cancelling.value || typeof cancelRun !== 'function') return
  cancelling.value = true
  try {
    await cancelRun()
  } catch (reason) {
    if (activeResearchSessionId.value) {
      setConversationError(activeResearchSessionId.value, requestErrorMessage(reason, '取消回答失败'))
    }
  } finally {
    cancelling.value = false
  }
}

function detachSubmittedSelection(anchor) {
  if (!anchor) return
  if (fixedTextSelection.value?.anchor === anchor) {
    fixedTextSelection.value = null
    emit('clear-selection')
  }
  if (confirmedFormula.value?.anchor === anchor) emit('clear-formula')
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
    mode: PAPER_AGENT_MODE,
    lastPage: 1,
    outputLanguage: 'ZH',
  }).then(session => {
    activeResearchSessionId.value = Number(session.id)
    selectionConversationId.value = freshSelectionConversationId(session.id)
    emit('research-session-change', Number(session.id))
    void loadConversationSessions()
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
    selectionConversationId.value = freshSelectionConversationId(sessionId)
    selectionMessages.value = (detail?.messages || []).map(mapResearchMessageView)
    await scrollSelectionChat()
    const unfinishedRunId = findUnfinishedRunId(selectionMessages.value)
    if (unfinishedRunId) void resumePersistedRun(unfinishedRunId, sessionId)
  } catch { /* A missing archive must not prevent PDF reading. */ }
}

function findUnfinishedRunId(messages) {
  const latest = messages.at(-1)
  return latest?.role === 'user' && latest.runId ? latest.runId : null
}

async function resumePersistedRun(runId, sessionId) {
  if (running.value || activeResearchSessionId.value !== sessionId || recoveredRunIds.has(runId)) return
  recoveredRunIds.add(runId)
  try {
    const completed = await watchRun(runId, {
      onActionRequired: actions => {
        emit('execute-actions', actions.map(action => ({ ...action, runId, evidence: null })))
      },
    })
    if (activeResearchSessionId.value !== sessionId
        || selectionMessages.value.some(message => message.role === 'assistant' && message.runId === runId)) return
    waitingRunId.value = completed.status === 'WAITING_USER' ? completed.runId : null
    selectionMessages.value.push({
      id: completed.runId,
      runId: completed.runId,
      role: 'assistant',
      content: completed.result?.answer || '',
      claims: completed.result?.claims || [],
      answerBlocks: completed.result?.answerBlocks || [],
      evidence: completed.result?.evidence || [],
      regionFallback: Boolean(completed.result?.regionFallback),
      actions: completed.result?.actions || [],
    })
    await scrollSelectionChat()
  } catch (reason) {
    if (reason?.message === 'aborted' || !reason?.agentTerminal) recoveredRunIds.delete(runId)
    if (reason?.message !== 'aborted' && activeResearchSessionId.value === sessionId) {
      setConversationError(sessionId, requestErrorMessage(reason, '后台回答失败'))
    }
  }
}

function contextModeLabel(message) {
  return {
    SELECTION: '基于本轮选区',
    FOLLOW_UP: '继续上一问题',
    PAPER_QUERY: '基于论文全文',
    GENERAL_CHAT: '普通对话',
    ACTION_EXPLICIT: '执行论文操作',
    ACTION_REFERENTIAL: '沿用上一目标执行',
  }[message?.contextMode] || (message?.contextInherited ? '继续上一问题' : '')
}

async function loadConversationSessions() {
  try {
    const response = await listResearchSessions({ archived: false, page: 1, size: 200 })
    const sessions = Array.isArray(response) ? response : response?.records || []
    conversationSessions.value = sessions.filter(sessionBelongsToCurrentPaper)
      .sort((left, right) => sessionActivity(right) - sessionActivity(left) || Number(right.id) - Number(left.id))
  } catch { /* Conversation switching is optional while PDF reading remains available. */ }
}

async function initializeConversation() {
  await loadConversationSessions()
  if (positiveSessionId(activeResearchSessionId.value)) {
    conversationPickerVisible.value = false
    await restoreResearchMessages(activeResearchSessionId.value)
    return
  }
  const latest = conversationSessions.value[0]
  if (latest) {
    const sessionId = positiveSessionId(latest.id)
    activeResearchSessionId.value = sessionId
    conversationPickerVisible.value = false
    emit('research-session-change', sessionId)
    await restoreResearchMessages(sessionId)
    return
  }
  startNewConversation()
}

function sessionActivity(session) {
  const value = Date.parse(session?.lastActivityAt || session?.updatedAt || session?.createdAt || '')
  return Number.isFinite(value) ? value : 0
}

function sessionBelongsToCurrentPaper(session) {
  const paperId = Number(props.paper.id)
  return Number(session?.primaryPaperId) === paperId
    || (session?.papers || []).some(paper => Number(paper.id) === paperId)
}

function openConversationPicker() {
  conversationPickerVisible.value = true
  void loadConversationSessions()
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
  selectionChatError.value = conversationErrors.get(sessionId) || ''
  waitingRunId.value = null
  question.value = ''
  clearComposerExtras()
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
  waitingRunId.value = null
  question.value = ''
  clearComposerExtras()
  conversationPickerVisible.value = false
  emit('research-session-change', null)
}

function setConversationError(sessionId, message) {
  const id = positiveSessionId(sessionId)
  if (!id) return
  conversationErrors.set(id, message)
  if (activeResearchSessionId.value === id) selectionChatError.value = message
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

function citedAnswer(message) {
  return buildCitedAnswer(message.content, message.claims, message.evidence, message.answerBlocks)
}

function jumpCitation(message, citationTarget) {
  const sources = messageCitationSources(message)
  const sourceMatch = /^source~(\d+)$/.exec(String(citationTarget || ''))
  if (sourceMatch) {
    jump(sources.find(source => source.number === Number(sourceMatch[1]))?.target)
    return
  }
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

function messageCitationSources(message) {
  return buildCitationSources(message?.claims, message?.evidence, message?.answerBlocks)
}

function jump(item) {
  if (item) emit('jump-evidence', item)
}

async function scrollSelectionChat() {
  await nextTick()
  const container = selectionChatMessages.value
  if (!container) return
  container.scrollTop = container.scrollHeight
  updateChatScrollState()
}

function updateChatScrollState() {
  const container = selectionChatMessages.value
  if (!container) return
  selectionChatScrollable.value = container.scrollHeight > container.clientHeight + 2
  selectionChatAtBottom.value = (
    container.scrollHeight - container.scrollTop - container.clientHeight <= 24
  )
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
  min-height: 0;
  height: calc(100% + var(--pdf-toolbar-height));
  margin-top: calc(-1 * var(--pdf-toolbar-height));
  overflow: hidden;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  background: var(--ra-panel-bg);
  color: var(--ra-text);
  border-top: 1px solid var(--ra-border-light);
}
section { padding: 14px 16px; border-bottom: 1px solid var(--ra-border-light); }
.assistant-context-header {
  position: relative;
  flex: 0 0 auto;
  z-index: 4;
  top: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 48px;
  box-sizing: border-box;
  padding-block: 9px;
  background: var(--ra-panel-bg);
}
.assistant-context-header__title { display: flex; min-width: 54px; flex: 1 1 auto; overflow: hidden; flex-direction: column; gap: 2px; }
.assistant-context-header b { font-size: 14px; letter-spacing:-.15px; }
.assistant-context-header small { overflow: hidden; color: var(--ra-text-tertiary); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.assistant-context-header__actions { display: flex; min-width: 0; flex: 0 0 auto; align-items: center; gap: 7px; }
.assistant-context-header .comparison-paper-action {
  flex: 0 0 auto;
  padding: 4px 7px;
  border: 1px solid var(--ra-border);
  border-radius: 8px;
  color: var(--ra-link);
  background: transparent;
  cursor: pointer;
  font-size: 9px;
  white-space: nowrap;
}
.memory-status { display: flex; flex: 0 0 auto; align-items: center; gap: 9px; padding-block: 9px; background: color-mix(in srgb, var(--ra-link) 5%, var(--ra-panel-bg)); }
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
.capture-switch--compact { flex: 0 0 124px; width: 124px; padding: 2px; border-radius: 8px; }
.capture-switch--compact .capture-switch__indicator { top: 2px; bottom: 2px; left: 2px; width: calc(50% - 2px); border-radius: 5px; }
.capture-switch--compact button { padding: 5px 2px; font-size: 9px; white-space: nowrap; }
.content-stage { flex: 0 1 auto; max-height: min(46%, 430px); overflow-y: auto; border-bottom: 1px solid var(--ra-border); }
.content-stage.is-formula { max-height: min(34%, 300px); }
.content-stage :deep(.formula-region-card) { border-bottom: 0; }
.content-stage-collapsed { display: flex; min-height: 40px; box-sizing: border-box; flex: 0 0 auto; align-items: center; justify-content: space-between; gap: 10px; padding: 7px 16px; border-bottom: 1px solid var(--ra-border); color: var(--ra-text-secondary); background: color-mix(in srgb, var(--ra-link) 4%, var(--ra-panel-bg)); font-size: 10px; }
.content-stage-collapsed span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.content-stage-collapsed button { flex: 0 0 auto; padding: 3px 7px; border: 1px solid var(--ra-border); border-radius: 6px; color: var(--ra-link); background: var(--ra-panel-bg); cursor: pointer; font-size: 9px; }
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
.content-empty { display: flex; min-height: 58px; box-sizing: border-box; border-bottom: 0; flex-direction: column; justify-content: center; gap: 3px; text-align: left; }
.content-empty b { color: var(--ra-text); font-size: 12px; }
.content-empty p { max-width: none; margin: 0; color: var(--ra-text-tertiary); font-size: 10px; line-height: 1.4; }
.selection-chat { display: flex; min-height: 0; flex: 1 1 0; overflow: hidden; flex-direction: column; gap: 0; border-bottom: 0; }
.selection-chat__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; padding: 0 2px 9px; border-bottom: 1px solid var(--ra-border-light); }
.selection-chat__heading > div { display: flex; flex-direction: column; gap: 2px; }
.selection-chat__heading b { font-size: 12px; }
.selection-chat__heading small { color: var(--ra-text-tertiary); font-size: 9px; line-height: 1.4; }
.selection-chat__actions { display: flex !important; flex-direction: row !important; gap: 2px !important; }
.selection-chat__actions button { padding: 3px 5px; border: 0; color: var(--ra-link); background: transparent; cursor: pointer; font-size: 10px; white-space: nowrap; }
.selection-chat__actions button:disabled { cursor: wait; opacity: .5; }
.conversation-picker { display: flex; max-height: 240px; margin-top: 5px; flex-direction: column; gap: 5px; overflow-y: auto; padding: 5px 8px 8px; border: 1px solid var(--ra-border); border-radius: 8px; background: color-mix(in srgb, var(--ra-link) 4%, var(--ra-panel-bg)); }
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
.selection-chat__messages { display: flex; min-height: 0; max-height: none; flex: 1 1 0; flex-direction: column; gap: 10px; overflow-y: auto; padding: 9px 2px 2px; }
.scroll-to-latest-anchor { position: relative; z-index: 3; height: 0; flex: 0 0 0; }
.scroll-to-latest { position: absolute; bottom: 7px; left: 50%; display: grid; width: 28px; height: 28px; padding: 0; border: 1px solid var(--ra-border); border-radius: 50%; place-items: center; color: var(--ra-text-secondary); background: var(--ra-panel-bg); box-shadow: 0 4px 12px rgb(0 0 0 / 13%); cursor: pointer; transform: translateX(-50%); }
.scroll-to-latest:hover { color: var(--ra-link); border-color: color-mix(in srgb, var(--ra-link) 45%, var(--ra-border)); }
.scroll-to-latest svg { width: 16px; height: 16px; fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
.selection-chat__empty { display: grid; min-height: 0; height: 100%; padding: 12px; box-sizing: border-box; border: 1px dashed var(--ra-border); border-radius: 9px; place-items: center; align-content: center; color: var(--ra-text-tertiary); text-align: center; }
.selection-chat__empty > span { margin-bottom: 6px; color: var(--ra-link); font-size: 20px; }
.selection-chat__empty b { color: var(--ra-text); font-size: 12px; }
.selection-chat__empty p { max-width: 260px; margin: 5px 0 0; font-size: 10px; line-height: 1.5; }
.chat-message { max-width: 92%; padding: 11px 12px; border: 1px solid var(--ra-border-light); border-radius: 12px; background: var(--ra-panel-bg); }
.chat-message.is-user { align-self: flex-end; border-color: color-mix(in srgb, var(--ra-link) 30%, var(--ra-border)); background: color-mix(in srgb, var(--ra-link) 8%, var(--ra-panel-bg)); }
.chat-message.is-assistant { align-self: flex-start; }
.chat-message__role { margin-bottom: 4px; color: var(--ra-text-tertiary); font-size: 9px; }
.chat-message__text { font-size: 12px; line-height: 1.55; white-space: pre-wrap; }
.chat-message__context { display: block; margin-top: 5px; color: var(--ra-text-tertiary); font-size: 9px; }
.chat-message__attachments { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 7px; }
.chat-message__attachments span { max-width: 100%; overflow: hidden; padding: 3px 7px; border-radius: 6px; color: var(--ra-text-secondary); background: var(--ra-hover-bg); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.chat-message.is-pending { width: 82%; }
.chat-claim-list { margin: 9px 0 0; font-size: 10px; }
.chat-claim-list summary { color: var(--ra-link); cursor: pointer; }
.chat-claim-list ol { display: flex; flex-direction: column; gap: 7px; margin: 7px 0 0; padding-left: 17px; }
.chat-claim-list li { font-size: 10px; line-height: 1.45; }
.evidence-source__excerpt { display: block; color: var(--ra-text-secondary); }
.evidence-source__formula { display: block; color: var(--ra-text-secondary); }
.evidence-source__legacy { display: block; margin-top: 3px; color: var(--ra-text-tertiary); font-size: 10px; }
.evidence-source__full { margin-top: 4px; color: var(--ra-text-secondary); }
.evidence-source__full summary { cursor: pointer; color: var(--ra-link); font-size: 10px; }
.evidence-source__full pre { max-height: 360px; overflow: auto; margin: 4px 0 0; white-space: pre-wrap; word-break: break-word; }
.evidence-source__jump { margin-top: 4px; padding: 2px 6px; border: 1px solid color-mix(in srgb, var(--ra-link) 45%, var(--ra-border)); border-radius: 999px; color: var(--ra-link); background: transparent; font-size: 10px; cursor: pointer; }
.assistant-composer { flex: 0 0 auto; padding: 9px; border: 1px solid var(--ra-border); border-radius: 12px; background: var(--ra-panel-bg); box-shadow: 0 5px 18px rgb(0 0 0 / 5%); }
.assistant-composer.disabled { background: var(--ra-hover-bg); }
.assistant-composer :deep(.el-textarea__inner) { padding: 4px; border: 0; background: transparent; box-shadow: none; }
.assistant-composer__attachments { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 7px; }
.assistant-composer__attachments > span { display: flex; min-width: 0; max-width: 100%; align-items: center; gap: 5px; padding: 4px 6px 4px 8px; border: 1px solid var(--ra-border-light); border-radius: 7px; color: var(--ra-text-secondary); background: var(--ra-hover-bg); font-size: 9px; }
.assistant-composer__attachment-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.assistant-composer__attachments > span.is-formula { border-color: color-mix(in srgb, var(--ra-link) 35%, var(--ra-border-light)); background: color-mix(in srgb, var(--ra-link) 7%, var(--ra-panel-bg)); }
.assistant-composer__attachments small { flex: 0 0 auto; color: var(--ra-text-tertiary); }
.assistant-composer__attachments button { flex: 0 0 auto; padding: 0 2px; border: 0; color: var(--ra-text-tertiary); background: transparent; cursor: pointer; }
.assistant-composer__attachments .assistant-composer__formula-name { min-width: 0; overflow: hidden; padding: 0; color: var(--ra-link); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.assistant-composer__latex { margin-bottom: 7px; padding: 7px; border: 1px solid var(--ra-border); border-radius: 8px; background: var(--ra-surface-muted); }
.assistant-composer__latex textarea { display: block; width: 100%; min-height: 62px; resize: vertical; box-sizing: border-box; padding: 7px 8px; border: 1px solid var(--ra-border-light); border-radius: 6px; outline: none; color: var(--ra-text); background: var(--ra-panel-bg); font: 11px/1.5 Consolas, monospace; }
.assistant-composer__latex textarea:focus { border-color: var(--ra-link); }
.assistant-composer__latex > div { display: flex; align-items: center; justify-content: flex-end; gap: 7px; margin-top: 6px; }
.assistant-composer__latex small { margin-right: auto; color: var(--ra-text-tertiary); font-size: 9px; }
.assistant-composer__latex button { padding: 3px 7px; border: 1px solid var(--ra-border); border-radius: 5px; color: var(--ra-text-secondary); background: transparent; font-size: 9px; cursor: pointer; }
.assistant-composer__latex button.is-primary { border-color: var(--ra-link); color: white; background: var(--ra-link); }
.assistant-composer__latex button:disabled { opacity: .45; cursor: default; }
.assistant-composer__footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 5px; }
.assistant-composer__tools { display: flex; min-width: 0; align-items: center; gap: 3px; }
.assistant-composer__tools > span { margin-left: 4px; color: var(--ra-text-tertiary); font-size: 9px; white-space: nowrap; }
.assistant-composer__tools > button { display: inline-flex; width: 28px; height: 28px; padding: 0; align-items: center; justify-content: center; border: 0; border-radius: 6px; color: var(--ra-text-secondary); background: transparent; font: 15px/1 Georgia, serif; cursor: pointer; }
.assistant-composer__tools > button:hover:not(:disabled) { color: var(--ra-link); background: var(--ra-hover-bg); }
.assistant-composer__tools > button:disabled { opacity: .4; cursor: default; }
.assistant-composer__tools sup { margin-top: -7px; font-size: 8px; }
.assistant-composer__tools svg { width: 19px; height: 19px; fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.assistant-composer__file-input { display: none; }
.assistant-composer__send { display: inline-flex; width: 34px; height: 34px; flex: 0 0 auto; align-items: center; justify-content: center; border: 0; border-radius: 8px; color: white; background: var(--ra-link); cursor: pointer; }
.assistant-composer__send:hover:not(:disabled) { filter: brightness(1.06); }
.assistant-composer__send:disabled { opacity: .42; cursor: default; }
.assistant-composer__send svg { width: 21px; height: 21px; fill: currentColor; stroke: white; stroke-width: 1.7; stroke-linecap: round; stroke-linejoin: round; }
.visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); clip-path: inset(50%); white-space: nowrap; }
.answer-progress { display: flex; align-items: center; gap: 7px; color: var(--ra-text-secondary); font-size: 10px; }
.answer-progress > span { width: 8px; height: 8px; border-radius: 50%; background: var(--ra-link); animation: trace-pulse 1.2s ease-out infinite; }
@keyframes trace-pulse { 0% { box-shadow: 0 0 0 0 color-mix(in srgb, var(--ra-link) 30%, transparent); } 75%, 100% { box-shadow: 0 0 0 6px transparent; } }
.answer-text { overflow-wrap: anywhere; font-size: 12px; line-height: 1.65; }
.muted-state, .error-state { padding-top: 8px; color: var(--ra-text-tertiary); font-size: 10px; line-height: 1.45; }
.error-state { color: var(--el-color-danger); }
.warning-state { margin-top: 8px; padding: 7px 8px; border-radius: 5px; color: var(--el-color-warning); background: color-mix(in srgb, var(--el-color-warning) 12%, var(--ra-panel-bg)); font-size: 10px; line-height: 1.45; }
.math-rich-state { margin-top: 8px; padding: 7px 8px; border-radius: 5px; color: var(--ra-link); background: color-mix(in srgb, var(--ra-link) 9%, var(--ra-panel-bg)); font-size: 10px; line-height: 1.45; }
@media (max-width: 1180px) { .paper-workbench { min-width:240px; } }
</style>
