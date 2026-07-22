import { createPdfiumEngine } from '@embedpdf/engines/pdfium-direct-engine'
import pdfiumWasmUrl from '@embedpdf/pdfium/pdfium.wasm?url'
import { glyphAt, rectsWithinSlice } from '@embedpdf/plugin-selection'
import {
  PdfInteractionEngine,
  canonicalRange,
  denormalizePagePoint,
  normalizePageRect,
} from './pdfInteractionEngine'

const taskResult = task => task.toPromise()

export class PdfiumInteractionEngine extends PdfInteractionEngine {
  constructor({ engineFactory = createPdfiumEngine, wasmUrl = pdfiumWasmUrl } = {}) {
    super()
    this.engineFactory = engineFactory
    this.wasmUrl = wasmUrl
    this.engine = null
    this.document = null
    this.pageCache = new Map()
  }

  get pageCount() {
    return this.document?.pageCount || 0
  }

  async open({ id, url }) {
    await this.close()
    this.engine = await this.engineFactory(this.wasmUrl, { fontFallback: null })
    this.document = await taskResult(this.engine.openDocumentUrl(
      { id: String(id), url },
      { mode: 'auto', normalizeRotation: true },
    ))
    return {
      id: this.document.id,
      pageCount: this.document.pageCount,
      pages: this.document.pages.map(page => ({
        index: page.index,
        size: { ...page.size },
        rotation: page.rotation,
      })),
    }
  }

  requireDocument() {
    if (!this.engine || !this.document) {
      throw new Error('PDF interaction engine is not open')
    }
  }

  requirePage(pageIndex) {
    this.requireDocument()
    const page = this.document.pages[pageIndex]
    if (!page) throw new RangeError(`PDF page ${pageIndex} does not exist`)
    return page
  }

  async getPage(pageIndex) {
    const cached = this.pageCache.get(pageIndex)
    if (cached) return cached
    const page = this.requirePage(pageIndex)
    const pending = Promise.all([
      taskResult(this.engine.getPageGeometry(this.document, page)),
      taskResult(this.engine.getPageTextRuns(this.document, page)),
    ]).then(([geometry, textRuns]) => ({
      index: page.index,
      size: { ...page.size },
      geometry,
      textRuns: textRuns.runs,
    })).catch(error => {
      this.pageCache.delete(pageIndex)
      throw error
    })
    this.pageCache.set(pageIndex, pending)
    return pending
  }

  async hitTest(pageIndex, normalizedPoint) {
    const page = await this.getPage(pageIndex)
    const point = denormalizePagePoint(normalizedPoint, page.size)
    const charIndex = glyphAt(page.geometry, point, 1.5)
    return charIndex < 0 ? null : { pageIndex, charIndex }
  }

  async select(pageIndex, from, to) {
    const page = await this.getPage(pageIndex)
    const range = canonicalRange(from, to)
    const overlappingRuns = page.textRuns.filter(run => (
      run.charIndex + run.charCount - 1 >= range.from && run.charIndex <= range.to
    )).map(run => ({
      run,
      charStart: Math.max(range.from, run.charIndex),
      charEnd: Math.min(range.to, run.charIndex + run.charCount - 1),
    }))
    const slices = [{
      pageIndex,
      charIndex: range.from,
      charCount: range.to - range.from + 1,
    }, ...overlappingRuns.map(item => ({
      pageIndex,
      charIndex: item.charStart,
      charCount: item.charEnd - item.charStart + 1,
    }))]
    const sliceTexts = await taskResult(this.engine.getTextSlices(this.document, slices))
    const text = sliceTexts[0] || ''
    const rects = rectsWithinSlice(page.geometry, range.from, range.to)
      .map(rect => normalizePageRect(rect, page.size))
      .filter(Boolean)
    return {
      pageIndex,
      charStart: range.from,
      charEnd: range.to,
      charCount: range.to - range.from + 1,
      text,
      rects,
      runs: overlappingRuns.map((item, index) => ({
        charStart: item.charStart,
        charEnd: item.charEnd,
        text: sliceTexts[index + 1] || '',
        rect: normalizePageRect(item.run.rect, page.size),
        font: { ...item.run.font },
        fontSize: item.run.fontSize,
      })).filter(run => run.rect),
      pageSize: { ...page.size },
      source: 'PDFIUM',
    }
  }

  async search(query) {
    this.requireDocument()
    const keyword = String(query || '').trim()
    if (!keyword) return []
    const { results } = await taskResult(this.engine.searchAllPages(this.document, keyword))
    return results.map(result => {
      const page = this.requirePage(result.pageIndex)
      return {
        pageIndex: result.pageIndex,
        charStart: result.charIndex,
        charEnd: result.charIndex + result.charCount - 1,
        charCount: result.charCount,
        rects: result.rects.map(rect => normalizePageRect(rect, page.size)).filter(Boolean),
        context: result.context,
        source: 'PDFIUM',
      }
    })
  }

  async close() {
    const engine = this.engine
    const document = this.document
    this.pageCache.clear()
    this.document = null
    this.engine = null
    if (engine && document) {
      try {
        await taskResult(engine.closeDocument(document))
      } finally {
        await taskResult(engine.destroy())
      }
    } else if (engine) {
      await taskResult(engine.destroy())
    }
  }
}

export const createPdfInteractionEngine = options => new PdfiumInteractionEngine(options)
