const INVALID_CONTROL = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/gu
const UNMAPPED_CHARACTER = /[\ufffd\ue000-\uf8ff]/gu
const VISUAL_LINE_BREAK = /\r\n?|\n/gu
const COLLAPSIBLE_WHITESPACE = /[\t\f\v \u00a0]+/gu

/**
 * Keep the engine text for character anchoring, but derive a readable string
 * for the UI and semantic requests. This intentionally does not guess missing
 * glyphs, dehyphenate words, or reconstruct two-dimensional mathematics.
 */
export function normalizePdfSelectionText(value) {
  const rawText = String(value || '')
  const invalidCharacters = rawText.match(INVALID_CONTROL) || []
  const unmappedCharacters = rawText.match(UNMAPPED_CHARACTER) || []
  const hadVisualLineBreaks = VISUAL_LINE_BREAK.test(rawText)
  VISUAL_LINE_BREAK.lastIndex = 0

  const readableText = rawText
    .normalize('NFC')
    .replace(INVALID_CONTROL, '')
    .replace(UNMAPPED_CHARACTER, '')
    .replace(VISUAL_LINE_BREAK, ' ')
    .replace(COLLAPSIBLE_WHITESPACE, ' ')
    .trim()

  return {
    rawText,
    readableText,
    hadVisualLineBreaks,
    hasExtractionIssues: invalidCharacters.length > 0 || unmappedCharacters.length > 0,
    removedCharacterCount: invalidCharacters.length + unmappedCharacters.length,
  }
}
