import { describe, expect, it } from 'vitest'
import {
  buildWorkbenchPlanRequest,
  compactTracePhases,
  citedEvidence,
} from '@/utils/workbenchRun.js'

describe('PDF workbench request boundary', () => {
  const anchor = { kind: 'TEXT', page: 1, documentHash: 'hash', parserVersion: 'v1' }

  it('builds a fixed selection request without exposing skill names', () => {
    expect(buildWorkbenchPlanRequest({
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
      paperId: 7,
      question: '解释这条参考文献',
      selectionAnchor: { kind: 'REGION', mappingStatus: 'EXACT', page: 8 },
    })

    expect(request.scope).toBe('SELECTION')
  })

  it('sends only a conversation id and leaves history assembly to the server', () => {
    const selection = buildWorkbenchPlanRequest({
      paperId: 7,
      question: '继续解释',
      selectionAnchor: anchor,
      conversationId: 'selection-thread_1',
    })

    expect(selection.conversationId).toBe('selection-thread_1')
    expect(selection).not.toHaveProperty('conversationContext')

  })

  it('builds a paper-grounded conversation request when no selection is attached', () => {
    expect(buildWorkbenchPlanRequest({
      paperId: 7,
      question: '这篇论文的核心贡献是什么？',
      conversationId: 'selection-thread_2',
    })).toEqual({
      paperIds: [7],
      question: '这篇论文的核心贡献是什么？',
      intent: 'ASK_SELECTION',
      scope: 'PAPER',
      conversationId: 'selection-thread_2',
      maxSteps: 6,
    })
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
