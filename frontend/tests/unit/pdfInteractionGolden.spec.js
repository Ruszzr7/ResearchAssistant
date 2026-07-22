import { describe, expect, it } from 'vitest'
import golden from '../fixtures/pdf-interaction-golden.json'

describe('PDF interaction engine golden contract', () => {
  it('keeps the real regression sample private and reproducible', () => {
    expect(golden.schemaVersion).toBe(1)
    expect(golden.engineContract).toBe('pdf-interaction-v1')
    expect(golden.realSampleEnv).toBe('RA_PDF_INTERACTION_SAMPLE')
    expect(JSON.stringify(golden)).not.toMatch(/[A-Z]:\\|backend\/data\/papers/i)
    expect(golden.realCases[0].expected).toMatchObject({
      mustNotCrossColumns: true,
      requiresCharacterRange: true,
      requiresPageRects: true,
    })
  })

  it('defines character ranges independently from inferred columns', () => {
    golden.syntheticCases.forEach(testCase => {
      expect(testCase.selection.from).toBeGreaterThanOrEqual(0)
      expect(testCase.selection.to).toBeGreaterThan(testCase.selection.from)
      expect(testCase.expectedText.trim()).not.toBe('')
      testCase.runs.forEach(run => {
        expect(run.charStart).toBeGreaterThanOrEqual(0)
        expect(run.rect.width).toBeGreaterThan(0)
        expect(run.rect.height).toBeGreaterThan(0)
      })
    })
  })
})
