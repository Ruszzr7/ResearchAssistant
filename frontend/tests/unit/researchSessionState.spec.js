import { describe, expect, it } from 'vitest'
import {
  LAST_RESEARCH_ROUTE_KEY,
  normalizeResearchLocation,
  readLastResearchLocation,
  writeLastResearchLocation,
} from '@/utils/researchSessionState.js'

function memoryStorage() {
  const values = new Map()
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    values,
  }
}

describe('research route recovery state', () => {
  it('stores only navigation state and restores a canonical location', () => {
    const storage = memoryStorage()
    writeLastResearchLocation({
      paperId: 17,
      page: 13,
      mode: 'analysis',
      paperIds: '17,18,17,invalid',
      selection: 'must not be stored',
    }, storage)

    expect(JSON.parse(storage.values.get(LAST_RESEARCH_ROUTE_KEY))).toEqual({
      paperId: 17,
      page: 13,
      mode: 'analysis',
      paperIds: '17,18',
    })
    expect(readLastResearchLocation(storage)).toEqual({
      path: '/research/17',
      query: { page: '13', mode: 'analysis', paperIds: '17,18' },
    })
  })

  it('rejects corrupt or paper-less state', () => {
    const storage = memoryStorage()
    storage.setItem(LAST_RESEARCH_ROUTE_KEY, '{broken')
    expect(readLastResearchLocation(storage)).toBeNull()
    expect(normalizeResearchLocation({ page: 2 })).toBeNull()
  })

  it('preserves a persistent research session identifier', () => {
    const storage = memoryStorage()
    writeLastResearchLocation({ paperId: 17, page: 5, session: 42 }, storage)

    expect(readLastResearchLocation(storage)).toEqual({
      path: '/research/17',
      query: { page: '5', session: '42' },
    })
  })
})
