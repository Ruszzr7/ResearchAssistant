import { describe, expect, it } from 'vitest'
import {
  formulaNumberCandidates,
  formulaNumberSearchQueries,
  normalizeFormulaNumber,
} from '@/utils/formulaEvidence.js'

describe('formula evidence labels', () => {
  it('keeps a numbered formula label separate from its unreliable region geometry', () => {
    expect(formulaNumberCandidates({
      formulaNumber: '21',
      locator: { precision: 'FORMULA_REGION' },
    })).toEqual(['21'])
    expect(formulaNumberSearchQueries('21')).toEqual(['(21)', '（21）'])
  })

  it('supports grouped sub-equation labels without expanding an uncertain range', () => {
    expect(formulaNumberCandidates({
      formulaNumbers: ['1a', '1b', '1c', '1d'],
      locator: { precision: 'FORMULA_REGION' },
    })).toEqual(['1a', '1b', '1c', '1d'])
    expect(formulaNumberCandidates({
      formulaNumber: '1a, 1b, 1c',
      locator: { precision: 'FORMULA_REGION' },
    })).toEqual(['1a', '1b', '1c'])
    expect(formulaNumberCandidates({
      formulaNumber: '1a-1d',
      locator: { precision: 'FORMULA_REGION' },
    })).toEqual(['1a', '1b', '1c', '1d'])
  })

  it('extracts explicit labels from legacy section paths but not formula variable subscripts', () => {
    expect(formulaNumberCandidates({
      sectionPath: ['Results', 'Equation (31)'],
      locator: { precision: 'FORMULA_REGION' },
      text: 'C_1/C_2 = x',
    })).toEqual(['31'])
    expect(formulaNumberCandidates({
      locator: { precision: 'FORMULA_REGION' },
      text: 'C_1/C_2 = x',
    })).toEqual([])
  })

  it('normalizes full-width and prefixed labels', () => {
    expect(normalizeFormulaNumber('公式（1B）')).toBe('1b')
    expect(normalizeFormulaNumber('Equation (21)')).toBe('21')
    expect(normalizeFormulaNumber('not-a-label')).toBe('')
  })
})
