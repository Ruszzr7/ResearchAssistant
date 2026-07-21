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

export function describePdfSearchState({
  query = '',
  resultCount = 0,
  activeResultIndex = -1,
  loading = false,
  progress = 0,
  totalPages = 0,
  summary = {},
} = {}) {
  if (loading) return `正在建立索引 ${progress}/${totalPages}`
  if (!String(query || '').trim()) return ''
  if (resultCount > 0) return `${Math.max(0, activeResultIndex) + 1} / ${resultCount}`
  const ready = Math.max(0, Number(summary.ready) || 0)
  const failed = Math.max(0, Number(summary.failed) || 0)
  const noTextLayer = Math.max(0, Number(summary.noTextLayer) || 0)
  if (ready === 0 && failed > 0) return `PDF 文字提取失败（${failed} 页）`
  if (ready === 0 && noTextLayer > 0) return '当前 PDF 没有可搜索文字'
  if (failed > 0) return `已索引页面未找到；${failed} 页提取失败`
  return '未找到匹配内容'
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
