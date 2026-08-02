import { describe, expect, it } from 'vitest'
import { buildCitedAnswer } from '@/utils/answerCitations.js'

describe('answer citations', () => {
  it('places only grounded evidence links next to the matching answer sentence', () => {
    const answer = '系统采用联合优化。实验结果显示时延降低。'
    const cited = buildCitedAnswer(answer, [
      { text: '实验结果显示时延降低', evidenceIds: ['lay-result', 'missing'] },
    ], [
      { evidenceId: 'lay-result', page: 4 },
    ])

    expect(cited).toContain('实验结果显示时延降低[1](#evidence-lay-result)')
    expect(cited).not.toContain('missing')
  })

  it('uses the closest sentence when a grounded claim is paraphrased', () => {
    const cited = buildCitedAnswer(
      '该方法先估计信道。随后通过功率分配降低传输时延。',
      [{ text: '功率优化能够降低时延', evidenceIds: ['lay-method'] }],
      [{ evidenceId: 'lay-method', page: 3 }],
    )

    expect(cited).toContain('随后通过功率分配降低传输时延。[1](#evidence-lay-method)')
  })
})
