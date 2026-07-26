import { describe, expect, it } from 'vitest'
import {
  buildWorkbenchPlanRequest,
  buildComparisonCoverage,
  buildComparisonQuestion,
  compactTracePhases,
  comparisonSelectionState,
  citedEvidence,
  WORKBENCH_MODES,
} from '@/utils/workbenchRun.js'

describe('PDF workbench request boundary', () => {
  const anchor = { kind: 'TEXT', page: 1, documentHash: 'hash', parserVersion: 'v1' }

  it('builds a fixed selection request without exposing skill names', () => {
    expect(buildWorkbenchPlanRequest({
      mode: WORKBENCH_MODES.SELECTION_QA,
      paperId: 7,
      question: '解释这一段',
      selectionAnchor: anchor,
    })).toEqual({
      paperIds: [7],
      question: '解释这一段',
      intent: 'ASK_SELECTION',
      scope: 'SELECTION',
      selectionAnchor: anchor,
      maxSteps: 6,
    })
  })

  it('uses mapping status instead of legacy kind for reference selections', () => {
    const request = buildWorkbenchPlanRequest({
      mode: WORKBENCH_MODES.SELECTION_QA,
      paperId: 7,
      question: '解释这条参考文献',
      selectionAnchor: { kind: 'REGION', mappingStatus: 'EXACT', page: 8 },
    })

    expect(request.scope).toBe('SELECTION')
  })

  it('sends only a conversation id and leaves history assembly to the server', () => {
    const selection = buildWorkbenchPlanRequest({
      mode: WORKBENCH_MODES.SELECTION_QA,
      paperId: 7,
      question: '继续解释',
      selectionAnchor: anchor,
      conversationId: 'selection-thread_1',
    })

    expect(selection.conversationId).toBe('selection-thread_1')
    expect(selection).not.toHaveProperty('conversationContext')

    const analysis = buildWorkbenchPlanRequest({
      mode: WORKBENCH_MODES.PAPER_ANALYSIS,
      paperId: 7,
      question: '全文分析',
      conversationId: 'ignored',
    })
    expect(analysis).not.toHaveProperty('conversationId')
    expect(analysis).not.toHaveProperty('conversationContext')
  })

  it('requires a second distinct paper for comparison', () => {
    expect(() => buildWorkbenchPlanRequest({
      mode: WORKBENCH_MODES.PAPER_COMPARISON,
      paperId: 7,
      comparisonPaperIds: [7],
      question: '比较贡献',
    })).toThrow('至少再选择一篇')
  })

  it('builds a single-paper improvement request', () => {
    expect(buildWorkbenchPlanRequest({
      mode: WORKBENCH_MODES.PAPER_IMPROVEMENT,
      paperId: 7,
      question: '分析可检验的改进空间',
    })).toEqual({
      paperIds: [7],
      question: '分析可检验的改进空间',
      intent: 'IDENTIFY_PAPER_IMPROVEMENTS',
      scope: 'PAPER',
      maxSteps: 6,
    })
  })

  it('builds field-gap analysis only from a completed comparison source', () => {
    expect(buildWorkbenchPlanRequest({
      mode: WORKBENCH_MODES.RESEARCH_GAP,
      paperId: 7,
      comparisonPaperIds: [8, 9],
      question: '识别候选研究空白',
      sourceRunId: 'compare-run',
    })).toEqual({
      paperIds: [7, 8, 9],
      question: '识别候选研究空白',
      intent: 'FIND_RESEARCH_GAPS',
      scope: 'COMPARISON',
      sourceRunId: 'compare-run',
      maxSteps: 6,
    })
    expect(() => buildWorkbenchPlanRequest({
      mode: WORKBENCH_MODES.RESEARCH_GAP,
      paperId: 7,
      comparisonPaperIds: [8],
      question: '识别候选研究空白',
    })).toThrow('至少需要三篇')
    expect(() => buildWorkbenchPlanRequest({
      mode: WORKBENCH_MODES.RESEARCH_GAP,
      paperId: 7,
      comparisonPaperIds: [8, 9],
      question: '识别候选研究空白',
    })).toThrow('先完成跨论文对比')
    expect(comparisonSelectionState(7, [8], 3)).toMatchObject({ total: 2, canStart: false })
  })

  it('enforces the shared eight-paper comparison boundary', () => {
    const state = comparisonSelectionState(1, [2, 2, 3, 4, 5, 6, 7, 8])
    expect(state).toMatchObject({ total: 8, canStart: true, atLimit: true, max: 8 })
    expect(() => buildWorkbenchPlanRequest({
      mode: WORKBENCH_MODES.PAPER_COMPARISON,
      paperId: 1,
      comparisonPaperIds: [2, 3, 4, 5, 6, 7, 8, 9],
      question: '比较贡献',
    })).toThrow('最多对比 8 篇')
  })

  it('builds an explicit bounded comparison question', () => {
    expect(buildComparisonQuestion('说明异同', ['核心方法', '关键结论', '核心方法']))
      .toBe('比较维度：核心方法、关键结论。说明异同')
  })

  it('computes per-paper evidence and claim coverage', () => {
    const coverage = buildComparisonCoverage({
      invocation: { paperIds: [1, 2, 3] },
      result: {
        evidence: [
          { evidenceId: 'e1', paperId: 1, page: 2 },
          { evidenceId: 'e2', paperId: 1, page: 3 },
          { evidenceId: 'e3', paperId: 2, page: 5 },
          { evidenceId: 'e4', paperId: 3, page: 1 },
        ],
        claims: [
          { evidenceIds: ['e1', 'e2', 'e3'] },
          { evidenceIds: ['e1', 'unknown'] },
        ],
      },
    }, [{ id: 1, title: 'Paper A' }, { id: 2, title: 'Paper B' }, { id: 3, title: 'Paper C' }])

    expect(coverage).toMatchObject({ covered: 2, total: 3, coverageRate: 2 / 3 })
    expect(coverage.rows).toEqual([
      expect.objectContaining({ title: 'Paper A', evidenceCount: 2, citedClaims: 2, pages: [2, 3], covered: true }),
      expect.objectContaining({ title: 'Paper B', evidenceCount: 1, citedClaims: 1, pages: [5], covered: true }),
      expect.objectContaining({ title: 'Paper C', evidenceCount: 1, citedClaims: 0, pages: [1], covered: false }),
    ])
  })

  it('returns only evidence actually cited by claims or an annotation', () => {
    const trace = { result: {
      claims: [{ text: 'claim', evidenceIds: ['e1'] }],
      annotationSuggestion: { evidenceIds: ['e2', 'unknown'] },
      evidence: [
        { evidenceId: 'e1', page: 1 },
        { evidenceId: 'e2', page: 2 },
        { evidenceId: 'e3', page: 3 },
      ],
    } }
    expect(citedEvidence(trace).map(item => item.evidenceId)).toEqual(['e1', 'e2'])
  })

  it('compresses persisted steps into four hoverable product phases', () => {
    const phases = compactTracePhases({
      steps: [
        { index: 0, name: '解析范围', status: 'COMPLETED', latencyMs: 10 },
        { index: 1, name: '检索证据', status: 'COMPLETED', evidenceCount: 3, latencyMs: 20 },
        { index: 2, name: '生成回答', status: 'COMPLETED', totalTokens: 900, latencyMs: 30 },
        { index: 3, name: '校验结果', status: 'COMPLETED', latencyMs: 5 },
        { index: 4, name: '保存结果', status: 'FAILED', errorMessage: '保存失败', latencyMs: 2 },
      ],
    })

    expect(phases).toHaveLength(4)
    expect(phases.map(item => item.status)).toEqual(['COMPLETED', 'COMPLETED', 'COMPLETED', 'FAILED'])
    expect(phases[1].tooltip).toContain('3 条证据')
    expect(phases[2].tooltip).toContain('900 tokens')
    expect(phases[3].tooltip).toContain('保存结果：失败')
    expect(phases[3].tooltip).toContain('保存失败')
  })
})
