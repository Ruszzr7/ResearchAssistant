import { describe, expect, it } from 'vitest'
import {
  buildFailedPdfPageSearchRecord,
  buildPdfPageSearchRecord,
  findPdfSearchMatches,
  normalizePdfSearchQuery,
  summarizePdfSearchIndex,
} from '@/utils/pdfSearch.js'
import golden from '../fixtures/pdf-content-golden.json'

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

  it('matches words split by PDF text items, line-wrap hyphens, and ligatures', () => {
    const record = buildPdfPageSearchRecord(5, [
      { str: 'micro' },
      { str: 'scope and multi-' },
      { str: 'modal ﬁne-tuning' },
    ])

    expect(findPdfSearchMatches([record], 'microscope')).toEqual([
      expect.objectContaining({ page: 5, spanIndexes: [0, 1] }),
    ])
    expect(findPdfSearchMatches([record], 'multimodal fine tuning')).toHaveLength(1)
  })

  it('keeps original TextLayer indexes when whitespace items appear before a match', () => {
    const fixture = golden.searchCases.find(testCase => (
      testCase.id === 'ieee-whitespace-preserves-original-item-index'
    ))
    const record = buildPdfPageSearchRecord(fixture.page, fixture.items)

    expect(findPdfSearchMatches([record], fixture.query)).toEqual([
      expect.objectContaining({
        page: fixture.expected.page,
        itemIndexes: fixture.expected.itemIndexes,
        itemRanges: [expect.objectContaining({ itemIndex: 3, startOffset: 0 })],
      }),
    ])
  })

  it('uses rendered span indexes without losing original empty text items', () => {
    const record = buildPdfPageSearchRecord(1, [
      { str: 'before' },
      { str: '' },
      { str: 'target phrase' },
    ])

    expect(findPdfSearchMatches([record], 'target phrase')).toEqual([
      expect.objectContaining({ itemIndexes: [2], spanIndexes: [1] }),
    ])
  })

  it('reports no-text and failed pages separately from ready pages', () => {
    const summary = summarizePdfSearchIndex([
      buildPdfPageSearchRecord(1, [{ str: 'searchable' }]),
      buildPdfPageSearchRecord(2, []),
      buildFailedPdfPageSearchRecord(3, 'TEXT_EXTRACTION_FAILED'),
    ])
    expect(summary).toEqual({ ready: 1, noTextLayer: 1, failed: 1 })
  })

  it('returns no matches for an empty query', () => {
    expect(findPdfSearchMatches([buildPdfPageSearchRecord(1, [{ str: 'text' }])], '  ')).toEqual([])
  })
})
