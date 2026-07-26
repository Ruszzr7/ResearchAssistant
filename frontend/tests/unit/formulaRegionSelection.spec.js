import { describe, expect, it } from 'vitest'
import {
  createFormulaRegionPreview,
  formulaRegionSvgRect,
  normalizedFormulaRegion,
} from '@/utils/formulaRegionSelection.js'
import { vi } from 'vitest'

describe('formula region selection', () => {
  it('normalizes reverse drags and clamps them to the page', () => {
    expect(normalizedFormulaRegion(
      { x: 310, y: 250 },
      { x: 80, y: 70 },
      { left: 100, top: 50, width: 200, height: 400 },
    )).toEqual({ x: 0, y: 0.05, width: 1, height: 0.45 })
  })

  it('keeps the same normalized region at different zoom levels', () => {
    const first = normalizedFormulaRegion(
      { x: 120, y: 140 }, { x: 360, y: 220 },
      { left: 0, top: 0, width: 600, height: 800 },
    )
    const second = normalizedFormulaRegion(
      { x: 180, y: 210 }, { x: 540, y: 330 },
      { left: 0, top: 0, width: 900, height: 1200 },
    )
    expect(second).toEqual(first)
  })

  it('rejects accidental clicks and projects a valid box to SVG pixels', () => {
    expect(normalizedFormulaRegion(
      { x: 10, y: 10 }, { x: 14, y: 16 },
      { left: 0, top: 0, width: 600, height: 800 },
    )).toBeNull()
    const svgRect = formulaRegionSvgRect(
      { x: 0.2, y: 0.25, width: 0.5, height: 0.1 }, 600, 800,
    )
    expect(svgRect.x).toBeCloseTo(120)
    expect(svgRect.y).toBeCloseTo(200)
    expect(svgRect.width).toBeCloseTo(300)
    expect(svgRect.height).toBeCloseTo(80)
  })

  it('roundtrips a rotated viewport using its rendered dimensions', () => {
    const pageRect = { left: 40, top: 60, width: 800, height: 600, rotation: 90 }
    const normalized = normalizedFormulaRegion(
      { x: 560, y: 120 }, { x: 680, y: 300 }, pageRect,
    )

    expect(normalized).toEqual({ x: 0.65, y: 0.1, width: 0.15, height: 0.3 })
    const restored = formulaRegionSvgRect(normalized, pageRect.width, pageRect.height)
    expect(restored.x).toBeCloseTo(520)
    expect(restored.y).toBeCloseTo(60)
    expect(restored.width).toBeCloseTo(120)
    expect(restored.height).toBeCloseTo(180)
  })

  it('crops a bounded formula preview from the existing PDF canvas', () => {
    const drawImage = vi.fn()
    const fillRect = vi.fn()
    const target = {
      width: 0,
      height: 0,
      getContext: () => ({ drawImage, fillRect }),
      toDataURL: () => 'data:image/png;base64,formula',
    }

    const result = createFormulaRegionPreview(
      { width: 2400, height: 3200 },
      { x: 0.2, y: 0.3, width: 0.5, height: 0.08 },
      { maxWidth: 600, canvasFactory: () => target },
    )

    expect(result.dataUrl).toContain('image/png')
    expect(result.width).toBe(600)
    expect(result.height).toBeGreaterThan(0)
    expect(fillRect).toHaveBeenCalledOnce()
    expect(drawImage).toHaveBeenCalledOnce()
  })
})
