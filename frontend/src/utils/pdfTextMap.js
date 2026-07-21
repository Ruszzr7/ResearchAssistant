export const PDF_TEXT_MAP_STATUS = Object.freeze({
  READY: 'READY',
  NO_TEXT_LAYER: 'NO_TEXT_LAYER',
  FAILED: 'FAILED',
})

const IGNORED_SEARCH_CHARACTERS = /[\s\u00ad\u200b\u200c\u200d\u2060-\u2063]/u
const SEARCH_HYPHENS = new Set(['-', '‐', '‑'])

/**
 * Build the single page-local text contract shared by search and selection.
 * `itemIndex` always refers to the original PDF.js textContent.items position.
 * `spanIndex` follows PDF.js TextLayer: non-empty items (including whitespace)
 * create DOM spans, while empty-string items only remain in the source contract.
 */
export function buildPdfPageTextMap(page, items = [], metadata = {}) {
  const sourceItems = Array.isArray(items) ? items : []
  const runs = []
  let sourceText = ''
  let previousHasEOL = false
  let nextSpanIndex = 0

  sourceItems.forEach((item, itemIndex) => {
    const rawText = String(item?.str || '')
    const spanIndex = rawText.length > 0 ? nextSpanIndex++ : null
    const separator = sourceText ? (previousHasEOL ? '\n' : ' ') : ''
    sourceText += separator
    const sourceStart = sourceText.length
    sourceText += rawText
    runs.push({
      itemIndex,
      spanIndex,
      rawText,
      sourceStart,
      sourceEnd: sourceText.length,
      hasEOL: Boolean(item?.hasEOL),
      transform: Array.isArray(item?.transform) ? [...item.transform] : [],
      width: finiteNumber(item?.width),
      height: finiteNumber(item?.height),
    })
    previousHasEOL = Boolean(item?.hasEOL)
  })

  const projection = buildPdfSearchProjectionFromRuns(runs)
  const hasSearchableText = runs.some(run => run.rawText.trim().length > 0)
  return {
    version: 1,
    status: hasSearchableText ? PDF_TEXT_MAP_STATUS.READY : PDF_TEXT_MAP_STATUS.NO_TEXT_LAYER,
    page: positiveInteger(page, 1),
    documentFingerprint: String(metadata.documentFingerprint || ''),
    pageWidth: positiveNumber(metadata.pageWidth),
    pageHeight: positiveNumber(metadata.pageHeight),
    rotation: normalizeRotation(metadata.rotation),
    scale: positiveNumber(metadata.scale),
    sourceText,
    runs,
    normalizedText: projection.text,
    charMap: projection.charMap,
  }
}

export function buildFailedPdfPageTextMap(page, errorCode = 'TEXT_EXTRACTION_FAILED', metadata = {}) {
  return {
    ...buildPdfPageTextMap(page, [], metadata),
    status: PDF_TEXT_MAP_STATUS.FAILED,
    errorCode: String(errorCode || 'TEXT_EXTRACTION_FAILED'),
  }
}

/** Normalize a query with exactly the same rules as page text projection. */
export function normalizePdfSearchNeedle(value) {
  let text = ''
  for (const sourceCharacter of String(value || '')) {
    for (const character of sourceCharacter.normalize('NFKC').toLocaleLowerCase()) {
      if (isIgnoredSearchCharacter(character)) continue
      text += character
    }
  }
  return text
}

export function buildPdfSearchProjectionFromRuns(runs = []) {
  let text = ''
  const charMap = []
  for (const run of Array.isArray(runs) ? runs : []) {
    let itemOffset = 0
    for (const sourceCharacter of String(run?.rawText || '')) {
      const normalized = sourceCharacter.normalize('NFKC').toLocaleLowerCase()
      for (const character of normalized) {
        if (isIgnoredSearchCharacter(character)) continue
        text += character
        charMap.push({
          itemIndex: Number(run?.itemIndex) || 0,
          spanIndex: Number.isInteger(run?.spanIndex) ? run.spanIndex : null,
          itemOffset,
          sourceOffset: (Number(run?.sourceStart) || 0) + itemOffset,
        })
      }
      itemOffset += sourceCharacter.length
    }
  }
  return { text, charMap }
}

export function projectionRangeToItemRanges(charMap, start, end) {
  if (!Array.isArray(charMap) || start < 0 || end <= start || start >= charMap.length) return []
  const boundedEnd = Math.min(end, charMap.length)
  const ranges = []
  for (let index = start; index < boundedEnd; index += 1) {
    const mapped = charMap[index]
    if (!mapped) continue
    const previous = ranges[ranges.length - 1]
    if (previous && previous.itemIndex === mapped.itemIndex) {
      previous.endOffset = Math.max(previous.endOffset, mapped.itemOffset + 1)
      continue
    }
    ranges.push({
      itemIndex: mapped.itemIndex,
      spanIndex: mapped.spanIndex,
      startOffset: mapped.itemOffset,
      endOffset: mapped.itemOffset + 1,
    })
  }
  return ranges
}

function isIgnoredSearchCharacter(character) {
  return IGNORED_SEARCH_CHARACTERS.test(character) || SEARCH_HYPHENS.has(character)
}

function finiteNumber(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

function positiveNumber(value) {
  const number = finiteNumber(value)
  return number != null && number > 0 ? number : null
}

function positiveInteger(value, fallback) {
  const number = Math.round(Number(value))
  return Number.isFinite(number) && number > 0 ? number : fallback
}

function normalizeRotation(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) return 0
  return ((Math.round(number) % 360) + 360) % 360
}
