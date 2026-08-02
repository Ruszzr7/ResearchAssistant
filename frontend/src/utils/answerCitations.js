export function buildCitedAnswer(answer, claims = [], evidence = [], answerBlocks = []) {
  const source = String(answer || '')
  if (answerBlocks?.length) return renderBoundBlocks(answerBlocks, evidence)
  if (!source || !claims?.length || !evidence?.length) return source

  const evidenceById = new Map(evidence.map(item => [item.evidenceId, item]))
  const numberById = new Map()
  let nextNumber = 1
  const insertions = []
  const fallbackMarkers = []

  for (const claim of claims) {
    const ids = [...new Set(claim?.evidenceIds || [])].filter(id => evidenceById.has(id))
    if (!ids.length) continue
    const markers = ids.map(id => {
      if (!numberById.has(id)) numberById.set(id, nextNumber++)
      return `[${numberById.get(id)}](#evidence-${encodeURIComponent(id)})`
    }).join('')
    const range = closestSentenceRange(source, claim.text)
    if (range) insertions.push({ at: range.end, markers })
    else fallbackMarkers.push(markers)
  }

  if (!insertions.length && !fallbackMarkers.length) return source
  const byPosition = new Map()
  insertions.forEach(({ at, markers }) => {
    byPosition.set(at, `${byPosition.get(at) || ''}${markers}`)
  })
  let result = source
  ;[...byPosition.entries()].sort((a, b) => b[0] - a[0])
    .forEach(([at, markers]) => {
      result = `${result.slice(0, at)}${markers}${result.slice(at)}`
    })
  return fallbackMarkers.length
    ? `${result}\n\n来源：${[...new Set(fallbackMarkers)].join('')}`
    : result
}

function renderBoundBlocks(blocks, evidence) {
  const evidenceById = new Map((evidence || []).map(item => [item.evidenceId, item]))
  const numberById = new Map()
  let nextNumber = 1
  return blocks.map((block, blockIndex) => {
    const seen = new Set()
    const markers = (block?.citations || [])
      .map((citation, citationIndex) => ({ citation, citationIndex }))
      .filter(({ citation }) => citation?.evidenceId && !seen.has(citation.evidenceId)
        && seen.add(citation.evidenceId) && evidenceById.has(citation.evidenceId))
      .map(({ citation, citationIndex }) => {
        const id = citation.evidenceId
        if (!numberById.has(id)) numberById.set(id, nextNumber++)
        const target = `${encodeURIComponent(id)}~${blockIndex}~${citationIndex}`
        return `[${numberById.get(id)}](#evidence-${target})`
      }).join('')
    const prefix = block?.basis === 'INFERENCE'
      ? '**据此推断：** '
      : block?.basis === 'GENERAL_KNOWLEDGE'
        ? '**通用知识：** '
        : block?.basis === 'EVIDENCE_LIMIT'
          ? '**证据限制：** '
          : ''
    return `${prefix}${String(block?.text || '').trim()}${markers}`
  }).filter(Boolean).join('\n\n')
}

function closestSentenceRange(source, claim) {
  const target = normalize(claim)
  if (!target) return null
  const exactIndex = normalize(source).indexOf(target)
  if (exactIndex >= 0) {
    const rawIndex = source.indexOf(String(claim || '').trim())
    if (rawIndex >= 0) return { end: rawIndex + String(claim).trim().length }
  }

  const ranges = sentenceRanges(source)
  let best = null
  for (const range of ranges) {
    const score = overlapScore(target, normalize(source.slice(range.start, range.end)))
    if (!best || score > best.score) best = { ...range, score }
  }
  return best?.score >= 0.16 ? best : null
}

function sentenceRanges(source) {
  const result = []
  const matcher = /[^。！？.!?\n]+[。！？.!?]?|\n/g
  let match
  while ((match = matcher.exec(source)) != null) {
    const text = match[0]
    if (text.trim()) result.push({ start: match.index, end: match.index + text.length })
  }
  return result
}

function overlapScore(first, second) {
  const firstParts = grams(first)
  const secondParts = new Set(grams(second))
  if (!firstParts.length || !secondParts.size) return 0
  return firstParts.filter(part => secondParts.has(part)).length / firstParts.length
}

function grams(value) {
  const normalized = normalize(value)
  if (normalized.length <= 2) return normalized ? [normalized] : []
  return Array.from({ length: normalized.length - 1 }, (_, index) => normalized.slice(index, index + 2))
}

function normalize(value) {
  return String(value || '').normalize('NFKC').toLowerCase().replace(/[\s\p{P}\p{S}]+/gu, '')
}
