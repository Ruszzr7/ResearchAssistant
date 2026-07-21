import { describe, expect, it } from 'vitest'
import golden from '../fixtures/pdf-content-golden.json'
import {
  buildFailedPdfPageTextMap,
  buildPdfPageTextMap,
  normalizePdfSearchNeedle,
  PDF_TEXT_MAP_STATUS,
  projectionRangeToItemRanges,
} from '@/utils/pdfTextMap.js'
import {
  denormalizeViewportRectangle,
  normalizeViewportRectangle,
  rectangleRoundtripError,
} from '@/utils/pdfCoordinates.js'

describe('PDF page text contract', () => {
  it('preserves original PDF.js indexes across whitespace-only items', () => {
    const fixture = golden.searchCases.find(testCase => (
      testCase.id === 'ieee-whitespace-preserves-original-item-index'
    ))
    const map = buildPdfPageTextMap(fixture.page, fixture.items)
    const needle = normalizePdfSearchNeedle(fixture.query)
    const start = map.normalizedText.indexOf(needle)
    const ranges = projectionRangeToItemRanges(map.charMap, start, start + needle.length)

    expect(map.status).toBe(PDF_TEXT_MAP_STATUS.READY)
    expect(map.runs).toHaveLength(fixture.items.length)
    expect(map.runs[1]).toMatchObject({ itemIndex: 1, rawText: ' ' })
    expect(ranges.map(range => range.itemIndex)).toEqual(fixture.expected.itemIndexes)
  })

  it('maps NFKC ligatures and split words back to their source items', () => {
    const fixture = golden.searchCases.find(testCase => testCase.id === 'split-word-hyphen-and-ligature')
    const map = buildPdfPageTextMap(fixture.page, fixture.items)
    const needle = normalizePdfSearchNeedle(fixture.query)
    const start = map.normalizedText.indexOf(needle)
    const ranges = projectionRangeToItemRanges(map.charMap, start, start + needle.length)

    expect(start).toBeGreaterThanOrEqual(0)
    expect(ranges.map(range => range.itemIndex)).toEqual(fixture.expected.itemIndexes)
  })

  it('distinguishes no text layer from extraction failure', () => {
    expect(buildPdfPageTextMap(1, []).status).toBe(PDF_TEXT_MAP_STATUS.NO_TEXT_LAYER)
    expect(buildFailedPdfPageTextMap(1).status).toBe(PDF_TEXT_MAP_STATUS.FAILED)
  })
})

describe('PDF coordinate contract', () => {
  it('roundtrips viewport and normalized rectangles within the golden tolerance', () => {
    const fixture = golden.formulaCases.find(testCase => testCase.id === 'viewport-rectangle-roundtrip')
    const normalized = normalizeViewportRectangle(fixture.cssRectangle, fixture.viewport)
    const restored = denormalizeViewportRectangle(normalized, fixture.viewport)

    expect(rectangleRoundtripError(normalized, fixture.expectedNormalizedBox))
      .toBeLessThanOrEqual(fixture.maximumRoundtripError)
    expect(rectangleRoundtripError(restored, fixture.cssRectangle))
      .toBeLessThanOrEqual(fixture.maximumRoundtripError)
  })

  it('keeps normalized regions stable when viewport scale changes', () => {
    const fixture = golden.formulaCases.find(testCase => testCase.id === 'zoom-does-not-change-normalized-region')
    fixture.viewports.forEach(viewport => {
      const rendered = denormalizeViewportRectangle(fixture.normalizedBox, viewport)
      const normalized = normalizeViewportRectangle(rendered, viewport)
      expect(rectangleRoundtripError(normalized, fixture.normalizedBox))
        .toBeLessThanOrEqual(fixture.maximumRoundtripError)
    })
  })
})
