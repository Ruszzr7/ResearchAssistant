import { describe, expect, it } from 'vitest'
import {
  legacyWorkbenchRedirect,
  normalizeWorkbenchRouteMode,
  positivePageNumber,
  researchRouteLocation,
  workbenchModeQueryValue,
  workbenchPaperIds,
} from '@/router/workbenchRoute.js'
import { WORKBENCH_MODES } from '@/utils/workbenchRun.js'

describe('unified paper research route', () => {
  it('routes legacy Gap to single-paper improvement and reserves field-gap for comparison follow-up', () => {
    expect(normalizeWorkbenchRouteMode('read')).toBe(WORKBENCH_MODES.PAPER_ANALYSIS)
    expect(normalizeWorkbenchRouteMode('gap')).toBe(WORKBENCH_MODES.PAPER_IMPROVEMENT)
    expect(normalizeWorkbenchRouteMode('paper-improvement')).toBe(WORKBENCH_MODES.PAPER_IMPROVEMENT)
    expect(normalizeWorkbenchRouteMode('field-gap')).toBe(WORKBENCH_MODES.RESEARCH_GAP)
    expect(normalizeWorkbenchRouteMode('compare')).toBe(WORKBENCH_MODES.PAPER_COMPARISON)
    expect(workbenchModeQueryValue(WORKBENCH_MODES.PAPER_IMPROVEMENT)).toBe('improvement')
    expect(workbenchModeQueryValue(WORKBENCH_MODES.RESEARCH_GAP)).toBe('field-gap')
  })

  it('routes the retired annotation-suggestion entry to selection QA', () => {
    expect(normalizeWorkbenchRouteMode('annotation')).toBe(WORKBENCH_MODES.SELECTION_QA)
    expect(normalizeWorkbenchRouteMode('annotation_suggestion')).toBe(WORKBENCH_MODES.SELECTION_QA)
    expect(normalizeWorkbenchRouteMode(WORKBENCH_MODES.ANNOTATION_SUGGESTION))
      .toBe(WORKBENCH_MODES.SELECTION_QA)
    expect(workbenchModeQueryValue(WORKBENCH_MODES.ANNOTATION_SUGGESTION)).toBe('selection')
  })

  it('redirects legacy entries while preserving paper parameters', () => {
    expect(legacyWorkbenchRedirect({ query: { paperId: '7', mode: 'compare' } }, 'analysis'))
      .toEqual({ path: '/research/7', query: { mode: 'comparison' } })
    expect(legacyWorkbenchRedirect({ query: { paperIds: '7,8,9' } }, 'gap'))
      .toEqual({ path: '/research/7', query: { paperIds: '7,8,9', mode: 'improvement' } })
  })

  it('parses and deduplicates base and additional paper IDs', () => {
    expect(workbenchPaperIds({ paperId: '7', paperIds: ['8,9', '7,invalid'] }))
      .toEqual([7, 8, 9])
  })

  it('builds canonical research routes and accepts only positive page numbers', () => {
    expect(researchRouteLocation(7, { paperId: 8, page: '13', mode: 'analysis' }))
      .toEqual({ path: '/research/7', query: { page: '13', mode: 'analysis' } })
    expect(positivePageNumber('13')).toBe(13)
    expect(positivePageNumber('0')).toBeNull()
    expect(positivePageNumber('2.5')).toBeNull()
  })
})
