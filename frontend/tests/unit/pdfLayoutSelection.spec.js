import { describe, expect, it } from 'vitest'
import { buildPdfPageLayoutIndex } from '@/utils/pdfLayoutIndex.js'
import { createSameColumnSelection, findLayoutRunAtPoint } from '@/utils/pdfLayoutSelection.js'

function run(id, text, x, y, width = 90, height = 12) {
  return { id, text, x, y, width, height }
}

function indexedRun(id, text, x, y, width = 20, height = 12) {
  return {
    id,
    text,
    orientation: 'horizontal',
    x,
    y,
    width,
    height,
    right: x + width,
    bottom: y + height,
    centerX: x + width / 2,
    centerY: y + height / 2
  }
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

  it('uses vertical proximity to disambiguate overlapping dense text rectangles', () => {
    const index = {
      runs: [
        indexedRun('upper', 'upper reference', 55, 100, 180, 14),
        indexedRun('lower', 'lower reference', 55, 108, 180, 14),
      ],
    }

    // y=112 lies inside both rectangles, but is visibly closer to the lower row.
    expect(findLayoutRunAtPoint(index, 80, 112)?.id).toBe('lower')
    expect(findLayoutRunAtPoint(index, 80, 104)?.id).toBe('upper')
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

  it('keeps all centred title lines in one selectable header lane', () => {
    const index = buildPdfPageLayoutIndex({
      pageNum: 1,
      pageWidth: 600,
      pageHeight: 800,
      textItems: [
        run('title-1', 'A Long IEEE Paper Title', 70, 32, 460, 28),
        run('title-2', 'That Continues on a Second Line', 115, 66, 370, 28),
        run('title-3', 'and a Third Line', 220, 100, 160, 28),
        run('l1', 'left body one', 55, 170), run('r1', 'right body one', 330, 170),
        run('l2', 'left body two', 55, 192), run('r2', 'right body two', 330, 192),
        run('l3', 'left body three', 55, 214), run('r3', 'right body three', 330, 214),
        run('l4', 'left body four', 55, 236), run('r4', 'right body four', 330, 236)
      ]
    })

    expect(index.lines.filter(line => line.text.includes('Line') || line.text.includes('Title'))
      .map(line => line.columnId)).toEqual(['full', 'full', 'full'])

    const selection = createSameColumnSelection(index,
      { runId: 'title-1', offset: 0 },
      { runId: 'title-3', offset: 'and a Third Line'.length })

    expect(selection?.columnId).toBe('full')
    expect(selection?.segments.map(segment => segment.runId)).toEqual(['title-1', 'title-2', 'title-3'])
  })

  it('keeps biography text in its column when a portrait indents the upper lines', () => {
    const index = buildPdfPageLayoutIndex({
      pageNum: 15,
      pageWidth: 600,
      pageHeight: 800,
      textItems: [
        // Three ordinary left-column lines establish the real column edge.
        run('left-anchor-1', 'reference line one', 55, 42, 245),
        run('left-anchor-2', 'reference line two', 55, 64, 245),
        run('left-anchor-3', 'reference line three', 55, 266, 245),
        // The biography begins to the right of a portrait and expands back to
        // the full left column below the image.
        run('bio-1', 'Biography beside portrait one', 130, 100, 170),
        run('bio-2', 'Biography beside portrait two', 130, 122, 170),
        run('bio-3', 'Biography beside portrait three', 130, 144, 170),
        run('bio-4', 'Biography below portrait one', 55, 196, 245),
        run('bio-5', 'Biography below portrait two', 55, 218, 245),
        run('bio-6', 'Biography below portrait three', 55, 240, 245),
        run('right-1', 'right column one', 330, 42, 220),
        run('right-2', 'right column two', 330, 64, 220),
        run('right-3', 'right column three', 330, 86, 220),
        run('right-4', 'right column four', 330, 108, 220)
      ]
    })

    const selection = createSameColumnSelection(index,
      { runId: 'bio-1', offset: 0 },
      { runId: 'bio-6', offset: 'Biography below portrait three'.length })

    expect(selection?.columnId).toBe('column-0')
    expect(selection?.segments.map(segment => segment.runId)).toEqual([
      'bio-1', 'bio-2', 'bio-3', 'bio-4', 'bio-5', 'bio-6'
    ])
  })

  it('keeps an inline formula attached to its only neighbouring prose lane', () => {
    const index = buildPdfPageLayoutIndex({
      pageNum: 13,
      pageWidth: 600,
      pageHeight: 800,
      textItems: [
        // Stable rows establish a 20px gutter centred at x=310.
        run('left-anchor-1', 'left column anchor one', 50, 40, 240), run('right-anchor-1', 'right column anchor one', 330, 40, 220),
        run('left-anchor-2', 'left column anchor two', 50, 62, 240), run('right-anchor-2', 'right column anchor two', 330, 62, 220),
        run('left-anchor-3', 'left column anchor three', 50, 168, 240), run('right-anchor-3', 'right column anchor three', 330, 168, 220),
        // Italic mathematical glyph bounds can spill into the gutter even
        // though the sentence continues only in the right column.
        run('inline-formula', 'Nₖ', 295, 106, 30),
        run('right-prose-after-formula', 'can be approximated by the r.v.', 330, 106, 210),
        run('right-prose-next-line', 'with the distribution Gamma.', 330, 128, 210)
      ]
    })

    const formulaLine = index.lines.find(line => line.runIds.includes('inline-formula'))
    expect(formulaLine?.columnId).toBe('column-1')

    const selection = createSameColumnSelection(index,
      { runId: 'inline-formula', offset: 0 },
      { runId: 'right-prose-next-line', offset: 'with the distribution Gamma.'.length })

    expect(selection?.segments.map(segment => segment.runId)).toEqual([
      'inline-formula', 'right-prose-after-formula', 'right-prose-next-line'
    ])
  })

  it('does not pull earlier prose when a formula fragment starts a same-baseline range', () => {
    // This mirrors the real N~_k case: its accent and base glyph are split
    // into separate PDF.js lines whose raw bounding-box centres are above the
    // preceding prose line. `selectionOrderY` reunites that visual baseline.
    const index = {
      runs: [
        indexedRun('prefix', 'And the r.v.', 468, 993, 77, 15),
        indexedRun('prefix-n', 'N', 552, 993, 11, 15),
        indexedRun('formula-base', 'N', 791, 993, 11, 15),
        indexedRun('formula-accent', '~', 795, 989, 8, 15),
        indexedRun('formula-subscript', 'k', 803, 999, 6, 10),
        indexedRun('formula-with', 'with', 818, 993, 27, 15),
        indexedRun('next-line', 'the distribution', 468, 1013, 92, 15)
      ],
      lines: [
        { id: 'prefix-line', columnId: 'column-1', centerY: 1004, selectionOrderY: 1008, x: 468, runIds: ['prefix', 'prefix-n'] },
        { id: 'formula-line', columnId: 'column-1', centerY: 1001, selectionOrderY: 1008, x: 791, runIds: ['formula-base', 'formula-subscript', 'formula-with'] },
        { id: 'accent-line', columnId: 'column-1', centerY: 997, selectionOrderY: 1008, x: 795, runIds: ['formula-accent'] },
        { id: 'next-line', columnId: 'column-1', centerY: 1021, selectionOrderY: 1028, x: 468, runIds: ['next-line'] }
      ]
    }

    const selection = createSameColumnSelection(index,
      { runId: 'formula-base', offset: 0 },
      { runId: 'next-line', offset: 'the distribution'.length })

    expect(selection?.segments.map(segment => segment.runId)).toEqual([
      'formula-base', 'formula-accent', 'formula-subscript', 'formula-with', 'next-line'
    ])
  })

  it('does not let a mixed appendix equation bridge into a prose column', () => {
    const index = buildPdfPageLayoutIndex({
      pageNum: 13,
      pageWidth: 600,
      pageHeight: 800,
      textItems: [
        // Stable geometry around the appendix establishes the left/right
        // gutters even though the formula row itself is fragmented.
        run('left-anchor-1', 'left column anchor one', 50, 40, 240), run('right-anchor-1', 'right column anchor one', 312, 40, 240),
        run('left-anchor-2', 'left column anchor two', 50, 62, 240), run('right-anchor-2', 'right column anchor two', 312, 62, 240),
        run('left-anchor-3', 'left column anchor three', 50, 214, 240), run('right-anchor-3', 'right column anchor three', 312, 214, 240),
        // A left prose run, crossing formula glyph and right formula run can
        // arrive on the same PDF.js baseline.
        run('appendix-left-start', 'left appendix prose start', 50, 100, 238),
        run('appendix-formula-crossing', '∑', 288, 100, 32),
        run('appendix-right-formula', 'right formula fragment', 320, 100, 160),
        run('appendix-left-end', 'left appendix prose end', 50, 160, 238)
      ]
    })

    const selection = createSameColumnSelection(index,
      { runId: 'appendix-left-start', offset: 0 },
      { runId: 'appendix-left-end', offset: 'left appendix prose end'.length })

    expect(selection?.columnId).toBe('column-0')
    expect(selection?.segments.map(segment => segment.runId)).toEqual([
      'appendix-left-start', 'appendix-left-end'
    ])
    expect(createSameColumnSelection(index,
      { runId: 'appendix-formula-crossing', offset: 0 },
      { runId: 'appendix-left-end', offset: 3 }
    )).toBeNull()
  })
})
