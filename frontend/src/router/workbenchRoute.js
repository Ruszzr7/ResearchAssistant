import { WORKBENCH_MODES } from '@/utils/workbenchRun.js'

export const WORKBENCH_ROUTE_PATH = '/workbench'

const MODE_ALIASES = Object.freeze({
  analysis: WORKBENCH_MODES.PAPER_ANALYSIS,
  read: WORKBENCH_MODES.PAPER_ANALYSIS,
  paper: WORKBENCH_MODES.PAPER_ANALYSIS,
  paper_analysis: WORKBENCH_MODES.PAPER_ANALYSIS,
  gap: WORKBENCH_MODES.RESEARCH_GAP,
  research_gap: WORKBENCH_MODES.RESEARCH_GAP,
  'research-gap': WORKBENCH_MODES.RESEARCH_GAP,
  comparison: WORKBENCH_MODES.PAPER_COMPARISON,
  compare: WORKBENCH_MODES.PAPER_COMPARISON,
  paper_comparison: WORKBENCH_MODES.PAPER_COMPARISON,
  selection: WORKBENCH_MODES.SELECTION_QA,
  selection_qa: WORKBENCH_MODES.SELECTION_QA,
  annotation: WORKBENCH_MODES.ANNOTATION_SUGGESTION,
  annotation_suggestion: WORKBENCH_MODES.ANNOTATION_SUGGESTION,
})

export function normalizeWorkbenchRouteMode(value, fallback = WORKBENCH_MODES.PAPER_ANALYSIS) {
  const raw = Array.isArray(value) ? value[0] : value
  if (Object.values(WORKBENCH_MODES).includes(raw)) return raw
  return MODE_ALIASES[String(raw || '').trim().toLowerCase()] || fallback
}

export function workbenchModeQueryValue(mode) {
  return {
    [WORKBENCH_MODES.PAPER_ANALYSIS]: 'analysis',
    [WORKBENCH_MODES.RESEARCH_GAP]: 'gap',
    [WORKBENCH_MODES.PAPER_COMPARISON]: 'comparison',
    [WORKBENCH_MODES.SELECTION_QA]: 'selection',
    [WORKBENCH_MODES.ANNOTATION_SUGGESTION]: 'annotation',
  }[mode] || 'analysis'
}

export function positivePaperId(value) {
  const raw = Array.isArray(value) ? value[0] : value
  const id = Number(raw)
  return Number.isInteger(id) && id > 0 ? id : null
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
  return {
    path: WORKBENCH_ROUTE_PATH,
    query: { ...(to?.query || {}), mode: workbenchModeQueryValue(mode) },
  }
}
