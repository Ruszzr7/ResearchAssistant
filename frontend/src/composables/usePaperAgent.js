import { onUnmounted, ref } from 'vue'
import { executeAgentTurn, getAgentRun, uploadAgentAttachment } from '@/api/agent.js'

export function usePaperAgent() {
  const running = ref(false)
  const error = ref('')
  let controller = null

  function stopWatching() {
    controller?.abort()
    controller = null
  }

  async function getRun(runId, signal) {
    return getAgentRun(runId, signal ? { signal } : undefined)
  }

  async function watchRun(runId) {
    stopWatching()
    controller = new AbortController()
    const signal = controller.signal
    running.value = true
    error.value = ''
    try {
      for (let attempt = 0; attempt < 360; attempt += 1) {
        const current = await getRun(runId, signal)
        if (['COMPLETED', 'WAITING_USER', 'WAITING_CLIENT'].includes(current?.status)) return toViewModel(current)
        if (['FAILED', 'CANCELLED'].includes(current?.status)) {
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
      running.value = false
    }
  }

  async function run(input, { onAccepted } = {}) {
    stopWatching()
    error.value = ''
    running.value = true
    try {
      const response = await executeAgentTurn(await toApiInput(input))
      const result = toViewModel(response)
      if (typeof onAccepted === 'function') onAccepted(result)
      return ['QUEUED', 'RUNNING'].includes(result.status)
        ? await watchRun(result.runId)
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

  onUnmounted(stopWatching)
  return { running, error, run, watchRun, stopWatching }
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
  const uploaded = await Promise.all((request.attachments || []).map(item => (
    uploadAgentAttachment(sessionId, item)
  )))
  const attachmentIds = []
  const formulaAttachmentIds = []
  uploaded.forEach((item, index) => {
    if (request.attachments[index]?.kind === 'FORMULA_TEXT') formulaAttachmentIds.push(item.attachmentId)
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
  const evidence = (response.evidence || []).map(item => {
    const locator = item.locators?.[0] || {}
    return {
      evidenceId: item.sourceObjectId,
      sourceObjectId: item.sourceObjectId,
      paperId: item.paperId || null,
      page: locator.pageNumber,
      text: item.quote,
      formulaNumber: item.formulaNumber || '',
      formulaNumbers: Array.isArray(item.formulaNumbers) ? item.formulaNumbers : [],
      locator: {
        targetText: item.quote,
        targetBoxes: locator.rects || [],
        targetBbox: unionBoundingBoxes(locator.rects || []),
        precision: locator.precision,
        formulaNumber: item.formulaNumber || '',
        formulaNumbers: Array.isArray(item.formulaNumbers) ? item.formulaNumbers : [],
      },
    }
  })
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

export function unionBoundingBoxes(boxes) {
  const valid = (boxes || []).filter(box => Number.isFinite(Number(box?.x))
    && Number.isFinite(Number(box?.y)) && Number(box?.width) > 0 && Number(box?.height) > 0)
  if (!valid.length) return null
  const left = Math.min(...valid.map(box => Number(box.x)))
  const top = Math.min(...valid.map(box => Number(box.y)))
  const right = Math.max(...valid.map(box => Number(box.x) + Number(box.width)))
  const bottom = Math.max(...valid.map(box => Number(box.y) + Number(box.height)))
  const normalized = value => Number(value.toFixed(6))
  return {
    x: normalized(left), y: normalized(top),
    width: normalized(right - left), height: normalized(bottom - top),
  }
}
