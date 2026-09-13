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

  /**
   * Select only the glyphs whose geometry falls inside the trusted source
   * rectangles.  A plain PDFium character range is not sufficient for a
   * two-column page: the character order can cross the gutter even when the
   * source block is confined to one column.  Keeping the geometry filter here
   * makes agent-created markers follow the same physical selection semantics
   * as a mouse drag without changing the ordinary selection path above.
   */
  async selectWithinRects(pageIndex, normalizedRects) {
    const page = await this.getPage(pageIndex)
    const targets = (normalizedRects || []).map(normalizedRect).filter(Boolean)
    if (!targets.length) return null

    const selectedByChar = new Map()
    targets.forEach((target, targetIndex) => {
      ;(page.geometry?.runs || []).forEach((run, runIndex) => {
        const charStart = Number(run?.charStart)
        if (!Number.isInteger(charStart) || !Array.isArray(run?.glyphs)) return
        run.glyphs.forEach((glyph, glyphIndex) => {
          const charIndex = charStart + glyphIndex
          if (!Number.isInteger(charIndex) || selectedByChar.has(charIndex)) return
          const rect = normalizeGlyphRect(glyph, page.size)
          if (!rect) return
          const intersection = intersectNormalizedRects(rect, target)
          const glyphArea = rect.width * rect.height
          const centerInside = pointInRect(
            rect.x + rect.width / 2,
            rect.y + rect.height / 2,
            target,
          )
          if (!centerInside && (!intersection || !glyphArea
            || intersection.width * intersection.height / glyphArea < 0.35)) return
          selectedByChar.set(charIndex, {
            charIndex,
            rect: intersection || rect,
            targetIndex,
            runIndex,
            renderable: glyph.flags !== 2 && glyph.isSpace !== true,
          })
        })
      })
    })

    if (!selectedByChar.size) return null

    const selected = [...selectedByChar.values()]
    const ranges = []
    const rangeGlyphs = []
    const byTarget = targets.map(() => [])
    selected.forEach(glyph => byTarget[glyph.targetIndex].push(glyph))
    byTarget.forEach((glyphs, targetIndex) => {
      const ordered = glyphs.slice().sort((left, right) => left.charIndex - right.charIndex)
      let group = []
      const flush = () => {
        const renderable = group.filter(glyph => glyph.renderable && glyph.rect)
        if (renderable.length) {
          ranges.push({ start: group[0].charIndex, end: group[group.length - 1].charIndex, targetIndex })
          rangeGlyphs.push(renderable)
        }
        group = []
      }
      ordered.forEach(glyph => {
        const previous = group[group.length - 1]
        if (previous && glyph.charIndex !== previous.charIndex + 1) flush()
        group.push(glyph)
      })
      flush()
    })
    if (!ranges.length) return null

    const slices = ranges.map(range => ({
      pageIndex,
      charIndex: range.start,
      charCount: range.end - range.start + 1,
    }))
    const sliceTexts = await taskResult(this.engine.getTextSlices(this.document, slices))
    const runs = ranges.map((range, index) => ({
      charStart: range.start,
      charEnd: range.end,
      text: sliceTexts?.[index] || '',
      rect: unionNormalizedRects(rangeGlyphs[index].map(glyph => glyph.rect)),
    })).filter(run => run.rect)
    // Character ranges may be split at whitespace or by PDF text runs even
    // though their glyphs form one visual line.  Keep those ranges for text
    // identity, but compact the display/persistence geometry per trusted
    // source rectangle.  Otherwise a large algorithm block produces hundreds
    // of tiny quads and can overflow the annotation JSON column.
    const rectEntries = visualGlyphRects(
      selected.filter(glyph => glyph.renderable && glyph.rect),
    ).map(rect => ({ rect }))
    const rects = rectEntries
      .sort((left, right) => left.rect.y - right.rect.y || left.rect.x - right.rect.x)
      .map(entry => entry.rect)
    const text = (sliceTexts || []).filter(value => String(value || '').trim()).join('\n')
    return {
      pageIndex,
      charStart: Math.min(...ranges.map(range => range.start)),
      charEnd: Math.max(...ranges.map(range => range.end)),
      charCount: ranges.reduce((count, range) => count + range.end - range.start + 1, 0),
      text,
      rects,
      runs,
      ranges,
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

function normalizedRect(rect) {
  if (!rect) return null
  const x = Number(rect.x)
  const y = Number(rect.y)
  const width = Number(rect.width)
  const height = Number(rect.height)
  if (![x, y, width, height].every(Number.isFinite)
      || width <= 0 || height <= 0 || x < 0 || y < 0 || x + width > 1.000001
      || y + height > 1.000001) return null
  return { x, y, width, height }
}

function normalizeGlyphRect(glyph, pageSize) {
  if (!glyph || !pageSize?.width || !pageSize?.height) return null
  const x = Number(glyph.x)
  const y = Number(glyph.y)
  const width = Number(glyph.width)
  const height = Number(glyph.height)
  if (![x, y, width, height].every(Number.isFinite) || width <= 0 || height <= 0) return null
  return normalizedRect({
    x: x / pageSize.width,
    y: y / pageSize.height,
    width: width / pageSize.width,
    height: height / pageSize.height,
  })
}

function pointInRect(x, y, rect) {
  return x >= rect.x && x <= rect.x + rect.width
    && y >= rect.y && y <= rect.y + rect.height
}

function intersectNormalizedRects(first, second) {
  const x = Math.max(first.x, second.x)
  const y = Math.max(first.y, second.y)
  const right = Math.min(first.x + first.width, second.x + second.width)
  const bottom = Math.min(first.y + first.height, second.y + second.height)
  return right > x && bottom > y
    ? { x, y, width: right - x, height: bottom - y }
    : null
}

function unionNormalizedRects(rects) {
  const valid = (rects || []).filter(Boolean)
  if (!valid.length) return null
  const x = Math.min(...valid.map(rect => rect.x))
  const y = Math.min(...valid.map(rect => rect.y))
  const right = Math.max(...valid.map(rect => rect.x + rect.width))
  const bottom = Math.max(...valid.map(rect => rect.y + rect.height))
  return { x, y, width: right - x, height: bottom - y }
}

function verticalOverlap(first, second) {
  return Math.max(0, Math.min(first.y + first.height, second.y + second.height)
    - Math.max(first.y, second.y)) / Math.max(1e-6, Math.min(first.height, second.height))
}

function horizontalGap(first, second) {
  if (first.x + first.width >= second.x && second.x + second.width >= first.x) return 0
  return first.x + first.width < second.x
    ? second.x - (first.x + first.width)
    : first.x - (second.x + second.width)
}

function visualGlyphRects(glyphs) {
  const ordered = (glyphs || []).slice().sort((left, right) => (
    left.rect.y - right.rect.y || left.rect.x - right.rect.x || left.charIndex - right.charIndex
  ))
  const lines = []
  ordered.forEach(glyph => {
    const previous = lines[lines.length - 1]
    const sameLine = previous
      && verticalOverlap(previous, glyph.rect) >= 0.45
      // Merge words and split PDF text runs on one visual line, but keep a
      // two-column gutter as a hard boundary.
      && horizontalGap(previous, glyph.rect) <= Math.max(
        0.012,
        Math.min(0.032, Math.max(previous.height, glyph.rect.height) * 2),
      )
    if (sameLine) {
      const union = unionNormalizedRects([previous, glyph.rect])
      Object.assign(previous, union)
    } else {
      lines.push({ ...glyph.rect })
    }
  })
  return lines
}

export const createPdfInteractionEngine = options => new PdfiumInteractionEngine(options)
