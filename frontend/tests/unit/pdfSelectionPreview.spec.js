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

  it('masks pixels outside the selected glyph rectangles', () => {
    const drawImage = vi.fn()
    const fillRect = vi.fn()
    const rect = vi.fn()
    const clip = vi.fn()
    const target = {
      width: 0,
      height: 0,
      getContext: () => ({
        drawImage,
        fillRect,
        save: vi.fn(),
        beginPath: vi.fn(),
        rect,
        clip,
        restore: vi.fn(),
      }),
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
    expect(preview.masked).toBe(true)
    expect(fillRect).toHaveBeenCalledWith(0, 0, preview.width, preview.height)
    expect(rect).toHaveBeenCalledOnce()
    expect(clip).toHaveBeenCalledOnce()
    expect(drawImage).toHaveBeenCalledOnce()
    expect(source).toEqual({ width: 1200, height: 1600 })
  })

  it('keeps tall formula glyphs while masking a nearby unselected line', () => {
    const rect = vi.fn()
    const target = {
      width: 0,
      height: 0,
      getContext: () => ({
        drawImage: vi.fn(),
        fillRect: vi.fn(),
        save: vi.fn(),
        beginPath: vi.fn(),
        rect,
        clip: vi.fn(),
        restore: vi.fn(),
      }),
      toDataURL: () => 'data:image/png;base64,formula',
    }
    const preview = createPdfSelectionPreview(
      { width: 1200, height: 1600 },
      [
        quad(0.10, 0.20, 0.65, 0.04),
        quad(0.42, 0.16, 0.10, 0.14),
      ],
      { canvasFactory: () => target },
    )

    expect(preview.masked).toBe(true)
    expect(rect).toHaveBeenCalledTimes(2)
    const maskBottom = Math.max(...rect.mock.calls.map(([, y, , height]) => y + height))
    expect(maskBottom).toBeLessThanOrEqual(preview.height)
  })

  it('uses visual fallback for any math or damaged character stream', () => {
    expect(selectionNeedsVisualFallback([{ type: 'INLINE_MATH' }], {})).toBe(true)
    expect(selectionNeedsVisualFallback([{ type: 'TEXT' }], { hasExtractionIssues: true })).toBe(true)
    expect(selectionNeedsVisualFallback([{ type: 'TEXT' }], {})).toBe(false)
  })
})
