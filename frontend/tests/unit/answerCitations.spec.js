import { describe, expect, it } from 'vitest'
import { buildCitationSources, buildCitedAnswer } from '@/utils/answerCitations.js'

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

    expect(cited).toContain('SINR 公式位于第三页。[1](#evidence-source~1)')
    expect(cited).toContain('**通用知识：** 其数值越大')
    expect(cited).not.toContain('legacy answer')
  })

  it('uses one shared index and merges formula text with its region locator', () => {
    const evidence = [
      {
        evidenceId: 'lay-text', paperId: 7, blockId: 'p4-b0062', page: 4,
        role: 'BODY', contentMode: 'TEXT', text: 'Γp,k = ... (5)',
        bbox: { x: 0.14, y: 0.76, width: 0.35, height: 0.02 },
        locator: { precision: 'BLOCK' },
      },
      {
        evidenceId: 'lay-region', paperId: 7, blockId: 'equation-region:p4-b0062', page: 4,
        role: 'FORMULA', contentMode: 'REGION', text: '[公式区域]',
        sectionPath: ['System Model', 'Equation (5)'],
        bbox: { x: 0.13, y: 0.75, width: 0.37, height: 0.04 },
        locator: { precision: 'FORMULA_REGION', targetBbox: { x: 0.13, y: 0.75, width: 0.37, height: 0.04 } },
      },
    ]
    const blocks = [{
      text: '私有流 SINR 为公式 (5)。', basis: 'PAPER_FACT', citations: [
        { evidenceId: 'lay-text', quote: 'Γp,k = ... (5)' },
        { evidenceId: 'lay-region', quote: '[公式区域]' },
      ],
    }]

    const cited = buildCitedAnswer('', [], evidence, blocks)
    const sources = buildCitationSources([], evidence, blocks)

    expect(cited).toContain('公式 (5)。[1](#evidence-source~1)')
    expect(cited.match(/#evidence-source~1/g)).toHaveLength(1)
    expect(sources).toHaveLength(1)
    expect(sources[0]).toMatchObject({ number: 1, kind: '公式', page: 4, excerpt: 'Γp,k = ... (5)' })
    expect(sources[0].target.locator).toMatchObject({ precision: 'FORMULA_REGION', targetText: '' })
  })

  it('keeps different quoted sentences from one evidence block as separate sources', () => {
    const evidence = [{
      evidenceId: 'lay-private', paperId: 175, blockId: 'p4-b0056', page: 4,
      readingOrder: 151, role: 'BODY', contentMode: 'TEXT',
      text: 'vehicle-k decodes its private stream, treating other private streams intended as background noise. The SINR for decoding its private stream',
      bbox: { x: 0.08, y: 0.69, width: 0.41, height: 0.07 },
      locator: { precision: 'BLOCK' },
    }]
    const blocks = [
      {
        text: '私有流 SINR 定义如下。', basis: 'PAPER_FACT',
        citations: [{ evidenceId: 'lay-private', quote: 'The SINR for decoding its private stream' }],
      },
      {
        text: '其他私有流被当作背景噪声。', basis: 'PAPER_FACT',
        citations: [{ evidenceId: 'lay-private', quote: 'treating other private streams intended as background noise' }],
      },
    ]

    const cited = buildCitedAnswer('', [], evidence, blocks)
    const sources = buildCitationSources([], evidence, blocks)

    expect(cited).toContain('私有流 SINR 定义如下。[1](#evidence-source~1)')
    expect(cited).toContain('其他私有流被当作背景噪声。[2](#evidence-source~2)')
    expect(sources).toHaveLength(2)
    expect(sources.map(source => source.target.locator.targetText)).toEqual([
      'The SINR for decoding its private stream',
      'treating other private streams intended as background noise',
    ])
  })

  it('merges adjacent layout fragments when citations continue one wrapped sentence', () => {
    const evidence = [
      {
        evidenceId: 'lay-common-a', paperId: 175, blockId: 'p4-b0037', page: 4,
        readingOrder: 141, role: 'BODY', contentMode: 'TEXT',
        text: 'signal-to-interference plus noise ratio (SINR) for the common',
        bbox: { x: 0.08, y: 0.522, width: 0.41, height: 0.036 },
        locator: { precision: 'BLOCK' },
      },
      {
        evidenceId: 'lay-common-b', paperId: 175, blockId: 'p4-b0039', page: 4,
        readingOrder: 142, role: 'BODY', contentMode: 'TEXT',
        text: 'stream at vehicle-k can be written as',
        bbox: { x: 0.08, y: 0.537, width: 0.30, height: 0.028 },
        locator: { precision: 'BLOCK' },
      },
    ]
    const blocks = [{
      text: '公共流 SINR 定义位于公式前。', basis: 'PAPER_FACT', citations: [
        { evidenceId: 'lay-common-a', quote: 'signal-to-interference plus noise ratio (SINR) for the common' },
        { evidenceId: 'lay-common-b', quote: 'stream at vehicle-k can be written as' },
      ],
    }]

    const cited = buildCitedAnswer('', [], evidence, blocks)
    const sources = buildCitationSources([], evidence, blocks)

    expect(cited).toContain('公式前。[1](#evidence-source~1)')
    expect(cited.match(/#evidence-source~1/g)).toHaveLength(1)
    expect(sources).toHaveLength(1)
    expect(sources[0].excerpt).toBe('signal-to-interference plus noise ratio (SINR) for the common stream at vehicle-k can be written as')
    expect(sources[0].evidenceIds).toEqual(['lay-common-a', 'lay-common-b'])
    expect(sources[0].target.locator).toMatchObject({
      precision: 'TEXT_SPAN',
      targetText: 'signal-to-interference plus noise ratio (SINR) for the common\nstream at vehicle-k can be written as',
      targetBbox: { x: 0.08, y: 0.522, width: 0.41, height: 0.04300000000000004 },
    })
  })
})
