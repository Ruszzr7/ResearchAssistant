import { describe, expect, it } from 'vitest'
import golden from '../fixtures/pdf-content-golden.json'

describe('PDF content golden contract', () => {
  it('contains uniquely named search, selection, and formula cases', () => {
    expect(golden.schemaVersion).toBe(1)
    expect(golden.searchCases.length).toBeGreaterThanOrEqual(3)
    expect(golden.selectionCases.length).toBeGreaterThanOrEqual(2)
    expect(golden.formulaCases.length).toBeGreaterThanOrEqual(2)

    const ids = [
      ...golden.searchCases,
      ...golden.selectionCases,
      ...golden.formulaCases,
    ].map(testCase => testCase.id)
    expect(new Set(ids).size).toBe(ids.length)
  })

  it('locks search expectations to original PDF.js item indexes', () => {
    const fixture = golden.searchCases.find(testCase => (
      testCase.id === 'ieee-whitespace-preserves-original-item-index'
    ))
    expect(fixture.items[1].str.trim()).toBe('')
    expect(fixture.expected.itemIndexes).toEqual([3])
    expect(fixture.items[fixture.expected.itemIndexes[0]].str).toContain(fixture.expected.exactText)
  })

  it('requires accepted selections to stay inside one visual column', () => {
    const accepted = golden.selectionCases.filter(testCase => testCase.expected.accepted)
    expect(accepted).not.toHaveLength(0)
    accepted.forEach(testCase => {
      const selectedRuns = testCase.runs.filter(run => testCase.expected.itemIndexes.includes(run.itemIndex))
      expect(new Set(selectedRuns.map(run => run.column))).toEqual(new Set([testCase.expected.column]))
      expect(testCase.expected.text.trim()).not.toBe('')
    })
  })

  it('defines scale-invariant normalized formula regions', () => {
    golden.formulaCases.forEach(testCase => {
      const box = testCase.expectedNormalizedBox || testCase.normalizedBox
      expect(box.x).toBeGreaterThanOrEqual(0)
      expect(box.y).toBeGreaterThanOrEqual(0)
      expect(box.x + box.width).toBeLessThanOrEqual(1)
      expect(box.y + box.height).toBeLessThanOrEqual(1)
      expect(testCase.maximumRoundtripError).toBeLessThanOrEqual(0.000001)
    })
  })
})
