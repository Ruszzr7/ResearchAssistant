import { describe, expect, it } from 'vitest'
import { buildCitationSources, buildCitedAnswer, stripModelCitationMarkers } from '@/utils/answerCitations.js'

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

  it('removes model-authored numeric markers before adding grounded clickable citations', () => {
    const cited = buildCitedAnswer(
      '方法填补了研究空白 [1]，并提高了速率 [3]。',
      [{ text: '方法填补了研究空白', evidenceIds: ['source-a'] }],
      [{ evidenceId: 'source-a', page: 4 }],
    )

    expect(cited).toContain('方法填补了研究空白[1](#evidence-source-a)')
    expect(cited).not.toContain(' [1]')
    expect(cited).not.toContain('[3]')
  })

  it('preserves mathematical intervals while removing standalone model citations', () => {
    expect(stripModelCitationMarkers('约束为 $t \\in [0,1]$，并满足 $$C_k \\le R_c$$。结论成立 [1]。'))
      .toBe('约束为 $t \\in [0,1]$，并满足 $$C_k \\le R_c$$。结论成立。')
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

  it('keeps evidence-limit diagnostics internal instead of rendering them to the user', () => {
    const cited = buildCitedAnswer('', [], [], [
      { text: '公共流 SINR 位于公式 (4)。', basis: 'PAPER_FACT', citations: [] },
      { text: '当前没有可信 LaTeX 转写，需要回原页核对。', basis: 'EVIDENCE_LIMIT', citations: [] },
    ])

    expect(cited).toContain('公共流 SINR 位于公式 (4)')
    expect(cited).not.toContain('证据限制')
    expect(cited).not.toContain('需要回原页核对')
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
    expect(sources[0]).toMatchObject({
      number: 1, kind: '公式', page: 4, excerpt: 'Equation (5) · Γp,k = ... (5)',
    })
    expect(sources[0].target.locator).toMatchObject({
      precision: 'FORMULA_REGION', targetText: '',
    })
  })

  it('groups sub-equations from one answer block into one clickable formula family', () => {
    const evidence = ['35a', '35b', '35c'].map((formulaNumber, index) => ({
      evidenceId: `eq-${formulaNumber}`,
      paperId: 190,
      page: 7,
      formulaNumber,
      blockId: `equation-region:p7-b00${index + 1}`,
      role: 'FORMULA',
      contentMode: 'REGION',
      text: '[公式区域]',
      sectionPath: ['IV. PROBLEM FORMULATION', `Equation (${formulaNumber})`],
      locator: {
        precision: 'FORMULA_REGION',
        targetBbox: { x: 0.1, y: 0.3 + index * 0.05, width: 0.4, height: 0.03 },
      },
    }))
    const blocks = [{
      text: '原始优化问题见式 (35a)–(35c)。',
      basis: 'PAPER_FACT',
      citations: evidence.map(item => ({ evidenceId: item.evidenceId, quote: '[公式区域]' })),
    }]

    const cited = buildCitedAnswer('', [], evidence, blocks)
    const sources = buildCitationSources([], evidence, blocks)

    expect(cited).toContain('式 (35a)–(35c)。[1](#evidence-source~1)')
    expect(cited.match(/#evidence-source~1/g)).toHaveLength(1)
    expect(sources).toHaveLength(1)
    expect(sources[0].evidenceIds).toEqual(['eq-35a', 'eq-35b', 'eq-35c'])
    expect(sources[0].target.formulaNumbers).toEqual(['35a', '35b', '35c'])
    expect(sources[0].target.locator.formulaNumbers).toEqual(['35a', '35b', '35c'])
  })

  it('never replaces a formula jump target with its supporting theorem prose', () => {
    const evidence = [{
      evidenceId: 'lay-theorem-formula', paperId: 188,
      blockId: 'equation-region:p6-b0024', page: 6,
      role: 'FORMULA', contentMode: 'REGION', text: '[公式区域]',
      sectionPath: ['Theorem 1', 'Equation (22)'],
      bbox: { x: 0.12, y: 0.42, width: 0.75, height: 0.08 },
      locator: {
        precision: 'FORMULA_REGION',
        targetBbox: { x: 0.12, y: 0.42, width: 0.75, height: 0.08 },
      },
    }]
    const blocks = [{
      text: '公共流遍历速率下界见式 (22)。', basis: 'PAPER_FACT', citations: [{
        evidenceId: 'lay-theorem-formula',
        quote: 'Theorem 1. The lower bound for the ergodic rate',
      }],
    }]

    const [source] = buildCitationSources([], evidence, blocks)

    expect(source.excerpt).toBe('Equation (22) · Theorem 1. The lower bound for the ergodic rate')
    expect(source.target.locator).toMatchObject({
      precision: 'FORMULA_REGION', targetText: '',
      targetBbox: evidence[0].locator.targetBbox,
    })
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

  it('merges adjacent wrapped fragments quoted from the same evidence block', () => {
    const evidence = [{
      evidenceId: 'lay-wrapped', paperId: 175, blockId: 'p4-b0037', page: 4,
      readingOrder: 141, role: 'BODY', contentMode: 'TEXT',
      text: 'signal-to-interference plus noise ratio (SINR) for the common stream at vehicle-k can be written as',
      bbox: { x: 0.08, y: 0.522, width: 0.41, height: 0.043 },
      locator: { precision: 'BLOCK' },
    }]
    const blocks = [{
      text: '公共流 SINR 定义如下。', basis: 'PAPER_FACT', citations: [
        { evidenceId: 'lay-wrapped', quote: 'signal-to-interference plus noise ratio (SINR) for the common' },
        { evidenceId: 'lay-wrapped', quote: 'stream at vehicle-k can be written as' },
      ],
    }]

    const sources = buildCitationSources([], evidence, blocks)

    expect(sources).toHaveLength(1)
    expect(sources[0].evidenceIds).toEqual(['lay-wrapped'])
    expect(sources[0].excerpt).toContain('common stream at vehicle-k')
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
    expect(sources[0].target.locator.targetBoxes).toEqual([
      evidence[0].bbox, evidence[1].bbox,
    ])
  })

  it('merges one wrapped source sentence even when its citations occur in separate answer blocks', () => {
    const evidence = [
      {
        evidenceId: 'lay-a', paperId: 7, blockId: 'p2-b10', page: 2, readingOrder: 10,
        role: 'BODY', contentMode: 'TEXT', text: 'The proposed method treats the remaining',
        bbox: { x: 0.08, y: 0.4, width: 0.4, height: 0.02 }, locator: { precision: 'BLOCK' },
      },
      {
        evidenceId: 'lay-b', paperId: 7, blockId: 'p2-b11', page: 2, readingOrder: 11,
        role: 'BODY', contentMode: 'TEXT', text: 'private streams as background noise.',
        bbox: { x: 0.08, y: 0.42, width: 0.35, height: 0.02 }, locator: { precision: 'BLOCK' },
      },
    ]
    const blocks = [
      { text: '该处理对象是剩余私有流。', basis: 'PAPER_FACT', citations: [
        { evidenceId: 'lay-a', quote: 'The proposed method treats the remaining' },
      ] },
      { text: '它们被视作背景噪声。', basis: 'PAPER_FACT', citations: [
        { evidenceId: 'lay-b', quote: 'private streams as background noise.' },
      ] },
    ]

    const cited = buildCitedAnswer('', [], evidence, blocks)
    const sources = buildCitationSources([], evidence, blocks)

    expect(sources).toHaveLength(1)
    expect(cited.match(/#evidence-source~1/g)).toHaveLength(2)
    expect(sources[0].excerpt).toContain('remaining private streams as background noise')
  })

  it('merges PDF-adjacent fragments even when formula and earlier citations interleave them', () => {
    const evidence = [
      {
        evidenceId: 'lay-order-139', paperId: 186, blockId: 'p4-b0035', page: 4,
        readingOrder: 139, role: 'BODY', contentMode: 'TEXT',
        text: 'Initially, each vehicle decodes the common stream sc,',
        bbox: { x: 0.096, y: 0.492, width: 0.394, height: 0.009 },
        locator: { precision: 'BLOCK' },
      },
      {
        evidenceId: 'lay-order-141', paperId: 186, blockId: 'p4-b0037', page: 4,
        readingOrder: 141, role: 'BODY', contentMode: 'TEXT',
        text: 'signal-to-interference plus noise ratio (SINR) for the common',
        bbox: { x: 0.08, y: 0.522, width: 0.41, height: 0.036 },
        locator: { precision: 'BLOCK' },
      },
      {
        evidenceId: 'lay-order-142', paperId: 186, blockId: 'p4-b0039', page: 4,
        readingOrder: 142, role: 'BODY', contentMode: 'TEXT',
        text: 'stream at vehicle-k can be written as',
        bbox: { x: 0.08, y: 0.537, width: 0.30, height: 0.028 },
        locator: { precision: 'BLOCK' },
      },
      {
        evidenceId: 'lay-formula', paperId: 186, blockId: 'equation-region:p4-b0041', page: 4,
        readingOrder: 143, role: 'FORMULA', contentMode: 'REGION', text: '[公式区域]',
        sectionPath: ['II. SYSTEM MODEL', 'Equation (4)'],
        bbox: { x: 0.15, y: 0.556, width: 0.34, height: 0.036 },
        locator: { precision: 'FORMULA_REGION' },
      },
    ]
    const blocks = [
      { text: '公共流 SINR 定义如下。', basis: 'PAPER_FACT', citations: [
        { evidenceId: 'lay-order-141', quote: 'signal-to-interference plus noise ratio (SINR) for the common' },
      ] },
      { text: '对应公式为公式 (4)。', basis: 'PAPER_FACT', citations: [
        { evidenceId: 'lay-formula', quote: '[公式区域]' },
        { evidenceId: 'lay-order-139', quote: 'Initially, each vehicle decodes the common stream sc,' },
        { evidenceId: 'lay-order-142', quote: 'stream at vehicle-k can be written as' },
      ] },
    ]

    const cited = buildCitedAnswer('', [], evidence, blocks)
    const sources = buildCitationSources([], evidence, blocks)

    expect(sources).toHaveLength(3)
    expect(sources[0].evidenceIds).toEqual(['lay-order-141', 'lay-order-142'])
    expect(sources[0].excerpt).toContain('common stream at vehicle-k can be written as')
    expect(sources[1].kind).toBe('公式')
    expect(sources[2].evidenceIds).toEqual(['lay-order-139'])
    expect(cited).toContain('公共流 SINR 定义如下。[1](#evidence-source~1)')
  })

  it('deduplicates physically identical evidence while retaining complete text', () => {
    const fullText = '同一物理区域的完整证据。'.repeat(30)
    const evidence = [
      {
        evidenceId: 'source-a', evidenceKey: 'ev-same', paperId: 7, page: 3,
        quote: `${fullText.slice(0, 319)}…`, fullText,
        locator: { precision: 'BLOCK', targetText: '同一物理区域的完整证据。',
          targetBoxes: [{ x: .1, y: .2, width: .3, height: .04 }] },
      },
      {
        evidenceId: 'source-b', evidenceKey: 'ev-same', paperId: 7, page: 3,
        quote: `${fullText.slice(0, 319)}…`, fullText,
        locator: { precision: 'BLOCK', targetText: '同一物理区域的完整证据。',
          targetBoxes: [{ x: .1, y: .2, width: .3, height: .04 }] },
      },
    ]
    const sources = buildCitationSources([
      { text: '第一处依据', evidenceIds: ['source-a'] },
      { text: '重复依据', evidenceIds: ['source-b'] },
    ], evidence)

    expect(sources).toHaveLength(1)
    expect(sources[0].fullText).toBe(fullText)
    expect(sources[0].excerptTruncated).toBe(true)
  })

  it('does not label a legacy evidence preview as expandable full text', () => {
    const preview = '历史证据摘要。'.repeat(40)
    const sources = buildCitationSources([
      { text: '旧回答', evidenceIds: ['legacy-source'] },
    ], [{
      evidenceId: 'legacy-source', evidenceKey: 'legacy-physical', paperId: 7, page: 3,
      quote: preview, fullText: preview, fullTextAvailable: false,
      locator: { precision: 'BLOCK', targetText: preview,
        targetBoxes: [{ x: .1, y: .2, width: .3, height: .04 }] },
    }])

    expect(sources).toHaveLength(1)
    expect(sources[0].fullTextAvailable).toBe(false)
    expect(sources[0].excerptTruncated).toBe(false)
  })

  it('does not render an exactly repeated persisted citation twice', () => {
    const answer = '同一段答案只应显示一次来源。'
    const evidence = [{
      evidenceId: 'source-a', paperId: 7, page: 3, quote: '完整证据', fullText: '完整证据',
      locator: { precision: 'BLOCK', targetText: '完整证据',
        targetBoxes: [{ x: .1, y: .2, width: .3, height: .04 }] },
    }]
    const duplicate = { text: answer, evidenceIds: ['source-a'] }

    expect(buildCitedAnswer(answer, [duplicate, duplicate], evidence))
      .toBe('同一段答案只应显示一次来源。[1](#evidence-source-a)')
  })

  it('exposes reliable LaTeX metadata for formula evidence', () => {
    const evidence = [{
      evidenceId: 'eq-21', paperId: 204, page: 6, formulaNumber: '21',
      quote: '公式 (21)', fullText: '\\hat{R}_c(t)=\\Psi(D)',
      contentType: 'FORMULA', textFormat: 'LATEX', textReliable: true,
      locator: { precision: 'FORMULA_REGION', targetBbox: { x: .2, y: .3, width: .4, height: .05 } },
    }]
    const sources = buildCitationSources([
      { text: '核心速率见式 (21)', evidenceIds: ['eq-21'] },
    ], evidence)

    expect(sources).toHaveLength(1)
    expect(sources[0]).toMatchObject({ kind: '公式', fullText: '\\hat{R}_c(t)=\\Psi(D)', textFormat: 'LATEX', textReliable: true })
  })
})
