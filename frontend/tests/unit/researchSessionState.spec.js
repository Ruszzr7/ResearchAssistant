import { describe, expect, it } from 'vitest'
import {
  LAST_RESEARCH_ROUTE_KEY,
  PENDING_RESEARCH_EVIDENCE_KEY,
  consumePendingResearchEvidence,
  consumeResearchArchiveReturnState,
  normalizeResearchLocation,
  readLastResearchLocation,
  writePendingResearchEvidence,
  writeResearchArchiveReturnState,
  writeLastResearchLocation,
} from '@/utils/researchSessionState.js'

function memoryStorage() {
  const values = new Map()
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key),
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

  it('round-trips every evidence locator for a cross-page jump', () => {
    const storage = memoryStorage()
    writePendingResearchEvidence({
      paperId: 184,
      page: 6,
      evidenceId: 'source-6',
      locators: [
        { locatorId: 'a', pageNumber: 6, targetBoxes: [{ x: 0.1, y: 0.2, width: 0.3, height: 0.02 }] },
        { locatorId: 'b', pageNumber: 6, targetBoxes: [{ x: 0.1, y: 0.24, width: 0.25, height: 0.02 }] },
      ],
    }, storage)

    const restored = consumePendingResearchEvidence(184, storage)

    expect(restored.evidenceId).toBe('source-6')
    expect(restored.locators).toHaveLength(2)
    expect(restored.locator.locatorId).toBe('a')
    expect(storage.getItem(PENDING_RESEARCH_EVIDENCE_KEY)).toBeNull()
  })

  it('does not consume evidence belonging to another paper', () => {
    const storage = memoryStorage()
    writePendingResearchEvidence({
      paperId: 184, page: 2,
      locator: { pageNumber: 2, targetBoxes: [{ x: 0.1, y: 0.2, width: 0.2, height: 0.02 }] },
    }, storage)

    expect(consumePendingResearchEvidence(185, storage)).toBeNull()
    expect(storage.getItem(PENDING_RESEARCH_EVIDENCE_KEY)).not.toBeNull()
  })

  it('restores archive list state once after a PDF jump', () => {
    const storage = memoryStorage()
    writeResearchArchiveReturnState({
      page: 2, activeTab: 'active', keyword: '核心', selectedPaperId: 184,
    }, storage)

    expect(consumeResearchArchiveReturnState(storage)).toEqual({
      page: 2, activeTab: 'active', keyword: '核心', selectedPaperId: 184,
    })
    expect(consumeResearchArchiveReturnState(storage)).toBeNull()
  })
})
