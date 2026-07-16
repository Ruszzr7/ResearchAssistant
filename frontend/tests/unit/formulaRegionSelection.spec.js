import { describe, expect, it } from 'vitest'
import { formulaRegionSvgRect, normalizedFormulaRegion } from '@/utils/formulaRegionSelection.js'

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
    expect(formulaRegionSvgRect(
      { x: 0.2, y: 0.25, width: 0.5, height: 0.1 }, 600, 800,
    )).toEqual({ x: 120, y: 200, width: 300, height: 80 })
  })
})
