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

/**
 * Returns the same source sequence used by inline citation markers. Formula text and its
 * sibling formula-region locator are presented as one source: text proves what is readable,
 * while the region supplies the click target.
 */
export function buildCitationSources(claims = [], evidence = [], answerBlocks = []) {
  if (answerBlocks?.length) return boundCitationContext(answerBlocks, evidence).sources
  const evidenceById = new Map((evidence || []).map(item => [item.evidenceId, item]))
  const seen = new Set()
  const sources = []
  for (const claim of claims || []) {
    for (const evidenceId of claim?.evidenceIds || []) {
      if (seen.has(evidenceId)) continue
      const item = evidenceById.get(evidenceId)
      if (!item) continue
      seen.add(evidenceId)
      sources.push(sourceView(sources.length + 1, `evidence:${evidenceId}`, item, item,
        item.locator?.targetText || item.text || ''))
    }
  }
  return sources
}

function renderBoundBlocks(blocks, evidence) {
  const visibleBlocks = (blocks || []).filter(block => block?.basis !== 'EVIDENCE_LIMIT')
  const context = boundCitationContext(visibleBlocks, evidence)
  return visibleBlocks.map((block) => {
    const seen = new Set()
    const markers = (block?.citations || [])
      .map(citation => context.sourceForCitation(citation, block))
      .filter(source => source && !seen.has(source.key) && seen.add(source.key))
      .map(source => `[${source.number}](#evidence-${encodeURIComponent(`source~${source.number}`)})`)
      .join('')
    const prefix = block?.basis === 'INFERENCE'
      ? '**据此推断：** '
      : block?.basis === 'GENERAL_KNOWLEDGE'
        ? '**通用知识：** '
        : ''
    return `${prefix}${String(block?.text || '').trim()}${markers}`
  }).filter(Boolean).join('\n\n')
}

function boundCitationContext(blocks, evidence) {
  const evidenceById = new Map((evidence || []).map(item => [item.evidenceId, item]))
  const formulaRegionByBase = new Map()
  for (const item of evidence || []) {
    if (isFormulaRegion(item)) formulaRegionByBase.set(formulaBaseBlockId(item), item)
  }
  const sources = []
  const sourceByKey = new Map()
  const sourceKeyByCitation = new WeakMap()

  function descriptor(citation) {
    const item = evidenceById.get(citation?.evidenceId)
    if (!item) return null
    const ownFormulaRegion = isFormulaRegion(item) ? item : null
    const siblingFormulaRegion = formulaRegionByBase.get(String(item.blockId || ''))
    const formulaTarget = ownFormulaRegion || (siblingFormulaRegion && looksLikeFormulaCitation(citation, item)
      ? siblingFormulaRegion : null)
    const base = formulaTarget ? formulaBaseBlockId(formulaTarget) : ''
    const formulaKey = formulaTarget
      ? `formula:${item.paperId || ''}:${item.page || ''}:${base}`
      : ''
    const quote = String(citation?.quote || '').trim()
    return { citation, formulaKey, item, formulaTarget, quote }
  }

  function sourceForCitation(citation) {
    return citation && typeof citation === 'object'
      ? sourceByKey.get(sourceKeyByCitation.get(citation)) || null
      : null
  }

  // Model citation order follows answer rhetoric, not PDF reading order. Collect all citations
  // first, then reconstruct wrapped source sentences in physical reading order. Numbering is
  // still assigned by the source's first appearance in the answer.
  const values = []
  let appearance = 0
  for (const block of blocks || []) {
    for (const citation of block?.citations || []) {
      const value = descriptor(citation)
      if (!value) continue
      values.push({ ...value, appearance: appearance++ })
    }
  }
  const textValues = values.filter(value => !value.formulaTarget)
    .sort(comparePhysicalCitationOrder)
  const textGroups = []
  for (const value of textValues) {
    const previousGroup = textGroups[textGroups.length - 1]
    const previousValue = previousGroup?.[previousGroup.length - 1]
    if (previousValue && canMergeCitationContinuation(previousValue, value)) previousGroup.push(value)
    else textGroups.push([value])
  }
  const formulaGroups = values.filter(value => value.formulaTarget).map(value => [value])
  const groups = [...textGroups, ...formulaGroups]
    .sort((first, second) => Math.min(...first.map(value => value.appearance))
      - Math.min(...second.map(value => value.appearance)))

  for (const group of groups) {
    const value = group.length > 1 ? mergedTextDescriptor(group) : singleDescriptor(group[0])
    group.forEach(entry => sourceKeyByCitation.set(entry.citation, value.key))
    const existing = sourceByKey.get(value.key)
    if (existing) {
      if (moreInformativeQuote(value.quote, existing.quote)) {
        existing.quote = value.quote
        existing.excerpt = sourceExcerpt(value.quote, existing.target)
        existing.title = value.quote
      }
      continue
    }
    const source = sourceView(sources.length + 1, value.key, value.item, value.target, value.quote)
    source.evidenceIds = value.evidenceIds
    sources.push(source)
    sourceByKey.set(value.key, source)
  }
  return { sources, sourceForCitation }
}

