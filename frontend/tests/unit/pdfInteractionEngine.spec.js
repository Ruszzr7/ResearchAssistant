import { describe, expect, it, vi } from 'vitest'
import {
  canonicalRange,
  denormalizePagePoint,
  normalizePageRect,
} from '@/services/pdfInteractionEngine'
import { PdfiumInteractionEngine } from '@/services/pdfiumInteractionEngine'

const task = value => ({ toPromise: () => Promise.resolve(value) })

function mockEngine() {
  const page = { index: 0, size: { width: 600, height: 800 }, rotation: 0 }
  return {
    page,
    engine: {
      openDocumentUrl: vi.fn(() => task({ id: 'paper-1', pageCount: 1, pages: [page] })),
      getPageGeometry: vi.fn(() => task({ runs: [{
        charStart: 0,
        rect: { x: 60, y: 80, width: 100, height: 20 },
        fontSize: 12,
        glyphs: Array.from({ length: 5 }, (_, index) => ({
          x: 60 + index * 10,
          y: 80,
          width: 10,
          height: 20,
          tightX: 60 + index * 10,
          tightY: 80,
          tightWidth: 10,
          tightHeight: 20,
          flags: 0,
        })),
      }] })),
      getPageTextRuns: vi.fn(() => task({ runs: [{ text: 'alpha', charIndex: 0, charCount: 5 }] })),
      getTextSlices: vi.fn(() => task(['alpha'])),
      searchAllPages: vi.fn(() => task({ results: [{
        pageIndex: 0,
        charIndex: 0,
        charCount: 5,
        rects: [{ origin: { x: 60, y: 80 }, size: { width: 50, height: 20 } }],
        context: { before: '', match: 'alpha', after: '' },
      }] })),
      closeDocument: vi.fn(() => task(true)),
      destroy: vi.fn(() => task(true)),
    },
  }
}

describe('PDF interaction engine contract', () => {
  it('normalizes engine geometry without depending on viewer scale', () => {
    expect(normalizePageRect(
      { origin: { x: 60, y: 80 }, size: { width: 120, height: 160 } },
      { width: 600, height: 800 },
    )).toEqual({ x: 0.1, y: 0.1, width: 0.2, height: 0.2 })
    expect(denormalizePagePoint({ x: 0.25, y: 0.5 }, { width: 600, height: 800 }))
      .toEqual({ x: 150, y: 400 })
    expect(canonicalRange(9, 2)).toEqual({ from: 2, to: 9 })
  })

  it('opens once, caches page facts, and returns engine-native anchors', async () => {
    const mocked = mockEngine()
    const adapter = new PdfiumInteractionEngine({
      engineFactory: vi.fn(async () => mocked.engine),
      wasmUrl: '/pdfium.wasm',
    })
    await adapter.open({ id: 'paper-1', url: '/paper.pdf' })
    await adapter.getPage(0)
    await adapter.getPage(0)
    expect(mocked.engine.getPageGeometry).toHaveBeenCalledTimes(1)

    const hit = await adapter.hitTest(0, { x: 0.11, y: 0.11 })
    expect(hit).toEqual({ pageIndex: 0, charIndex: 0 })
    const selection = await adapter.select(0, 4, 0)
    expect(selection).toMatchObject({
      text: 'alpha', charStart: 0, charEnd: 4, charCount: 5, source: 'PDFIUM',
    })
    expect(selection.rects[0]).toMatchObject({ x: 0.1, y: 0.1 })

    const results = await adapter.search('alpha')
    expect(results[0]).toMatchObject({ pageIndex: 0, charStart: 0, charEnd: 4 })
    await adapter.close()
    expect(mocked.engine.destroy).toHaveBeenCalledOnce()
  })
})
