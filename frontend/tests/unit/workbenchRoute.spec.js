import { describe, expect, it } from 'vitest'
import {
  legacyWorkbenchRedirect,
  normalizeWorkbenchRouteMode,
  workbenchModeQueryValue,
  workbenchPaperIds,
} from '@/router/workbenchRoute.js'
import { WORKBENCH_MODES } from '@/utils/workbenchRun.js'

describe('unified paper research route', () => {
  it('normalizes old analysis, Gap and comparison modes', () => {
    expect(normalizeWorkbenchRouteMode('read')).toBe(WORKBENCH_MODES.PAPER_ANALYSIS)
    expect(normalizeWorkbenchRouteMode('gap')).toBe(WORKBENCH_MODES.RESEARCH_GAP)
    expect(normalizeWorkbenchRouteMode('compare')).toBe(WORKBENCH_MODES.PAPER_COMPARISON)
    expect(workbenchModeQueryValue(WORKBENCH_MODES.RESEARCH_GAP)).toBe('gap')
  })

  it('redirects legacy entries while preserving paper parameters', () => {
    expect(legacyWorkbenchRedirect({ query: { paperId: '7', mode: 'compare' } }, 'analysis'))
      .toEqual({ path: '/workbench', query: { paperId: '7', mode: 'comparison' } })
    expect(legacyWorkbenchRedirect({ query: { paperIds: '7,8,9' } }, 'gap'))
      .toEqual({ path: '/workbench', query: { paperIds: '7,8,9', mode: 'gap' } })
  })

  it('parses and deduplicates base and additional paper IDs', () => {
    expect(workbenchPaperIds({ paperId: '7', paperIds: ['8,9', '7,invalid'] }))
      .toEqual([7, 8, 9])
  })
})
