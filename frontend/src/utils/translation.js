export const TRANSLATION_LANGUAGES = Object.freeze({
  CHINESE: 'ZH',
  ENGLISH: 'EN',
})

/** A deterministic UI hint; DeepL remains responsible for authoritative detection. */
export function detectTextLanguage(text) {
  const source = String(text || '')
  const chinese = (source.match(/[\u3400-\u9fff]/g) || []).length
  const latin = (source.match(/[A-Za-z]/g) || []).length
  return chinese >= 2 && chinese >= latin * 0.12
    ? TRANSLATION_LANGUAGES.CHINESE
    : TRANSLATION_LANGUAGES.ENGLISH
}

export function oppositeLanguage(language) {
  return language === TRANSLATION_LANGUAGES.CHINESE
    ? TRANSLATION_LANGUAGES.ENGLISH
    : TRANSLATION_LANGUAGES.CHINESE
}

export function languageLabel(language) {
  return language === TRANSLATION_LANGUAGES.CHINESE ? '中文' : '英文'
}
