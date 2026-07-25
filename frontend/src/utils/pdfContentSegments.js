const MATH_FONT = /(?:math|symbol|cmsy|cmmi|cmex|mt extra|stix|asana|euler)/i
const MATH_CHAR = /[\u0370-\u03ff\u2070-\u209f\u2200-\u22ff\u27c0-\u27ef\u2980-\u29ff\u2a00-\u2aff=<>±×÷√∑∏∫∞^_]/u
const WORD_CHAR = /[\p{L}\p{N}]/u

function runSignals(run) {
  const text = String(run.text || '')
  const compact = [...text].filter(character => !/\s/u.test(character))
  const mathCharacters = compact.filter(character => MATH_CHAR.test(character)).length
  const wordCharacters = compact.filter(character => WORD_CHAR.test(character)).length
  const mathFont = MATH_FONT.test(`${run.font?.name || ''} ${run.font?.familyName || ''}`)
  const operatorSyntax = /(?:[\p{L}\p{N})\]}][=<>±×÷^_]|[=<>±×÷^_][\p{L}\p{N}({[])/u.test(text)
  return {
    mathFont,
    mathRatio: compact.length ? mathCharacters / compact.length : 0,
    operatorSyntax,
    wordCharacters,
  }
}

function classifyRun(run) {
  const signals = runSignals(run)
  const math = signals.mathFont
    || signals.mathRatio >= 0.2
    || signals.operatorSyntax && signals.wordCharacters <= 8
  if (!math) return 'TEXT'
  const rect = run.rect || {}
  const centered = rect.x > 0.12 && rect.x + rect.width < 0.88
  const displayLike = centered && (signals.mathRatio >= 0.45 || rect.width >= 0.28)
  return displayLike ? 'DISPLAY_MATH' : 'INLINE_MATH'
}

function canMerge(left, right) {
  if (!left || left.type !== right.type || right.charStart > left.charEnd + 3) return false
  if (left.text.length + right.text.length > 1200) return false
  if (left.type === 'TEXT') return true
  const verticalGap = Math.abs((left.rect?.y || 0) - (right.rect?.y || 0))
  return verticalGap <= Math.max(left.rect?.height || 0, right.rect?.height || 0) * 0.8
}

function merge(left, right) {
  const x = Math.min(left.rect.x, right.rect.x)
  const y = Math.min(left.rect.y, right.rect.y)
  const edge = Math.max(left.rect.x + left.rect.width, right.rect.x + right.rect.width)
  const bottom = Math.max(left.rect.y + left.rect.height, right.rect.y + right.rect.height)
  return {
    ...left,
    charEnd: right.charEnd,
    text: `${left.text}${right.charStart > left.charEnd + 1 ? ' ' : ''}${right.text}`,
    rect: { x, y, width: edge - x, height: bottom - y },
    fonts: [...new Set([...left.fonts, ...right.fonts])],
  }
}

/**
 * Segments engine-native character runs without changing their source text.
 * Classification is an auxiliary hint; character ranges remain the source of truth.
 */
export function segmentPdfSelection(runs, pageSize) {
  const classified = (runs || [])
    .filter(run => run && run.charEnd >= run.charStart && String(run.text || '').trim().length)
    .sort((left, right) => left.charStart - right.charStart)
    .map(run => ({
      type: classifyRun(run),
      charStart: run.charStart,
      charEnd: run.charEnd,
      text: String(run.text),
      rect: { ...run.rect },
      fonts: [run.font?.name || run.font?.familyName || ''].filter(Boolean),
    }))

  return classified.reduce((segments, current) => {
    const previous = segments[segments.length - 1]
    if (canMerge(previous, current)) segments[segments.length - 1] = merge(previous, current)
    else segments.push(current)
    return segments
  }, [])
}