function singleDescriptor(value) {
  const targetItem = value.formulaTarget || value.item
  // Supporting prose and the click target are different concerns. A formula citation can be
  // supported by a theorem sentence, but clicking it must still use the canonical formula box.
  const targetText = value.formulaTarget ? '' : (usableSearchQuote(value.quote) ? value.quote : '')
  const key = value.formulaKey || citationQuoteKey(value.item, value.quote)
  return {
    key,
    item: value.item,
    quote: value.quote,
    evidenceIds: [value.item.evidenceId],
    target: {
      ...targetItem,
      locator: { ...(targetItem.locator || {}), targetText },
    },
  }
}

function usableSearchQuote(value) {
  const quote = String(value || '').trim()
  return Boolean(quote) && !/^\[(?:公式|表格|图形)区域/.test(quote)
}

function mergedTextDescriptor(values) {
  const items = values.map(value => value.item)
  const quotes = values.map(value => value.quote).filter(Boolean)
  const targetBoxes = items.flatMap(item => item.locator?.targetBoxes?.length
    ? item.locator.targetBoxes
    : [item.locator?.targetBbox || item.bbox]).filter(Boolean)
  const targetBbox = unionBoundingBoxes(targetBoxes)
  const quote = quotes.join(' ').replace(/\s+/g, ' ').trim()
  const first = items[0]
  const key = `span:${first.paperId || ''}:${first.page || ''}:${items.map(item => item.blockId).join('|')}:${normalize(quote)}`
  return {
    key,
    item: first,
    quote,
    evidenceIds: [...new Set(items.map(item => item.evidenceId))],
    target: {
      ...first,
      blockId: `citation-span:${items.map(item => item.blockId).join('|')}`,
      bbox: targetBbox,
      text: quote,
      locator: {
        ...(first.locator || {}),
        targetBbox,
        targetBoxes,
        // Keep physical line fragments separate for PDFium search while displaying one sentence.
        targetText: quotes.join('\n'),
        precision: 'TEXT_SPAN',
      },
    },
  }
}

function citationQuoteKey(item, quote) {
  const targetText = quote || item?.locator?.targetText || item?.text || ''
  return `evidence:${item?.evidenceId || ''}:quote:${normalize(targetText)}`
}

function canMergeCitationContinuation(previous, current) {
  if (previous.formulaTarget || current.formulaTarget) return false
  if (!previous.quote || !current.quote) return false
  if (Number(previous.item.paperId) !== Number(current.item.paperId)
      || Number(previous.item.page) !== Number(current.item.page)) return false
  if (/[.!?。！？;；:]\s*$/.test(previous.quote)) return false
  if (previous.item.evidenceId === current.item.evidenceId) {
    return sameEvidenceContinuation(previous, current)
  }
  const previousOrder = Number(previous.item.readingOrder)
  const currentOrder = Number(current.item.readingOrder)
  if (Number.isFinite(previousOrder) && Number.isFinite(currentOrder)
      && currentOrder - previousOrder !== 1) return false
  const first = previous.item.locator?.targetBbox || previous.item.bbox
  const second = current.item.locator?.targetBbox || current.item.bbox
  return boxesShareColumn(first, second) && boxesAreVerticallyContinuous(first, second)
}

function sameEvidenceContinuation(previous, current) {
  const source = String(previous.item?.text || '').replace(/\s+/g, ' ')
  const first = String(previous.quote || '').replace(/\s+/g, ' ')
  const second = String(current.quote || '').replace(/\s+/g, ' ')
  const firstAt = source.indexOf(first)
  const secondAt = source.indexOf(second)
  if (firstAt < 0 || secondAt < 0 || secondAt < firstAt + first.length) return false
  return secondAt - (firstAt + first.length) <= 8
}

function comparePhysicalCitationOrder(first, second) {
  const paper = Number(first.item.paperId) - Number(second.item.paperId)
  if (paper) return paper
  const page = Number(first.item.page) - Number(second.item.page)
  if (page) return page
  const firstOrder = Number(first.item.readingOrder)
  const secondOrder = Number(second.item.readingOrder)
  if (Number.isFinite(firstOrder) && Number.isFinite(secondOrder) && firstOrder !== secondOrder) {
    return firstOrder - secondOrder
  }
  const firstBox = first.item.locator?.targetBbox || first.item.bbox
  const secondBox = second.item.locator?.targetBbox || second.item.bbox
  const vertical = Number(firstBox?.y || 0) - Number(secondBox?.y || 0)
  return vertical || first.appearance - second.appearance
}

function boxesShareColumn(first, second) {
  if (!first || !second) return false
  const overlap = Math.max(0, Math.min(Number(first.x) + Number(first.width), Number(second.x) + Number(second.width))
    - Math.max(Number(first.x), Number(second.x)))
  const narrower = Math.max(0.0001, Math.min(Number(first.width), Number(second.width)))
  return overlap / narrower >= 0.55 || Math.abs(Number(first.x) - Number(second.x)) <= 0.035
}

function boxesAreVerticallyContinuous(first, second) {
  if (!first || !second) return false
  const firstTop = Number(first.y)
  const firstBottom = firstTop + Number(first.height)
  const secondTop = Number(second.y)
  const gap = secondTop - firstBottom
  return secondTop >= firstTop - 0.01 && gap <= 0.025
}

function unionBoundingBoxes(boxes) {
  const valid = (boxes || []).filter(Boolean)
  if (!valid.length) return null
  const left = Math.min(...valid.map(box => Number(box.x)))
  const top = Math.min(...valid.map(box => Number(box.y)))
  const right = Math.max(...valid.map(box => Number(box.x) + Number(box.width)))
  const bottom = Math.max(...valid.map(box => Number(box.y) + Number(box.height)))
  return { x: left, y: top, width: right - left, height: bottom - top }
}

function sourceView(number, key, item, target, quote) {
  const formula = isFormulaRegion(target)
  return {
    number,
    key,
    evidenceId: item?.evidenceId || '',
    page: target?.page || item?.page,
    quote: String(quote || '').trim(),
    excerpt: sourceExcerpt(quote, target || item),
    title: String(quote || target?.text || item?.text || '').trim(),
    kind: formula ? '公式' : '正文',
    target,
  }
}

function sourceExcerpt(quote, item) {
  const value = String(quote || '').trim()
  const placeholder = !value || /^\[(?:公式|表格|图形)区域/.test(value)
  const formulaLabel = [...(item?.sectionPath || [])].reverse().find(part => /Equation|公式/i.test(part))
  const source = placeholder
    ? (formulaLabel || (isFormulaRegion(item) ? '公式区域' : item?.text || ''))
    : (isFormulaRegion(item) && formulaLabel && !value.includes(formulaLabel)
        ? `${formulaLabel} · ${value}` : value)
  return source.length <= 220 ? source : `${source.slice(0, 217).trim()}…`
}

function isFormulaRegion(item) {
  return item?.locator?.precision === 'FORMULA_REGION'
    || item?.role === 'FORMULA' && item?.contentMode === 'REGION'
    || String(item?.blockId || '').startsWith('equation-region:')
}

function formulaBaseBlockId(item) {
  return String(item?.blockId || '').replace(/^equation-region:/, '')
}

function looksLikeFormulaCitation(citation, item) {
  const value = `${citation?.quote || ''} ${item?.text || ''}`
  return /(?:[=<>≤≥∑∫√Γ]|\(\d{1,3}\)|\b(?:equation|formula)\b)/i.test(value)
}

function moreInformativeQuote(candidate, current) {
  const score = value => {
    const text = String(value || '').trim()
    if (!text || /^\[.*区域/.test(text)) return 0
    return Math.min(500, text.length) + (/=|Γ|∑|∫|√|\(\d+\)/.test(text) ? 500 : 0)
  }
  return score(candidate) > score(current)
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
