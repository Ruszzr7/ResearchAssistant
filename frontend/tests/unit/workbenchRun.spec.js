import { describe, expect, it } from 'vitest'
import {
  buildWorkbenchPlanRequest,
  appliedWorkbenchRunIds,
  citedEvidence,
  workbenchMarkdownToHtml,
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

  it('requires a second distinct paper for comparison', () => {
    expect(() => buildWorkbenchPlanRequest({
      mode: WORKBENCH_MODES.PAPER_COMPARISON,
      paperId: 7,
      comparisonPaperIds: [7],
      question: '比较贡献',
    })).toThrow('至少再选择一篇')
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

  it('renders the report subset while escaping model-provided HTML', () => {
    const html = workbenchMarkdownToHtml('# 结论\n\n- **有效** <script>alert(1)</script>')
    expect(html).toContain('<h3>结论</h3>')
    expect(html).toContain('<strong>有效</strong>')
    expect(html).toContain('&lt;script&gt;')
    expect(html).not.toContain('<script>')
  })

  it('recovers already-applied annotation runs after a reload', () => {
    expect(appliedWorkbenchRunIds([
      { coordinates: { workbenchRunId: 'run-1' } },
      { coordinates: { workbenchRunId: 'run-1' } },
      { coordinates: {} },
    ])).toEqual(['run-1'])
  })
})
