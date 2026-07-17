import { WORKBENCH_MODES } from '@/utils/workbenchRun.js'

export const RESEARCH_ROUTE_PATH = '/research'
// Compatibility export for older callers. New navigation should use
// researchRouteLocation() so the paper identity lives in the path.
export const WORKBENCH_ROUTE_PATH = RESEARCH_ROUTE_PATH

const MODE_ALIASES = Object.freeze({
  analysis: WORKBENCH_MODES.PAPER_ANALYSIS,
  read: WORKBENCH_MODES.PAPER_ANALYSIS,
  paper: WORKBENCH_MODES.PAPER_ANALYSIS,
  paper_analysis: WORKBENCH_MODES.PAPER_ANALYSIS,
  gap: WORKBENCH_MODES.PAPER_IMPROVEMENT,
  research_gap: WORKBENCH_MODES.PAPER_IMPROVEMENT,
  'research-gap': WORKBENCH_MODES.PAPER_IMPROVEMENT,
  improvement: WORKBENCH_MODES.PAPER_IMPROVEMENT,
  paper_improvement: WORKBENCH_MODES.PAPER_IMPROVEMENT,
  'paper-improvement': WORKBENCH_MODES.PAPER_IMPROVEMENT,
  field_gap: WORKBENCH_MODES.RESEARCH_GAP,
  'field-gap': WORKBENCH_MODES.RESEARCH_GAP,
  domain_gap: WORKBENCH_MODES.RESEARCH_GAP,
  'domain-gap': WORKBENCH_MODES.RESEARCH_GAP,
  comparison: WORKBENCH_MODES.PAPER_COMPARISON,
  compare: WORKBENCH_MODES.PAPER_COMPARISON,
  paper_comparison: WORKBENCH_MODES.PAPER_COMPARISON,
  selection: WORKBENCH_MODES.SELECTION_QA,
  selection_qa: WORKBENCH_MODES.SELECTION_QA,
  annotation: WORKBENCH_MODES.SELECTION_QA,
  annotation_suggestion: WORKBENCH_MODES.SELECTION_QA,
})

export function normalizeWorkbenchRouteMode(value, fallback = WORKBENCH_MODES.PAPER_ANALYSIS) {
  const raw = Array.isArray(value) ? value[0] : value
  if (raw === WORKBENCH_MODES.ANNOTATION_SUGGESTION) return WORKBENCH_MODES.SELECTION_QA
  if (Object.values(WORKBENCH_MODES).includes(raw)) return raw
  return MODE_ALIASES[String(raw || '').trim().toLowerCase()] || fallback
}

export function workbenchModeQueryValue(mode) {
  return {
    [WORKBENCH_MODES.PAPER_ANALYSIS]: 'analysis',
    [WORKBENCH_MODES.PAPER_IMPROVEMENT]: 'improvement',
    [WORKBENCH_MODES.RESEARCH_GAP]: 'field-gap',
    [WORKBENCH_MODES.PAPER_COMPARISON]: 'comparison',
    [WORKBENCH_MODES.SELECTION_QA]: 'selection',
    [WORKBENCH_MODES.ANNOTATION_SUGGESTION]: 'selection',
  }[mode] || 'analysis'
}

export function positivePaperId(value) {
  const raw = Array.isArray(value) ? value[0] : value
  const id = Number(raw)
  return Number.isInteger(id) && id > 0 ? id : null
}

export function positivePageNumber(value) {
  const raw = Array.isArray(value) ? value[0] : value
  const page = Number(raw)
  return Number.isInteger(page) && page > 0 ? page : null
}

export function workbenchPaperIds(query = {}) {
  const baseId = positivePaperId(query.paperId)
  const rawValues = Array.isArray(query.paperIds) ? query.paperIds : [query.paperIds]
  const ids = rawValues.flatMap(value => String(value || '').split(','))
    .map(Number).filter(id => Number.isInteger(id) && id > 0)
  return [...new Set([...(baseId ? [baseId] : []), ...ids])]
}

export function legacyWorkbenchRedirect(to, defaultMode) {
  const fallback = normalizeWorkbenchRouteMode(defaultMode)
  const mode = normalizeWorkbenchRouteMode(to?.query?.mode, fallback)
  const paperIds = workbenchPaperIds(to?.query)
  const paperId = positivePaperId(to?.params?.paperId) || paperIds[0] || null
  const query = { ...(to?.query || {}), mode: workbenchModeQueryValue(mode) }
  delete query.paperId
  return {
    path: paperId ? `${RESEARCH_ROUTE_PATH}/${paperId}` : RESEARCH_ROUTE_PATH,
    query,
  }
}

export function researchRouteLocation(paperId, query = {}) {
  const id = positivePaperId(paperId)
  const normalizedQuery = { ...query }
  delete normalizedQuery.paperId
  return {
    path: id ? `${RESEARCH_ROUTE_PATH}/${id}` : RESEARCH_ROUTE_PATH,
    query: normalizedQuery,
  }
}
