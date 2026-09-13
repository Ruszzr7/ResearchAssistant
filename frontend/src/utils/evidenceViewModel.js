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
    // Keep the parser's physical rectangles for citations and page actions.  The
    // display geometry is a derived view only: adjacent lines from one source are
    // painted as one readable region without changing the trusted action target.
    const displayRects = mergeDisplayBoxes(rects)
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
      displayBoxes: displayRects,
      focusBoxes: focusRects,
      rects,
      targetBbox: unionBoundingBoxes(rects),
      displayBbox: unionBoundingBoxes(displayRects),
      focusBbox: unionBoundingBoxes(focusRects),
      precision: raw?.precision || 'BLOCK',
    }
  }).filter(locator => Number.isInteger(locator.pageNumber) && locator.pageNumber > 0)
  const first = locators[0] || {}
  const primaryBoxes = first.targetBoxes || []
  const primaryDisplayBoxes = first.displayBoxes || mergeDisplayBoxes(primaryBoxes)
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
      displayBoxes: primaryDisplayBoxes,
      focusBoxes: primaryFocusBoxes,
      targetBbox: first.targetBbox || unionBoundingBoxes(primaryBoxes),
      displayBbox: first.displayBbox || unionBoundingBoxes(primaryDisplayBoxes),
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
 * Build display-only regions from adjacent physical rectangles.  PDF layout
 * blocks may be split into several line boxes; showing every fragment as an
 * independent dashed rectangle makes a complete citation look incomplete.
 * Only boxes on the same visual column and with a small vertical gap merge.
 * Raw targetBoxes remain untouched for evidence identity and page actions.
 */
export function mergeDisplayBoxes(boxes, { maxVerticalGap = 0.028 } = {}) {
  const ordered = validBoxes(boxes)
    .slice()
    .sort((first, second) => Number(first.y) - Number(second.y)
      || Number(first.x) - Number(second.x))
  const groups = []
  for (const box of ordered) {
    const candidate = [...groups].reverse().find(group => (
      boxesShareDisplayColumn(group.bounds, box)
      && displayVerticalGap(group.bounds, box) <= maxVerticalGap
    ))
    if (candidate) {
      candidate.boxes.push(box)
      candidate.bounds = unionBoundingBoxes(candidate.boxes)
    } else {
      groups.push({ boxes: [box], bounds: box })
    }
  }
  return groups.map(group => unionBoundingBoxes(group.boxes)).filter(Boolean)
}

function boxesShareDisplayColumn(first, second) {
  if (!first || !second) return false
  const firstLeft = Number(first.x)
  const firstRight = firstLeft + Number(first.width)
  const secondLeft = Number(second.x)
  const secondRight = secondLeft + Number(second.width)
  const overlap = Math.max(0, Math.min(firstRight, secondRight) - Math.max(firstLeft, secondLeft))
  const narrower = Math.max(0.0001, Math.min(Number(first.width), Number(second.width)))
  return overlap / narrower >= 0.55 || Math.abs(firstLeft - secondLeft) <= 0.035
}

function displayVerticalGap(first, second) {
  return Number(second.y) - (Number(first.y) + Number(first.height))
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
