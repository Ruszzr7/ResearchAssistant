export const WORKBENCH_MODES = Object.freeze({
  SELECTION_QA: 'SELECTION_QA',
  PAPER_ANALYSIS: 'PAPER_ANALYSIS',
  ANNOTATION_SUGGESTION: 'ANNOTATION_SUGGESTION',
  PAPER_COMPARISON: 'PAPER_COMPARISON',
})

const MODE_CONFIG = Object.freeze({
  [WORKBENCH_MODES.SELECTION_QA]: { intent: 'ASK_SELECTION', scope: 'SELECTION' },
  [WORKBENCH_MODES.PAPER_ANALYSIS]: { intent: 'ANALYZE_PAPER', scope: 'PAPER' },
  [WORKBENCH_MODES.ANNOTATION_SUGGESTION]: { intent: 'SUGGEST_ANNOTATION', scope: 'SELECTION' },
  [WORKBENCH_MODES.PAPER_COMPARISON]: { intent: 'COMPARE_PAPERS', scope: 'COMPARISON' },
})

export function buildWorkbenchPlanRequest({
  mode,
  paperId,
  comparisonPaperIds = [],
  question = '',
  selectionAnchor = null,
}) {
  const config = MODE_CONFIG[mode]
  if (!config) throw new Error('请选择论文助手功能')
  const currentPaperId = Number(paperId)
  if (!Number.isInteger(currentPaperId) || currentPaperId <= 0) throw new Error('当前论文无效')

  const needsSelection = mode === WORKBENCH_MODES.SELECTION_QA
    || mode === WORKBENCH_MODES.ANNOTATION_SUGGESTION
  if (needsSelection && !selectionAnchor) throw new Error('请先在 PDF 中选择内容')

  const normalizedQuestion = String(question || '').trim()
  if (mode !== WORKBENCH_MODES.PAPER_ANALYSIS && !normalizedQuestion) {
    throw new Error('请填写问题或分析要求')
  }

  const paperIds = mode === WORKBENCH_MODES.PAPER_COMPARISON
    ? [...new Set([currentPaperId, ...comparisonPaperIds.map(Number)])]
        .filter(id => Number.isInteger(id) && id > 0)
    : [currentPaperId]
  if (mode === WORKBENCH_MODES.PAPER_COMPARISON && paperIds.length < 2) {
    throw new Error('请至少再选择一篇论文')
  }

  const scope = selectionAnchor?.kind === 'REGION' && needsSelection ? 'REGION' : config.scope
  return {
    paperIds,
    question: normalizedQuestion,
    intent: config.intent,
    scope,
    ...(needsSelection ? { selectionAnchor } : {}),
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

export function appliedWorkbenchRunIds(annotations) {
  return [...new Set((annotations || [])
    .map(item => item?.coordinates?.workbenchRunId)
    .filter(id => typeof id === 'string' && id.trim()))]
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

/** Small, escaped Markdown subset for model reports; never renders raw model HTML. */
export function workbenchMarkdownToHtml(value) {
  const escaped = String(value || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
  const inline = text => text
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
  const output = []
  let listType = ''
  const closeList = () => {
    if (listType) output.push(`</${listType}>`)
    listType = ''
  }
  for (const rawLine of escaped.split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line) {
      closeList()
      continue
    }
    const heading = line.match(/^(#{1,4})\s+(.+)$/)
    if (heading) {
      closeList()
      const level = Math.min(5, heading[1].length + 2)
      output.push(`<h${level}>${inline(heading[2])}</h${level}>`)
      continue
    }
    const bullet = line.match(/^[-*]\s+(.+)$/)
    const numbered = line.match(/^\d+[.)]\s+(.+)$/)
    if (bullet || numbered) {
      const nextType = bullet ? 'ul' : 'ol'
      if (listType !== nextType) {
        closeList()
        listType = nextType
        output.push(`<${listType}>`)
      }
      output.push(`<li>${inline((bullet || numbered)[1])}</li>`)
      continue
    }
    closeList()
    output.push(`<p>${inline(line)}</p>`)
  }
  closeList()
  return output.join('')
}
