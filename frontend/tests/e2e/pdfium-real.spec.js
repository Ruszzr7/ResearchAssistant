import { expect, test } from '@playwright/test'
import path from 'node:path'

test('PDFium preserves real two-column math-dense text facts', async ({ page }) => {
  const sample = process.env.RA_PDF_INTERACTION_SAMPLE
  test.skip(!sample, 'Set RA_PDF_INTERACTION_SAMPLE to a private real PDF')
  const absolutePath = path.resolve(sample).replaceAll('\\', '/')
  const pdfUrl = `/@fs/${absolutePath}`

  await page.goto(`/tests/harness/pdfium-real.html?pdf=${encodeURIComponent(pdfUrl)}`)
  await expect(page.locator('html')).toHaveAttribute('data-status', 'ready', { timeout: 30_000 })
  const result = JSON.parse(await page.locator('#result').textContent())
  expect(result.pageCount).toBe(16)
  expect(result.coefficientPages).toEqual(expect.arrayContaining([1, 3, 7, 10, 12]))
  expect(result.pageThreePrecoderText.toLowerCase()).toContain('precoders')
  expect(result.pageThreePrecoderRects).toBeGreaterThan(0)
  expect(result.pageThreeDenseMathSegments).toBeGreaterThanOrEqual(5)
  expect(result.pageThreeDenseHasCoefficient).toBe(true)
  expect(result.pageThreeDenseMinimumX).toBeGreaterThan(0.5)
  expect(result.pageThreeHitDelta).toBeLessThanOrEqual(10)
})
