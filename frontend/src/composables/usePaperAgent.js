import { onUnmounted, ref } from 'vue'
import { cancelAgentRun, executeAgentTurn, getAgentRun, uploadAgentAttachment } from '@/api/agent.js'
import { mapAgentEvidenceList } from '@/utils/evidenceViewModel.js'
import { MAX_CHAT_ATTACHMENTS, MAX_CHAT_ATTACHMENTS_BYTES } from '@/utils/chatAttachments.js'
import {
  MAX_AGENT_MESSAGE_CHARACTERS,
  MAX_FORMULA_SELECTION_CHARACTERS,
  MAX_TEXT_SELECTION_CHARACTERS,
} from '@/utils/agentTurn.js'

export { unionBoundingBoxes } from '@/utils/evidenceViewModel.js'

export function usePaperAgent() {
  const running = ref(false)
  const error = ref('')
  const progress = ref({ status: '', phase: 'IDLE', label: '' })
  let controller = null
  let activeRunId = null

  function stopWatching() {
    controller?.abort()
    controller = null
  }

  async function getRun(runId, signal) {
    return getAgentRun(runId, signal ? { signal } : undefined)
  }

  async function watchRun(runId, { onProgress, onActionRequired } = {}) {
    stopWatching()
    controller = new AbortController()
    const signal = controller.signal
    activeRunId = runId
    running.value = true
    error.value = ''
    progress.value = { status: 'RUNNING', phase: 'THINKING', label: '思考中…' }
    const dispatchedActions = new Set()
    let finalStatus = null
    try {
      for (let attempt = 0; attempt < 360; attempt += 1) {
        const current = await getRun(runId, signal)
        const view = toViewModel(current)
        const currentProgress = {
          status: current?.status || '',
          phase: current?.phase || inferPhase(current),
          label: current?.progressLabel || inferProgressLabel(current),
        }
        progress.value = currentProgress
        if (typeof onProgress === 'function') onProgress(currentProgress)
        if (current?.status === 'WAITING_CLIENT') {
          const actions = view.result?.actions || []
          const actionKey = actions.map(action => `${action.toolCallId || ''}:${action.ticket || ''}`).join('|')
          if (actions.length && actionKey && !dispatchedActions.has(actionKey)) {
            dispatchedActions.add(actionKey)
            try { await onActionRequired?.(actions, view) } catch (reason) {
              if (reason?.message !== 'aborted') error.value = reason?.message || '页面操作执行失败'
            }
          }
          await waitForPoll(signal)
          continue
        }
        if (['COMPLETED', 'WAITING_USER', 'CANCELLED'].includes(current?.status)) {
          finalStatus = current.status
          return view
        }
        if (current?.status === 'FAILED') {
          finalStatus = current.status
          const terminalError = new Error(current?.message || '论文助手执行失败')
          terminalError.agentTerminal = true
          terminalError.agentRunStatus = current.status
          throw terminalError
        }
        await waitForPoll(signal)
      }
      throw new Error('论文助手运行超时')
    } catch (reason) {
      if (reason?.message !== 'aborted') error.value = reason?.message || '论文助手执行失败'
      throw reason
    } finally {
      if (controller?.signal === signal) controller = null
      if (activeRunId === runId
          && ['COMPLETED', 'FAILED', 'CANCELLED', 'WAITING_USER'].includes(finalStatus)) {
        activeRunId = null
      }
      running.value = false
      if (finalStatus) progress.value = {
        status: finalStatus,
        phase: finalStatus === 'CANCELLED' ? 'CANCELLED' : 'IDLE',
        label: finalStatus === 'CANCELLED' ? '已停止' : '',
      }
    }
  }

  async function run(input, { onAccepted, onProgress, onActionRequired } = {}) {
    stopWatching()
    error.value = ''
    running.value = true
    try {
      const response = await executeAgentTurn(await toApiInput(input))
      const result = toViewModel(response)
      activeRunId = result.runId
      if (typeof onAccepted === 'function') onAccepted(result)
      return ['QUEUED', 'RUNNING', 'WAITING_CLIENT'].includes(result.status)
        ? await watchRun(result.runId, { onProgress, onActionRequired })
        : result
    } catch (reason) {
      if (reason?.message !== 'aborted') {
        error.value = reason?.response?.data?.message || reason?.message || '论文助手启动失败'
      }
      throw reason
    } finally {
      running.value = false
    }
  }

  async function cancelRun() {
    const runId = activeRunId
    if (!runId) return null
    const response = await cancelAgentRun(runId)
    return toViewModel(response)
  }

  onUnmounted(stopWatching)
  return { running, error, progress, run, watchRun, cancelRun, stopWatching }
}

