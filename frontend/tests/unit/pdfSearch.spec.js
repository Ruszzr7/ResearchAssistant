import { describe, expect, it } from 'vitest'
import {
  buildPdfPageSearchRecord,
  findPdfSearchMatches,
  normalizePdfSearchQuery,
} from '@/utils/pdfSearch.js'

describe('PDF document search', () => {
  it('normalizes user whitespace and matches case-insensitively across text items', () => {
    const record = buildPdfPageSearchRecord(3, [
      { str: 'Graph Neural' },
      { str: 'Networks improve retrieval.' },
    ])

    expect(normalizePdfSearchQuery('  graph   NEURAL networks ')).toBe('graph neural networks')
    expect(findPdfSearchMatches([record], 'graph neural networks')).toEqual([
      expect.objectContaining({ page: 3, spanIndexes: [0, 1] }),
    ])
  })

  it('returns ordered page contexts and every occurrence', () => {
    const records = [
      buildPdfPageSearchRecord(1, [{ str: 'method and method' }]),
      buildPdfPageSearchRecord(2, [{ str: 'another method appears here' }]),
    ]

    const matches = findPdfSearchMatches(records, 'method')
    expect(matches.map(match => match.page)).toEqual([1, 1, 2])
    expect(matches[0].context).toContain('method')
  })

  it('returns no matches for an empty query', () => {
    expect(findPdfSearchMatches([buildPdfPageSearchRecord(1, [{ str: 'text' }])], '  ')).toEqual([])
  })
})
