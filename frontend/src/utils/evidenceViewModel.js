/**
 * Convert the backend evidence contract into the shape consumed by the PDF UI.
 * The backend quote is only a short citation preview; fullText and every locator
 * are retained so neither the evidence panel nor navigation silently loses a
 * wrapped line or a second page/rectangle.
 */
export function mapAgentEvidenceItem(item = {}) {
  const quote = String(item.quote ?? '').trim()
  const explicitFullText = item.fullText == null ? '' : String(item.fullText).trim()
  const fullText = String(explicitFullText || item.text || quote).trim()
  const fullTextAvailable = Boolean(explicitFullText)
  const rawLocators = Array.isArray(item.locators) && item.locators.length
    ? item.locators
    : item.locator ? [item.locator] : []
  const locators = rawLocators.map((raw, index) => {
    const rawContentRects = Array.isArray(raw?.contentRects) && raw.contentRects.length
      ? raw.contentRects : raw?.rects
    const rawFocusRects = Array.isArray(raw?.focusRects) && raw.focusRects.length
      ? raw.focusRects : raw?.rects
    const rects = validBoxes(rawContentRects)
    const focusRects = validBoxes(rawFocusRects?.length ? rawFocusRects : rects)
    const pageNumber = Number(raw?.pageNumber ?? raw?.page ?? item.page)
    const targetText = String(raw?.targetText ?? '').trim() || fullText
    return {
      ...raw,
      locatorId: raw?.locatorId || `locator-${index + 1}`,
      pageNumber: Number.isInteger(pageNumber) ? pageNumber : null,
      page: Number.isInteger(pageNumber) ? pageNumber : null,
      targetText,
      targetBoxes: rects,
      contentBoxes: rects,
      focusBoxes: focusRects,
      rects,
      targetBbox: unionBoundingBoxes(rects),
      focusBbox: unionBoundingBoxes(focusRects),
      precision: raw?.precision || 'BLOCK',
    }
  }).filter(locator => Number.isInteger(locator.pageNumber) && locator.pageNumber > 0)
  const first = locators[0] || {}
  const primaryBoxes = first.targetBoxes || []
  const primaryFocusBoxes = first.focusBoxes || primaryBoxes
  const contentType = String(item.contentType || 'TEXT').toUpperCase()
  const textFormat = String(item.textFormat || 'PLAIN_TEXT').toUpperCase()
  const evidenceKey = item.evidenceKey || physicalEvidenceKey(item, locators, quote || fullText)
  const mapped = {
    ...item,
    evidenceId: item.evidenceId || item.sourceObjectId || item.evidenceKey || '',
    sourceObjectId: item.sourceObjectId || item.evidenceId || '',
    evidenceKey,
    paperId: item.paperId ?? null,
    page: first.pageNumber ?? (Number.isInteger(Number(item.page)) ? Number(item.page) : null),
    pages: [...new Set(locators.map(locator => locator.pageNumber))],
    text: fullText || quote,
    quote,
    fullText: fullText || quote,
    fullTextAvailable,
    contentType,
    textFormat,
    textReliable: item.textReliable !== false,
    formulaNumber: item.formulaNumber || '',
    formulaNumbers: Array.isArray(item.formulaNumbers) ? item.formulaNumbers : [],
    locators,
    locator: {
      ...(first || item.locator || {}),
      pageNumber: first.pageNumber,
      targetText: first.targetText || fullText || quote,
      targetBoxes: primaryBoxes,
      contentBoxes: first.contentBoxes || primaryBoxes,
      focusBoxes: primaryFocusBoxes,
      targetBbox: first.targetBbox || unionBoundingBoxes(primaryBoxes),
      focusBbox: first.focusBbox || unionBoundingBoxes(primaryFocusBoxes),
      precision: first.precision || item.locator?.precision,
      formulaNumber: item.formulaNumber || '',
      formulaNumbers: Array.isArray(item.formulaNumbers) ? item.formulaNumbers : [],
    },
  }
  return mapped
}

function physicalEvidenceKey(item, locators, text) {
  if (!locators.length) return ''
  const geometry = locators.map(locator => {
    const boxes = validBoxes(locator.targetBoxes || locator.rects || [])
      .map(box => [box.x, box.y, box.width, box.height]
        .map(value => Number(value).toFixed(6)).join(','))
      .sort().join(';')
    return `${locator.pageNumber}:${boxes}`
  }).sort().join('|')
  const normalizedText = String(text || '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '')
  return `physical:${item.paperId ?? ''}:${normalizedText}:${geometry}`
}

export function mapAgentEvidenceList(items = []) {
  return (Array.isArray(items) ? items : []).map(mapAgentEvidenceItem)
}

export function evidenceLocators(item = {}) {
  if (Array.isArray(item.locators) && item.locators.length) return item.locators
  return item.locator ? [item.locator] : []
}

export function validBoxes(boxes) {
  return (boxes || []).filter(box => Number.isFinite(Number(box?.x))
    && Number.isFinite(Number(box?.y)) && Number(box?.width) > 0 && Number(box?.height) > 0)
}

/**
 * 选择证据框时，版面解析器返回的 locator 几何是唯一的完整性来源。
 * PDFium 的文本搜索可能只命中长证据的前缀或其中一行，因此搜索框只能
 * 作为没有可靠 locator 时的降级路径，不能覆盖完整 locator。
 */
export function selectEvidenceFocusBoxes({ locatorBoxes = [], exactBoxes = [], fallbackBox = null } = {}) {
  const complete = validBoxes(locatorBoxes)
  if (complete.length) return complete
  const exact = validBoxes(exactBoxes)
  if (exact.length) return exact
  return validBoxes(fallbackBox ? [fallbackBox] : [])
}

export function unionBoundingBoxes(boxes) {
  const valid = validBoxes(boxes)
  if (!valid.length) return null
  const left = Math.min(...valid.map(box => Number(box.x)))
  const top = Math.min(...valid.map(box => Number(box.y)))
  const right = Math.max(...valid.map(box => Number(box.x) + Number(box.width)))
  const bottom = Math.max(...valid.map(box => Number(box.y) + Number(box.height)))
  const normalized = value => Number(value.toFixed(6))
  return {
    x: normalized(left), y: normalized(top),
    width: normalized(right - left), height: normalized(bottom - top),
  }
}
