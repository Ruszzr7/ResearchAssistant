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

  it('renders directly bound answer blocks without fuzzy sentence matching', () => {
    const cited = buildCitedAnswer('legacy answer', [], [
      { evidenceId: 'lay-sinr', page: 3 },
    ], [
      {
        text: 'SINR 公式位于第三页。',
        basis: 'PAPER_FACT',
        citations: [{ evidenceId: 'lay-sinr', quote: 'SINR is defined' }],
      },
      {
        text: '其数值越大通常表示接收条件越好。',
        basis: 'GENERAL_KNOWLEDGE',
        citations: [],
      },
    ])

    expect(cited).toContain('SINR 公式位于第三页。[1](#evidence-lay-sinr)')
    expect(cited).toContain('**通用知识：** 其数值越大')
    expect(cited).not.toContain('legacy answer')
  })
})