function waitForPoll(signal) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, 1000)
    signal.addEventListener('abort', () => {
      clearTimeout(timer)
      reject(new Error('aborted'))
    }, { once: true })
  })
}

async function toApiInput(request) {
  const paperId = Number(request.paperId)
  const sessionId = Number(request.researchSessionId)
  if (!Number.isInteger(sessionId) || sessionId <= 0) throw new Error('研究对话尚未创建')
  const attachments = request.attachments || []
  if (attachments.length > MAX_CHAT_ATTACHMENTS) throw new Error('每条消息最多添加 2 个附件')
  if (attachments.reduce((sum, item) => sum + Number(item.size || item.rawFile?.size || 0), 0)
      > MAX_CHAT_ATTACHMENTS_BYTES) throw new Error('附件总大小不能超过 10 MB')
  const uploaded = await Promise.all(attachments.map(item => (
    uploadAgentAttachment(sessionId, item)
  )))
  const attachmentIds = []
  const formulaAttachmentIds = []
  uploaded.forEach((item, index) => {
    if (attachments[index]?.kind === 'FORMULA_TEXT') formulaAttachmentIds.push(item.attachmentId)
    else attachmentIds.push(item.attachmentId)
  })
  const contextParts = []
  const anchor = request.selectionAnchor
  const selectedContent = anchor && anchor.documentHash && (anchor.targetText || anchor.text) ? {
    selectionId: String(anchor.anchorId || anchor.selectionId || `selection-${Date.now()}`),
    paperId,
    documentHash: anchor.documentHash,
    pageNumber: Number(anchor.page || 1),
    contentType: anchor.contentType || 'TEXT',
    exactText: anchor.targetText || anchor.text,
    sourceObjectIds: [anchor.sourceObjectId || anchor.anchorId].filter(Boolean),
  } : null
  const selectedText = String(selectedContent?.exactText || '')
  const selectionLimit = String(selectedContent?.contentType || '').toUpperCase().includes('FORMULA')
    ? MAX_FORMULA_SELECTION_CHARACTERS
    : MAX_TEXT_SELECTION_CHARACTERS
  if (selectedText.length > selectionLimit) throw new Error('选取内容过长')
  if (String(request.userMessage || '').trim().length > MAX_AGENT_MESSAGE_CHARACTERS) throw new Error('输入内容过长')
  if (anchor && !selectedContent && (anchor.targetText || anchor.text)) {
    contextParts.unshift(`当前选区：\n${anchor.targetText || anchor.text}`)
  }
  return {
    conversationId: sessionId,
    primaryPaperId: paperId,
    userMessage: [request.userMessage, ...contextParts].filter(Boolean).join('\n\n'),
    selectedContent,
    attachmentIds,
    formulaAttachmentIds,
    uiContext: { pageNumber: Number(anchor?.page || 1), zoom: null, activeTool: null },
    clientRequestId: globalThis.crypto?.randomUUID?.() || `turn-${Date.now()}-${Math.random()}`,
    resumeRunId: request.resumeRunId || null,
  }
}

function toViewModel(response) {
  const evidence = mapAgentEvidenceList(response.evidence || [])
  const claims = (response.citations || []).map(item => ({
    text: response.message?.slice(item.answerStart, item.answerEnd) || '',
    evidenceIds: [item.sourceObjectId],
  }))
  const actions = (response.pendingActions || []).map(item => ({
    ...item,
    type: item.actionType,
    paperId: item.target?.paperId,
    page: item.target?.pageNumber,
    status: 'READY',
  }))
  return {
    runId: response.runId,
    status: response.status,
    result: { answer: response.message || '', claims, answerBlocks: [], evidence, actions },
  }
}

function inferPhase(response = {}) {
  if (response?.status === 'QUEUED') return 'QUEUED'
  if (response?.status === 'WAITING_CLIENT') return 'EXECUTING_ACTION'
  if (response?.status === 'WAITING_USER') return 'WAITING_USER'
  if (response?.status === 'COMPLETED' || response?.status === 'CANCELLED') return 'IDLE'
  return 'THINKING'
}

function inferProgressLabel(response = {}) {
  if (response?.status === 'QUEUED') return '等待中…'
  if (response?.status === 'WAITING_CLIENT') return '正在执行页面操作…'
  if (response?.status === 'WAITING_USER') return '等待补充信息…'
  if (response?.status === 'COMPLETED' || response?.status === 'CANCELLED') return ''
  return '思考中…'
}
