import { describe, expect, it } from 'vitest'
import { resizeTextAnnotationQuads } from '@/utils/pdfAnnotation.js'

const multiLineQuads = [
  { x1: 0.2, y1: 0.4, x2: 0.8, y2: 0.4, x3: 0.8, y3: 0.36, x4: 0.2, y4: 0.36 },
  { x1: 0.1, y1: 0.48, x2: 0.5, y2: 0.48, x3: 0.5, y3: 0.44, x4: 0.1, y4: 0.44 }
]

describe('PDF text annotation range resizing', () => {
  it('moves only the first quad start handle for a multi-line selection', () => {
    const result = resizeTextAnnotationQuads(multiLineQuads, 'start', 0.35)

    expect(result.changed).toBe(true)
    expect(result.quads[0].x1).toBe(0.35)
    expect(result.quads[0].x4).toBe(0.35)
    expect(result.quads[1]).toEqual(multiLineQuads[1])
    expect(multiLineQuads[0].x1).toBe(0.2)
  })

  it('keeps a minimum width when an end handle is dragged past its start', () => {
    const result = resizeTextAnnotationQuads(multiLineQuads, 'end', 0.02)

    expect(result.changed).toBe(true)
    expect(result.quads[1].x2).toBeCloseTo(0.106)
    expect(result.quads[1].x3).toBeCloseTo(0.106)
  })
})
