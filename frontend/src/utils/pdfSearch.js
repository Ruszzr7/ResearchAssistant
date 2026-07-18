const MAX_RESULTS = 500

export function normalizePdfSearchQuery(value) {
  return String(value || '').replace(/\s+/g, ' ').trim().toLocaleLowerCase()
}

export function buildPdfPageSearchRecord(page, items = []) {
  const segments = []
  let text = ''
  let spanIndex = 0
  for (const item of items || []) {
    const value = String(item?.str || '').replace(/\s+/g, ' ').trim()
    if (!value) continue
    if (text) text += ' '
    const start = text.length
    text += value
    segments.push({ start, end: text.length, spanIndex })
    spanIndex += 1
  }
  return {
    page: Number(page) || 1,
    text,
    normalizedText: text.toLocaleLowerCase(),
    segments,
  }
}

export function findPdfSearchMatches(records, query, limit = MAX_RESULTS) {
  const needle = normalizePdfSearchQuery(query)
  if (!needle) return []
  const results = []
  for (const record of records || []) {
    const haystack = record?.normalizedText || ''
    let cursor = 0
    while (cursor <= haystack.length - needle.length && results.length < limit) {
      const start = haystack.indexOf(needle, cursor)
      if (start < 0) break
      const end = start + needle.length
      const spanIndexes = (record.segments || [])
        .filter(segment => segment.end > start && segment.start < end)
        .map(segment => segment.spanIndex)
      results.push({
        id: `${record.page}:${start}`,
        page: record.page,
        start,
        end,
        context: searchResultContext(record.text || '', start, end),
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
  return `${prefix}${text.slice(from, to).trim()}${suffix}`
}
