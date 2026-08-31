const FORMULA_NUMBER = /^(\d{1,4}[a-z]?)$/i

/**
 * Returns the printed equation labels carried by an evidence item.  A source normally has
 * one label (21 or 1b), but accepting a list/range here keeps the viewer compatible with
 * grouped sub-equations without making the PDF geometry parser responsible for grouping.
 */
export function formulaNumberCandidates(item) {
  const values = [
    ...(Array.isArray(item?.formulaNumbers) ? item.formulaNumbers : []),
    item?.formulaNumber,
    ...(Array.isArray(item?.locator?.formulaNumbers) ? item.locator.formulaNumbers : []),
    item?.locator?.formulaNumber,
  ]
  const precision = item?.locator?.precision
  if (!values.some(value => String(value || '').trim())
      && (precision === 'FORMULA_REGION' || item?.kind === '公式')) {
    values.push(item?.text, item?.quote, item?.locator?.targetText)
    const sectionPath = Array.isArray(item?.sectionPath) ? item.sectionPath : []
    values.push(...sectionPath)
  }
  return [...new Set(values.flatMap(splitFormulaNumbers))]
}

/**
 * Search the complete printed label first.  The plain-label fallback is deliberately omitted:
 * searching for “1” would match ordinary prose and sub-labels such as “1a”.
 */
export function formulaNumberSearchQueries(label) {
  const value = normalizeFormulaNumber(label)
  if (!value) return []
  return [`(${value})`, `（${value}）`]
}

export function normalizeFormulaNumber(value) {
  const text = String(value || '')
    .replace(/[（）]/g, match => match === '（' ? '(' : ')')
    .replace(/^(?:equation|eq\.?|公式)\s*/i, '')
    .replace(/^\(|\)$/g, '')
    .trim()
  return FORMULA_NUMBER.test(text) ? text.toLowerCase() : ''
}

function splitFormulaNumbers(value) {
  const text = String(value || '').replace(/[–—−]/g, '-').trim()
  if (!text) return []
  const explicit = [...text.matchAll(/[（(]\s*(\d{1,4}[a-z]?)\s*[）)]/gi)]
  if (explicit.length) return explicit.map(match => normalizeFormulaNumber(match[1])).filter(Boolean)
  const labelSequence = text.replace(/^(?:equation|eq\.?|公式)\s*/i, '').trim()
  if (!/^\d{1,4}[a-z]?(?:[\s,;/&-]+\d{1,4}[a-z]?)*$/i.test(labelSequence)) return []
  const range = /^(\d{1,4})([a-z])-\s*(\d{1,4})([a-z])$/i.exec(labelSequence)
  if (range && range[1] === range[3]) {
    const start = range[2].toLowerCase().charCodeAt(0)
    const end = range[4].toLowerCase().charCodeAt(0)
    if (end >= start && end - start < 26) {
      return Array.from({ length: end - start + 1 }, (_, index) => (
        `${range[1]}${String.fromCharCode(start + index)}`
      ))
    }
  }
  const tokens = labelSequence.match(/\d{1,4}[a-z]?/gi) || []
  return tokens.map(normalizeFormulaNumber).filter(Boolean)
}
