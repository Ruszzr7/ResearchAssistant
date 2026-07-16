export const WORKBENCH_MODES = Object.freeze({
  SELECTION_QA: 'SELECTION_QA',
  PAPER_ANALYSIS: 'PAPER_ANALYSIS',
  ANNOTATION_SUGGESTION: 'ANNOTATION_SUGGESTION',
  PAPER_COMPARISON: 'PAPER_COMPARISON',
  RESEARCH_GAP: 'RESEARCH_GAP',
})

export const MAX_COMPARISON_PAPERS = 8

const MODE_CONFIG = Object.freeze({
  [WORKBENCH_MODES.SELECTION_QA]: { intent: 'ASK_SELECTION', scope: 'SELECTION' },
  [WORKBENCH_MODES.PAPER_ANALYSIS]: { intent: 'ANALYZE_PAPER', scope: 'PAPER' },
  [WORKBENCH_MODES.ANNOTATION_SUGGESTION]: { intent: 'SUGGEST_ANNOTATION', scope: 'SELECTION' },
  [WORKBENCH_MODES.PAPER_COMPARISON]: { intent: 'COMPARE_PAPERS', scope: 'COMPARISON' },
  [WORKBENCH_MODES.RESEARCH_GAP]: { intent: 'FIND_RESEARCH_GAPS', scope: 'COMPARISON' },
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

  const multiPaperMode = mode === WORKBENCH_MODES.PAPER_COMPARISON
    || mode === WORKBENCH_MODES.RESEARCH_GAP
  const paperIds = multiPaperMode
    ? normalizeComparisonPaperIds(currentPaperId, comparisonPaperIds)
    : [currentPaperId]
  if (mode === WORKBENCH_MODES.PAPER_COMPARISON && paperIds.length < 2) {
    throw new Error('请至少再选择一篇论文')
  }
  if (mode === WORKBENCH_MODES.RESEARCH_GAP && paperIds.length < 3) {
    throw new Error('研究 Gap 至少需要三篇论文')
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

export function normalizeComparisonPaperIds(paperId, comparisonPaperIds = []) {
  const currentPaperId = Number(paperId)
  const ids = [...new Set([currentPaperId, ...comparisonPaperIds.map(Number)])]
    .filter(id => Number.isInteger(id) && id > 0)
  if (ids.length > MAX_COMPARISON_PAPERS) {
    throw new Error(`一次最多对比 ${MAX_COMPARISON_PAPERS} 篇论文`)
  }
  return ids
}

export function comparisonSelectionState(paperId, comparisonPaperIds = [], minimumPaperCount = 2) {
  const currentPaperId = Number(paperId)
  const additionalIds = [...new Set(comparisonPaperIds.map(Number))]
    .filter(id => Number.isInteger(id) && id > 0 && id !== currentPaperId)
  const total = 1 + additionalIds.length
  return {
    additionalIds,
    total,
    canStart: total >= minimumPaperCount && total <= MAX_COMPARISON_PAPERS,
    atLimit: total >= MAX_COMPARISON_PAPERS,
    max: MAX_COMPARISON_PAPERS,
  }
}

export function buildComparisonQuestion(question, dimensions = []) {
  const normalizedQuestion = String(question || '').trim()
  const normalizedDimensions = [...new Set((dimensions || [])
    .map(value => String(value || '').trim()).filter(Boolean))]
  const prefix = normalizedDimensions.length
    ? `比较维度：${normalizedDimensions.join('、')}。`
    : ''
  return `${prefix}${normalizedQuestion}`.slice(0, 4000)
}

/** Per-paper citation coverage for a completed comparison result. */
export function buildComparisonCoverage(trace, papers = []) {
  const paperTitleById = new Map((papers || []).map(paper => [
    Number(paper?.id), String(paper?.title || '').trim(),
  ]))
  const result = trace?.result || {}
  const requiredPaperIds = Array.isArray(result.paperIds) && result.paperIds.length
    ? result.paperIds
    : trace?.invocation?.paperIds || []
  const paperIds = [...new Set(requiredPaperIds
    .map(Number).filter(id => Number.isInteger(id) && id > 0))]
  const evidenceById = new Map()
  const evidenceByPaper = new Map()
  for (const item of result.evidence || []) {
    const paperId = Number(item?.paperId)
    if (!Number.isInteger(paperId) || paperId <= 0 || !item?.evidenceId) continue
    evidenceById.set(item.evidenceId, item)
    if (!evidenceByPaper.has(paperId)) evidenceByPaper.set(paperId, [])
    evidenceByPaper.get(paperId).push(item)
    if (!paperIds.includes(paperId)) paperIds.push(paperId)
  }
  const citedClaimsByPaper = new Map(paperIds.map(id => [id, 0]))
  for (const claim of result.claims || []) {
    const citedPaperIds = new Set((claim?.evidenceIds || [])
      .map(id => Number(evidenceById.get(id)?.paperId))
      .filter(id => Number.isInteger(id) && id > 0))
    for (const paperId of citedPaperIds) {
      citedClaimsByPaper.set(paperId, (citedClaimsByPaper.get(paperId) || 0) + 1)
    }
  }
  const rows = paperIds.map(paperId => {
    const evidence = evidenceByPaper.get(paperId) || []
    const pages = [...new Set(evidence.map(item => Number(item.page))
      .filter(page => Number.isInteger(page) && page > 0))].sort((a, b) => a - b)
    const citedClaims = citedClaimsByPaper.get(paperId) || 0
    return {
      paperId,
      title: paperTitleById.get(paperId) || `论文 #${paperId}`,
      evidenceCount: evidence.length,
      citedClaims,
      pages,
      covered: evidence.length > 0 && citedClaims > 0,
      firstEvidence: evidence[0] || null,
    }
  })
  const covered = rows.filter(row => row.covered).length
  return {
    rows,
    covered,
    total: rows.length,
    coverageRate: rows.length ? covered / rows.length : 0,
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
