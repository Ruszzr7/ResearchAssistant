import { describe, expect, it } from 'vitest'
import { mergePageSelection, normalizeSelection, removeSelectedIds } from '@/utils/selection.js'

describe('paginated selection helpers', () => {
  it('deduplicates while preserving the original id type', () => {
    expect(normalizeSelection([1, '1', 2, null])).toEqual([1, 2])
  })

  it('merges a page without dropping selections from another page', () => {
    expect(mergePageSelection([1, 9], [{ id: 2 }, { id: 3 }], true)).toEqual([1, 9, 2, 3])
    expect(mergePageSelection([1, 2, 3], [{ id: 2 }], false)).toEqual([1, 3])
  })

  it('removes deleted ids across numeric/string representations', () => {
    expect(removeSelectedIds([1, 2, 3], ['2'])).toEqual([1, 3])
  })
})
