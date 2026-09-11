import { describe, expect, it } from 'vitest'
import { mapResearchMessageView } from '@/utils/researchMessageView.js'

describe('research message evidence replay', () => {
  it('restores legacy citations and keeps all persisted locators', () => {
    const message = mapResearchMessageView({
      id: 7,
      role: 'ASSISTANT',
      content: '核心创新在于联合优化。',
      evidence: {
        citations: [{ answerStart: 0, answerEnd: 10, sourceObjectId: 'source-a' }],
        evidence: [{
          evidenceId: 'source-a', paperId: 184, page: 6,
          quote: '联合优化', fullText: '第一行。第二行。',
          locators: [
            { pageNumber: 6, contentRects: [{ x: 0.1, y: 0.2, width: 0.3, height: 0.02 }] },
            { pageNumber: 6, contentRects: [{ x: 0.1, y: 0.24, width: 0.25, height: 0.02 }] },
          ],
        }],
      },
    })

    expect(message.role).toBe('assistant')
    expect(message.claims).toEqual([{ text: '核心创新在于联合优化', evidenceIds: ['source-a'] }])
    expect(message.evidence[0].fullText).toBe('第一行。第二行。')
    expect(message.evidence[0].locators).toHaveLength(2)
  })
})
