/**
 * Narrow, replaceable contract for PDF text interaction facts.
 * Rendering and product annotations intentionally stay outside this module.
 */
export class PdfInteractionEngine {
  async open(_source) {
    throw new Error('PdfInteractionEngine.open must be implemented')
  }

  get pageCount() {
    return 0
  }

  async getPage(_pageIndex) {
    throw new Error('PdfInteractionEngine.getPage must be implemented')
  }

  async hitTest(_pageIndex, _normalizedPoint) {
    throw new Error('PdfInteractionEngine.hitTest must be implemented')
  }

  async select(_pageIndex, _from, _to) {
    throw new Error('PdfInteractionEngine.select must be implemented')
  }

  async search(_query) {
    throw new Error('PdfInteractionEngine.search must be implemented')
  }

  async close() {}
}

export function normalizePageRect(rect, pageSize) {
  if (!rect || !pageSize?.width || !pageSize?.height) return null
  return {
    x: rect.origin.x / pageSize.width,
    y: rect.origin.y / pageSize.height,
    width: rect.size.width / pageSize.width,
    height: rect.size.height / pageSize.height,
  }
}

export function denormalizePagePoint(point, pageSize) {
  if (!point || !pageSize?.width || !pageSize?.height) return null
  return {
    x: point.x * pageSize.width,
    y: point.y * pageSize.height,
  }
}

export function canonicalRange(from, to) {
  const start = Math.max(0, Math.min(Number(from), Number(to)))
  const end = Math.max(start, Math.max(Number(from), Number(to)))
  return { from: start, to: end }
}
