import { describe, expect, it, vi } from 'vitest'
import {
  createPdfSelectionPreview,
  selectionNeedsVisualFallback,
  selectionPreviewBounds,
} from '@/utils/pdfSelectionPreview.js'

const quad = (x, y, width, height) => ({
  x1: x, y1: y + height,
  x2: x + width, y2: y + height,
  x3: x + width, y3: y,
  x4: x, y4: y,
})

describe('PDF selection source preview', () => {
  it('crops the union of selected lines with bounded padding', () => {
    const bounds = selectionPreviewBounds([
      quad(0.52, 0.40, 0.35, 0.02),
      quad(0.52, 0.44, 0.40, 0.02),
    ])
    expect(bounds.x).toBeCloseTo(0.512)
    expect(bounds.y).toBeCloseTo(0.392)
    expect(bounds.width).toBeCloseTo(0.416)
    expect(bounds.height).toBeCloseTo(0.076)
  })

  it('creates one local image without changing the source canvas', () => {
    const drawImage = vi.fn()
    const target = {
      width: 0,
      height: 0,
      getContext: () => ({ drawImage }),
      toDataURL: () => 'data:image/png;base64,preview',
    }
    const source = { width: 1200, height: 1600 }
    const preview = createPdfSelectionPreview(source, [quad(0.5, 0.4, 0.4, 0.1)], {
      maxWidth: 300,
      canvasFactory: () => target,
    })

    expect(preview.dataUrl).toBe('data:image/png;base64,preview')
    expect(preview.width).toBe(300)
    expect(preview.height).toBeGreaterThan(0)
    expect(drawImage).toHaveBeenCalledOnce()
    expect(source).toEqual({ width: 1200, height: 1600 })
  })

  it('uses visual fallback for any math or damaged character stream', () => {
    expect(selectionNeedsVisualFallback([{ type: 'INLINE_MATH' }], {})).toBe(true)
    expect(selectionNeedsVisualFallback([{ type: 'TEXT' }], { hasExtractionIssues: true })).toBe(true)
    expect(selectionNeedsVisualFallback([{ type: 'TEXT' }], {})).toBe(false)
  })
})
