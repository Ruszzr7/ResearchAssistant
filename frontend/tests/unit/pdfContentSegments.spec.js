import { describe, expect, it } from 'vitest'
import { segmentPdfSelection } from '@/utils/pdfContentSegments.js'

const pageSize = { width: 600, height: 800 }
const run = (charStart, text, x, font = 'TimesNewRomanPSMT') => ({
  charStart,
  charEnd: charStart + text.length - 1,
  text,
  rect: { x, y: 0.55, width: Math.max(0.02, text.length * 0.008), height: 0.018 },
  font: { name: font, familyName: font },
})

describe('PDFium selection content segmentation', () => {
  it('separates inline math fonts from surrounding prose', () => {
    const segments = segmentPdfSelection([
      run(0, 'where ', 0.54),
      run(6, 'p_c ∈ C', 0.60, 'CMMI10'),
      run(13, ' are the precoders', 0.70),
    ], pageSize)

    expect(segments.map(segment => segment.type)).toEqual(['TEXT', 'INLINE_MATH', 'TEXT'])
    expect(segments[1]).toMatchObject({ charStart: 6, charEnd: 12, text: 'p_c ∈ C' })
  })

  it('marks a centered math-rich run as display math', () => {
    const segments = segmentPdfSelection([{
      ...run(20, 'x = ∑ p_k s_k', 0.25, 'STIXTwoMath'),
      rect: { x: 0.25, y: 0.45, width: 0.5, height: 0.035 },
    }], pageSize)
    expect(segments).toHaveLength(1)
    expect(segments[0].type).toBe('DISPLAY_MATH')
  })

  it('does not classify ordinary prose containing a hyphen as math', () => {
    expect(segmentPdfSelection([run(0, 'finite-blocklength transmission', 0.08)], pageSize)[0].type)
      .toBe('TEXT')
  })

  it('drops PDF math-font runs that contain only layout whitespace', () => {
    const segments = segmentPdfSelection([
      run(10, 'E', 0.54, 'MSBM10'),
      run(11, '\t\r\n', 0.56, 'CMEX10'),
      run(14, 'ssH', 0.58, 'CMBX10'),
    ], pageSize)

    expect(segments.flatMap(segment => segment.text)).not.toContain('\t\r\n')
    expect(segments.every(segment => segment.text.trim())).toBe(true)
  })
})
