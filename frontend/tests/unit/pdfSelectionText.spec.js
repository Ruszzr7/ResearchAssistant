import { describe, expect, it, vi } from 'vitest'
import { copyPdfSelectionText, normalizePdfSelectionText } from '@/utils/pdfSelectionText.js'

describe('PDF selection readable text', () => {
  it('turns visual PDF lines into one readable paragraph', () => {
    const result = normalizePdfSelectionText(
      'where p_c ∈ C\r\nNt×1\r\nare the precoders\r\npertainting to the common stream',
    )

    expect(result.readableText).toBe(
      'where p_c ∈ C Nt×1 are the precoders pertainting to the common stream',
    )
    expect(result.hadVisualLineBreaks).toBe(true)
    expect(result.hasExtractionIssues).toBe(false)
  })

  it('removes control and unmapped characters without inventing replacements', () => {
    const result = normalizePdfSelectionText('ensures E\r\n\b\r\nssH\r\n\t\r\n= I.\ufffd')

    expect(result.rawText).toContain('\b')
    expect(result.readableText).toBe('ensures E ssH = I.')
    expect(result.hasExtractionIssues).toBe(true)
    expect(result.removedCharacterCount).toBe(2)
    expect(result.readableText).not.toMatch(/[\u0000-\u001f\ufffd]/u)
  })

  it('preserves valid Unicode mathematics', () => {
    expect(normalizePdfSelectionText('0 ≤ μₖ ≤ 1').readableText).toBe('0 ≤ μₖ ≤ 1')
  })

  it('writes a PDFium-owned selection to the system copy event', () => {
    const setData = vi.fn()
    const preventDefault = vi.fn()

    expect(copyPdfSelectionText({ clipboardData: { setData }, preventDefault }, 'first\nsecond'))
      .toBe(true)
    expect(setData).toHaveBeenCalledWith('text/plain', 'first second')
    expect(preventDefault).toHaveBeenCalledOnce()
  })
})
