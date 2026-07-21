import { describe, expect, it } from 'vitest'
import {
  buildPdfPageLayoutIndex,
  clusterTextRunsIntoLines,
  normalizeTextRuns
} from '@/utils/pdfLayoutIndex.js'

function textRun(id, text, x, y, width = 90, height = 12, extra = {}) {
  return { id, text, x, y, width, height, ...extra }
}

describe('PDF rendered layout index', () => {
  it('keeps DOM span and original PDF.js item indexes as separate identities', () => {
    const index = buildPdfPageLayoutIndex({
      pageNum: 1,
      pageWidth: 600,
      pageHeight: 800,
      textItems: [textRun('mapped', 'target', 10, 10, 40, 12, { spanIndex: 7, itemIndex: 9 })],
    })

    expect(index.runs[0]).toMatchObject({ sourceIndex: 7, spanIndex: 7, itemIndex: 9 })
  })

  it('splits same-baseline left and right column runs into separate visual lines', () => {
    const runs = normalizeTextRuns([
      textRun('left-a', 'left column', 50, 100, 100),
      textRun('left-b', 'continues', 154, 100, 65),
      textRun('right-a', 'right column', 340, 100, 105)
    ])

    const lines = clusterTextRunsIntoLines(runs, { pageWidth: 600 })

    expect(lines).toHaveLength(2)
    expect(lines.map(line => line.text)).toEqual(['left column continues', 'right column'])
  })

  it('uses a repeated narrow central gutter instead of merging two columns into full-width lines', () => {
    const textItems = [
      textRun('l1a', 'left', 73, 100, 150, 13.45), textRun('l1b', 'one', 231, 100, 219, 13.45), textRun('r1', 'right one', 468, 100, 377, 13.45),
      textRun('l2a', 'left', 73, 120, 150, 13.45), textRun('l2b', 'two', 231, 120, 219, 13.45), textRun('r2', 'right two', 468, 120, 377, 13.45),
      textRun('l3a', 'left', 73, 140, 150, 13.45), textRun('l3b', 'three', 231, 140, 219, 13.45), textRun('r3', 'right three', 468, 140, 377, 13.45),
      textRun('l4a', 'left', 73, 160, 150, 13.45), textRun('l4b', 'four', 231, 160, 219, 13.45), textRun('r4', 'right four', 468, 160, 377, 13.45)
    ]

    const index = buildPdfPageLayoutIndex({ pageNum: 1, pageWidth: 918, pageHeight: 1188, textItems })

    expect(index.lines.map(line => line.text)).toEqual([
      'left one', 'right one',
      'left two', 'right two',
      'left three', 'right three',
      'left four', 'right four'
    ])
    expect(index.columns).toHaveLength(2)
    expect(index.lines.filter(line => line.columnId === 'full')).toHaveLength(0)
  })

  it('detects a double-column body while keeping a full-width title first in reading order', () => {
    const textItems = [
      textRun('title', 'A full width paper title', 70, 30, 460, 20),
      textRun('author', 'Author One and Author Two', 150, 58, 300, 14),
      textRun('l1', 'left one', 55, 110),
      textRun('r1', 'right one', 330, 110),
      textRun('l2', 'left two', 55, 132),
      textRun('r2', 'right two', 330, 132),
      textRun('l3', 'left three', 55, 154),
      textRun('r3', 'right three', 330, 154),
      textRun('l4', 'left four', 55, 176),
      textRun('r4', 'right four', 330, 176)
    ]

    const index = buildPdfPageLayoutIndex({ pageNum: 1, pageWidth: 600, pageHeight: 800, textItems })
    const orderedText = index.readingOrder.map(id => index.lines.find(line => line.id === id)?.text)

    expect(index.columns).toHaveLength(2)
    expect(index.columns.map(column => column.lineIds)).toEqual([
      ['line-2', 'line-4', 'line-6', 'line-8'],
      ['line-3', 'line-5', 'line-7', 'line-9']
    ])
    expect(orderedText).toEqual([
      'A full width paper title',
      'Author One and Author Two',
      'left one', 'left two', 'left three', 'left four',
      'right one', 'right two', 'right three', 'right four'
    ])
  })

  it('breaks paragraph candidates at a meaningful vertical gap without crossing columns', () => {
    const index = buildPdfPageLayoutIndex({
      pageNum: 1,
      pageWidth: 600,
      pageHeight: 800,
      textItems: [
        textRun('l1', 'first paragraph one', 55, 100),
        textRun('l2', 'first paragraph two', 55, 120),
        textRun('l3', 'second paragraph one', 55, 175),
        textRun('l4', 'second paragraph two', 55, 195)
      ]
    })

    expect(index.columns).toHaveLength(1)
    expect(index.paragraphs).toHaveLength(2)
    expect(index.paragraphs.map(paragraph => paragraph.text)).toEqual([
      'first paragraph one first paragraph two',
      'second paragraph one second paragraph two'
    ])
  })

  it('retains explicitly vertical marginal text but keeps it out of line reading flow', () => {
    const index = buildPdfPageLayoutIndex({
      pageNum: 1,
      pageWidth: 600,
      pageHeight: 800,
      textItems: [
        textRun('margin', 'IEEE publication information', 10, 220, 10, 170, { orientation: 'vertical' }),
        textRun('body', 'body sentence', 60, 120, 100)
      ]
    })

    expect(index.verticalRuns.map(run => run.id)).toEqual(['margin'])
    expect(index.lines.map(line => line.text)).toEqual(['body sentence'])
    expect(index.readingOrder).toEqual(['line-0'])
  })
})
