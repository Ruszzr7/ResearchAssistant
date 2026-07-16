import { describe, expect, it } from 'vitest'
import {
  detectTextLanguage,
  oppositeLanguage,
  TRANSLATION_LANGUAGES,
} from '@/utils/translation.js'

describe('translation language helpers', () => {
  it('detects the dominant user-facing language without treating formulas as prose', () => {
    expect(detectTextLanguage('The rate $R_k$ follows from [12].')).toBe('EN')
    expect(detectTextLanguage('该速率由公式 $R_k$ 给出。')).toBe('ZH')
    expect(oppositeLanguage(TRANSLATION_LANGUAGES.ENGLISH)).toBe('ZH')
    expect(oppositeLanguage(TRANSLATION_LANGUAGES.CHINESE)).toBe('EN')
  })
})
