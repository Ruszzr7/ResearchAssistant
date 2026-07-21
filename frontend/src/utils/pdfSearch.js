import {
  buildFailedPdfPageTextMap,
  buildPdfPageTextMap,
  normalizePdfSearchNeedle,
  PDF_TEXT_MAP_STATUS,
  projectionRangeToItemRanges,
} from '@/utils/pdfTextMap.js'

const MAX_RESULTS = 500

export function normalizePdfSearchQuery(value) {
  return String(value || '').normalize('NFKC').replace(/\s+/g, ' ').trim().toLocaleLowerCase()
}

export function buildPdfPageSearchRecord(page, items = [], metadata = {}) {
  return buildPdfPageTextMap(page, items, metadata)
}

export function buildFailedPdfPageSearchRecord(page, errorCode, metadata = {}) {
  return buildFailedPdfPageTextMap(page, errorCode, metadata)
}

export function summarizePdfSearchIndex(records = []) {
  const summary = { ready: 0, noTextLayer: 0, failed: 0 }
  for (const record of records || []) {
    if (record?.status === PDF_TEXT_MAP_STATUS.READY) summary.ready += 1
    else if (record?.status === PDF_TEXT_MAP_STATUS.FAILED) summary.failed += 1
    else summary.noTextLayer += 1
  }
  return summary
}

export function findPdfSearchMatches(records, query, limit = MAX_RESULTS) {
  const needle = normalizePdfSearchNeedle(normalizePdfSearchQuery(query))
  if (!needle) return []
  const results = []
  for (const record of records || []) {
    if (record?.status !== PDF_TEXT_MAP_STATUS.READY) continue
    const haystack = record.normalizedText || ''
    let cursor = 0
    while (cursor <= haystack.length - needle.length && results.length < limit) {
      const start = haystack.indexOf(needle, cursor)
      if (start < 0) break
      const end = start + needle.length
      const itemRanges = projectionRangeToItemRanges(record.charMap, start, end)
      const sourceStart = record.charMap?.[start]?.sourceOffset ?? 0
      const sourceEnd = record.charMap?.[end - 1]?.sourceOffset != null
        ? record.charMap[end - 1].sourceOffset + 1
        : sourceStart
      const itemIndexes = itemRanges.map(range => range.itemIndex)
      const spanIndexes = itemRanges
        .map(range => range.spanIndex)
        .filter(Number.isInteger)
      results.push({
        id: `${record.page}:${start}`,
        page: record.page,
        start: sourceStart,
        end: sourceEnd,
        context: searchResultContext(record.sourceText || '', sourceStart, sourceEnd),
        itemRanges,
        itemIndexes,
        spanIndexes,
      })
      cursor = Math.max(end, start + 1)
    }
    if (results.length >= limit) break
  }
  return results
}

function searchResultContext(text, start, end) {
  const radius = 48
  const from = Math.max(0, start - radius)
  const to = Math.min(text.length, end + radius)
  const prefix = from > 0 ? '…' : ''
  const suffix = to < text.length ? '…' : ''
  return `${prefix}${text.slice(from, to).replace(/\s+/g, ' ').trim()}${suffix}`
}
