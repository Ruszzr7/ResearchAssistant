export const WORKBENCH_MODES = Object.freeze({
  SELECTION_QA: 'SELECTION_QA',
})

export function buildWorkbenchPlanRequest({
  paperId,
  question = '',
  selectionAnchor = null,
  conversationId = '',
}) {
  const currentPaperId = Number(paperId)
  if (!Number.isInteger(currentPaperId) || currentPaperId <= 0) throw new Error('当前论文无效')

  const normalizedQuestion = String(question || '').trim()
  if (!normalizedQuestion) throw new Error('请输入问题')

  const regionOnly = selectionAnchor?.mappingStatus
    ? selectionAnchor.mappingStatus === 'REGION'
    : selectionAnchor?.kind === 'REGION'
  const scope = !selectionAnchor ? 'PAPER' : regionOnly ? 'REGION' : 'SELECTION'
  const normalizedConversationId = String(conversationId || '').trim()
  return {
    paperIds: [currentPaperId],
    question: normalizedQuestion,
    intent: 'ASK_SELECTION',
    scope,
    ...(selectionAnchor ? { selectionAnchor } : {}),
    ...(normalizedConversationId ? { conversationId: normalizedConversationId } : {}),
    maxSteps: 6,
  }
}

export function evidenceIndex(trace) {
  return new Map((trace?.result?.evidence || []).map(item => [item.evidenceId, item]))
}

export function citedEvidence(trace) {
  const index = evidenceIndex(trace)
  const ids = new Set()
  for (const claim of trace?.result?.claims || []) {
    for (const id of claim?.evidenceIds || []) ids.add(id)
  }
  for (const id of trace?.result?.annotationSuggestion?.evidenceIds || []) ids.add(id)
  return [...ids].map(id => index.get(id)).filter(Boolean)
}

export function traceIsActive(trace) {
  return ['PLANNED', 'QUEUED', 'RUNNING'].includes(trace?.status)
}

export function stepStatusLabel(status) {
  return {
    PENDING: '等待',
    RUNNING: '执行中',
    COMPLETED: '完成',
    FAILED: '失败',
    SKIPPED: '跳过',
  }[status] || status || '等待'
}

const TRACE_PHASE_DEFINITIONS = Object.freeze([
  { key: 'scope', description: '定位当前阅读范围', matches: step => step.index === 0 },
  { key: 'evidence', description: '提取可回链证据', matches: step => step.index === 1 },
  { key: 'answer', description: '生成回答', matches: step => step.index === 2 },
  { key: 'verify', description: '校验并保存结果', matches: step => step.index >= 3 },
])

/** Four compact product phases backed by the persisted low-level run trace. */
export function compactTracePhases(trace) {
  const steps = Array.isArray(trace?.steps) ? trace.steps : []
  return TRACE_PHASE_DEFINITIONS.map(definition => {
    const grouped = steps.filter(definition.matches)
    const status = groupedStatus(grouped)
    const evidenceCount = Math.max(0, ...grouped.map(step => Number(step?.evidenceCount) || 0))
    const totalTokens = grouped.reduce((sum, step) => sum + (Number(step?.totalTokens) || 0), 0)
    const latencyMs = grouped.reduce((sum, step) => sum + (Number(step?.latencyMs) || 0), 0)
    const detail = grouped.map(step => `${step.name || definition.description}：${stepStatusLabel(step.status)}`)
      .join('；')
    const metrics = [
      evidenceCount ? `${evidenceCount} 条证据` : '',
      totalTokens ? `${totalTokens} tokens` : '',
      latencyMs ? formatCompactDuration(latencyMs) : '',
    ].filter(Boolean).join(' · ')
    const error = grouped.find(step => step?.errorMessage)?.errorMessage || ''
    return {
      key: definition.key,
      description: definition.description,
      status,
      statusLabel: stepStatusLabel(status),
      tooltip: [
        `${definition.description}：${stepStatusLabel(status)}`,
        detail,
        metrics,
        error,
      ].filter(Boolean).join('\n'),
    }
  })
}

function groupedStatus(steps) {
  if (!steps.length) return 'PENDING'
  if (steps.some(step => step?.status === 'FAILED')) return 'FAILED'
  if (steps.some(step => step?.status === 'RUNNING')) return 'RUNNING'
  if (steps.every(step => ['COMPLETED', 'SKIPPED'].includes(step?.status))) return 'COMPLETED'
  return 'PENDING'
}

function formatCompactDuration(milliseconds) {
  const value = Math.max(0, Number(milliseconds) || 0)
  return value >= 1000 ? `${(value / 1000).toFixed(value >= 10000 ? 0 : 1)}s` : `${value}ms`
}
