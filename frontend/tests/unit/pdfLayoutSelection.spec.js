import { describe, expect, it } from 'vitest'
import { buildPdfPageLayoutIndex } from '@/utils/pdfLayoutIndex.js'
import { createSameColumnSelection, findLayoutRunAtPoint } from '@/utils/pdfLayoutSelection.js'

function run(id, text, x, y, width = 90, height = 12) {
  return { id, text, x, y, width, height }
}

function doubleColumnIndex() {
  return buildPdfPageLayoutIndex({
    pageNum: 1,
    pageWidth: 600,
    pageHeight: 800,
    textItems: [
      run('l1', 'left-one', 55, 100), run('r1', 'right-one', 330, 100),
      run('l2', 'left-two', 55, 122), run('r2', 'right-two', 330, 122),
      run('l3', 'left-three', 55, 144), run('r3', 'right-three', 330, 144),
      run('l4', 'left-four', 55, 166), run('r4', 'right-four', 330, 166)
    ]
  })
}

describe('PDF layout-aware selection range', () => {
  it('only resolves a pointer that lands on or immediately beside a text run', () => {
    const index = doubleColumnIndex()

    expect(findLayoutRunAtPoint(index, 60, 105)?.id).toBe('l1')
    expect(findLayoutRunAtPoint(index, 200, 105)).toBeNull()
  })

  it('keeps a same-column multi-line range in visual reading order even when dragged upward', () => {
    const index = doubleColumnIndex()

    const selection = createSameColumnSelection(index,
      { runId: 'l3', offset: 4 },
      { runId: 'l1', offset: 4 })

    expect(selection?.columnId).toBe('column-0')
    expect(selection?.segments).toEqual([
      { runId: 'l1', startOffset: 4, endOffset: 8, text: '-one' },
      { runId: 'l2', startOffset: 0, endOffset: 8, text: 'left-two' },
      { runId: 'l3', startOffset: 0, endOffset: 4, text: 'left' }
    ])
    expect(selection?.text).toBe('-one left-two left')
  })

  it('keeps partial endpoints when the drag stays in one rendered run', () => {
    const index = doubleColumnIndex()

    const selection = createSameColumnSelection(index,
      { runId: 'r2', offset: 2 },
      { runId: 'r2', offset: 7 })

    expect(selection?.segments).toEqual([
      { runId: 'r2', startOffset: 2, endOffset: 7, text: 'ght-t' }
    ])
  })

  it('rejects a cross-column drag instead of guessing a DOM range', () => {
    const index = doubleColumnIndex()

    expect(createSameColumnSelection(index,
      { runId: 'l1', offset: 1 },
      { runId: 'r2', offset: 4 }
    )).toBeNull()
  })

  it('does not interleave left-column runs when a compact double-column gutter is detected', () => {
    const index = buildPdfPageLayoutIndex({
      pageNum: 1,
      pageWidth: 918,
      pageHeight: 1188,
      textItems: [
        run('l1a', 'left', 73, 100, 150, 13.45), run('l1b', 'one', 231, 100, 219, 13.45), run('r1', 'right-one', 468, 100, 377, 13.45),
        run('l2a', 'left', 73, 120, 150, 13.45), run('l2b', 'two', 231, 120, 219, 13.45), run('r2', 'right-two', 468, 120, 377, 13.45),
        run('l3a', 'left', 73, 140, 150, 13.45), run('l3b', 'three', 231, 140, 219, 13.45), run('r3', 'right-three', 468, 140, 377, 13.45),
        run('l4a', 'left', 73, 160, 150, 13.45), run('l4b', 'four', 231, 160, 219, 13.45), run('r4', 'right-four', 468, 160, 377, 13.45)
      ]
    })

    const selection = createSameColumnSelection(index,
      { runId: 'r1', offset: 0 },
      { runId: 'r4', offset: 10 })

    expect(selection?.segments.map(segment => segment.runId)).toEqual(['r1', 'r2', 'r3', 'r4'])
    expect(selection?.text).toBe('right-one right-two right-three right-four')
  })
})
