import { describe, expect, it } from 'vitest'
import {
  boundingBoxToViewportQuad,
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
