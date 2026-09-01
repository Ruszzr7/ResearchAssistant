import { describe, expect, it } from 'vitest'
import {
  legacyWorkbenchRedirect,
  positivePageNumber,
  researchRouteLocation,
} from '@/router/workbenchRoute.js'

describe('unified paper research route', () => {
  it('redirects every retired workbench mode to the same paper conversation', () => {
    expect(legacyWorkbenchRedirect({ query: { paperId: '7', mode: 'analysis' } }))
      .toEqual({ path: '/research/7', query: {} })
    expect(legacyWorkbenchRedirect({ query: { paperIds: '8,9', page: '3' } }))
      .toEqual({ path: '/research/8', query: { page: '3' } })
  })

  it('builds canonical research routes without obsolete mode parameters', () => {
    expect(researchRouteLocation(7, {
      paperId: 8, paperIds: '8,9', page: '13', mode: 'analysis',
    })).toEqual({ path: '/research/7', query: { page: '13' } })
    expect(positivePageNumber('13')).toBe(13)
    expect(positivePageNumber('0')).toBeNull()
    expect(positivePageNumber('2.5')).toBeNull()
  })
})
