import { describe, expect, it } from 'vitest'
import {
  buildEvidenceResearchLocation,
  evidenceRelationMeta,
  evidenceStateMeta,
  summarizeClaims,
} from '../../src/utils/writingEvidence.js'

describe('writing evidence helpers', () => {
  it('summarizes evidence coverage and risk', () => {
    const result = summarizeClaims([
      { evidenceState: 'SUPPORTED' },
      { evidenceState: 'NEEDS_EVIDENCE' },
      { evidenceState: 'MIXED' },
      { evidenceState: 'CONFLICTING' },
    ])

    expect(result).toEqual({
      total: 4,
      supported: 1,
      needsEvidence: 1,
      risk: 2,
      coverage: 75,
    })
  })

  it('provides safe state and relation labels', () => {
    expect(evidenceStateMeta('SUPPORTED').label).toBe('已有支撑')
    expect(evidenceStateMeta('UNKNOWN').label).toBe('待补证据')
    expect(evidenceRelationMeta('CONTRADICTS').label).toBe('反驳')
  })

  it('builds a research backlink with page and session', () => {
    expect(buildEvidenceResearchLocation({
      paperId: 7,
      pageNumber: 13,
      researchSessionId: 9,
    }, 3)).toEqual({
      path: '/research/7',
      query: {
        page: '13',
        mode: 'selection',
        returnTo: '/writing?project=3',
        session: '9',
      },
    })
  })
})
