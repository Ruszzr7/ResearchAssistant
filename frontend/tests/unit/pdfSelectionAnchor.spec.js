import { describe, expect, it } from 'vitest'
import {
  boundingBoxToViewportQuad,
  normalizePdfiumContentSegments,
  selectionQuadsToBoxes,
  selectionToAnchorPayload
} from '@/utils/pdfSelectionAnchor.js'

function quad(x1, top, x2, bottom) {
  return { x1, y1: bottom, x2, y2: bottom, x3: x2, y3: top, x4: x1, y4: top }
}

describe('PDF selection anchor geometry', () => {
  it('merges adjacent word quads on one visual line', () => {
    const boxes = selectionQuadsToBoxes([
      quad(0.10, 0.20, 0.18, 0.22),
      quad(0.185, 0.20, 0.27, 0.22),
      quad(0.10, 0.24, 0.20, 0.26)
    ])

    expect(boxes).toHaveLength(2)
    expect(boxes[0].x).toBeCloseTo(0.10)
    expect(boxes[0].y).toBeCloseTo(0.20)
    expect(boxes[0].width).toBeCloseTo(0.17)
    expect(boxes[0].height).toBeCloseTo(0.02)
    expect(boxes[1].x).toBeCloseTo(0.10)
    expect(boxes[1].y).toBeCloseTo(0.24)
    expect(boxes[1].width).toBeCloseTo(0.10)
    expect(boxes[1].height).toBeCloseTo(0.02)
  })

  it('does not merge two distant columns on the same baseline', () => {
    const boxes = selectionQuadsToBoxes([
      quad(0.08, 0.30, 0.42, 0.32),
      quad(0.56, 0.30, 0.90, 0.32)
    ])

    expect(boxes).toHaveLength(2)
  })

  it('builds a bounded backend payload from the active page group', () => {
    const payload = selectionToAnchorPayload({
      text: 'selected paragraph',
      groups: [{ pageNum: 13, quads: [quad(0.1, 0.2, 0.4, 0.23)] }]
    })

    expect(payload.page).toBe(13)
    expect(payload.anchorText).toBe('selected paragraph')
    expect(payload.boxes).toHaveLength(1)
    expect(payload.boxes[0].x).toBeCloseTo(0.1)
    expect(payload.boxes[0].y).toBeCloseTo(0.2)
    expect(payload.boxes[0].width).toBeCloseTo(0.3)
    expect(payload.boxes[0].height).toBeCloseTo(0.03)
  })

  it('sends the versioned PDF.js character anchor with geometry', () => {
    const payload = selectionToAnchorPayload({
      text: 'selected',
      groups: [{ pageNum: 2, quads: [quad(0.1, 0.2, 0.3, 0.23)] }],
      textAnchor: {
        version: 1, page: 2, documentFingerprint: 'fingerprint', textMapVersion: 1,
        ranges: [{ itemIndex: 7, spanIndex: 0, startOffset: 2, endOffset: 10 }],
      },
    })

    expect(payload.clientTextAnchor).toEqual({
      version: 1, page: 2, documentFingerprint: 'fingerprint', textMapVersion: 1,
      ranges: [{ itemIndex: 7, spanIndex: 0, startOffset: 2, endOffset: 10 }],
    })
  })

  it('sends a PDFium character range beside normalized geometry', () => {
    const payload = selectionToAnchorPayload({
      text: 'where p_c belongs to C',
      groups: [{ pageNum: 3, quads: [quad(0.54, 0.56, 0.92, 0.61)] }],
      textAnchor: {
        version: 2,
        engine: 'PDFIUM',
        page: 3,
        documentFingerprint: 'pdf-fingerprint',
        charStart: 840,
        charEnd: 862,
      },
    })

    expect(payload.clientTextAnchor).toEqual({
      version: 2,
      engine: 'PDFIUM',
      page: 3,
      documentFingerprint: 'pdf-fingerprint',
      textMapVersion: 1,
      charStart: 840,
      charEnd: 862,
      ranges: [],
      contentSegments: [],
    })
  })

  it('omits blank auxiliary PDFium segments without losing the character anchor', () => {
    const payload = selectionToAnchorPayload({
      text: 'ensures E ssH = I',
      groups: [{ pageNum: 3, quads: [quad(0.54, 0.56, 0.92, 0.61)] }],
      textAnchor: {
        version: 2,
        engine: 'PDFIUM',
        page: 3,
        charStart: 5092,
        charEnd: 5108,
        contentSegments: [
          { type: 'INLINE_MATH', charStart: 5092, charEnd: 5094, text: 'E' },
          { type: 'INLINE_MATH', charStart: 5103, charEnd: 5105, text: '\t\r\n' },
        ],
      },
    })

    expect(payload.clientTextAnchor).toMatchObject({
      charStart: 5092,
      charEnd: 5108,
      contentSegments: [{ text: 'E' }],
    })
  })

  it('splits oversized segment text while preserving the inclusive character range', () => {
    const text = 'x'.repeat(2401)
    const segments = normalizePdfiumContentSegments([
      {
        type: 'TEXT',
        charStart: 100,
        charEnd: 2500,
        text,
        rect: { x: 0.1, y: 0.2, width: 0.7, height: 0.1 },
      },
    ], 100, 2500)

    expect(segments).toHaveLength(3)
    expect(segments.every(segment => segment.text.length <= 1200)).toBe(true)
    expect(segments[0]).toMatchObject({ charStart: 100, charEnd: 1299 })
    expect(segments[2]).toMatchObject({ charStart: 2500, charEnd: 2500 })
  })

  it('compacts dense inline-math runs to the API limit without dropping the tail', () => {
    const source = Array.from({ length: 130 }, (_, index) => ({
      type: index % 2 ? 'INLINE_MATH' : 'TEXT',
      charStart: 5000 + index,
      charEnd: 5000 + index,
      text: index % 2 ? 'μ' : 'a',
      fonts: [index % 2 ? 'CMMI10' : 'Times'],
      rect: { x: 0.55, y: 0.2 + index * 0.0001, width: 0.005, height: 0.01 },
    }))

    const segments = normalizePdfiumContentSegments(source, 5000, 5129)

    expect(segments).toHaveLength(100)
    expect(segments[0].charStart).toBe(5000)
    expect(segments.at(-1).charEnd).toBe(5129)
    expect(segments.some(segment => segment.type === 'INLINE_MATH')).toBe(true)
    expect(segments.every(segment => segment.text.length <= 1200)).toBe(true)
  })

  it('removes invalid controls before request validation', () => {
    const segments = normalizePdfiumContentSegments([
      { type: 'INLINE_MATH', charStart: 3, charEnd: 6, text: `x\u0000+y` },
    ], 3, 6)

    expect(segments).toHaveLength(1)
    expect(segments[0].text).toBe('x+y')
  })

  it('converts a top-left backend bbox into the viewer quad orientation', () => {
    expect(boundingBoxToViewportQuad({ x: 0.1, y: 0.2, width: 0.3, height: 0.05 }))
      .toEqual({
        x1: 0.1, y1: 0.25,
        x2: 0.4, y2: 0.25,
        x3: 0.4, y3: 0.2,
        x4: 0.1, y4: 0.2
      })
  })

})
