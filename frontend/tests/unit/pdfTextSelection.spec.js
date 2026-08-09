import { describe, expect, it } from 'vitest'
import { distanceToRect, isNearTextRect, isTextSelectionDrag } from '@/utils/pdfTextSelection.js'

const glyphRect = { left: 100, right: 180, top: 50, bottom: 70 }

describe('PDF native selection start hit testing', () => {
  it('accepts a pointer inside a rendered text glyph rectangle', () => {
    expect(isNearTextRect(120, 60, glyphRect)).toBe(true)
    expect(distanceToRect(120, 60, glyphRect)).toBe(0)
  })

  it('keeps a small tolerance at a glyph edge', () => {
    expect(isNearTextRect(96.5, 60, glyphRect)).toBe(true)
  })

  it('rejects a pointer in paragraph margin whitespace', () => {
    expect(isNearTextRect(95, 60, glyphRect)).toBe(false)
    expect(distanceToRect(95, 60, glyphRect)).toBe(5)
  })

  it('treats malformed rectangles as non-selectable', () => {
    expect(isNearTextRect(100, 60, { left: 100, right: 90, top: 50, bottom: 70 })).toBe(false)
  })

  it('requires an intentional drag instead of selecting a glyph on click', () => {
    expect(isTextSelectionDrag({ x: 100, y: 50 }, { x: 101, y: 51 })).toBe(false)
    expect(isTextSelectionDrag({ x: 100, y: 50 }, { x: 104, y: 50 })).toBe(true)
  })
})
