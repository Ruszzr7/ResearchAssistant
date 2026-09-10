import { h } from 'vue'
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { usePaperAgent, unionBoundingBoxes } from '@/composables/usePaperAgent.js'
import { mapAgentEvidenceItem, selectEvidenceFocusBoxes } from '@/utils/evidenceViewModel.js'

const mocks = vi.hoisted(() => ({
  executeAgentTurn: vi.fn(),
  getAgentRun: vi.fn(),
  uploadAgentAttachment: vi.fn(),
}))

vi.mock('@/api/agent.js', () => mocks)

describe('usePaperAgent', () => {
  beforeEach(() => {
    mocks.executeAgentTurn.mockReset().mockResolvedValue({
      turnId: 'turn-1', runId: 'run-1', status: 'COMPLETED', message: '完成',
      citations: [], evidence: [], pendingActions: [],
    })
    mocks.uploadAgentAttachment.mockReset()
      .mockResolvedValueOnce({ attachmentId: 'file-1' })
      .mockResolvedValueOnce({ attachmentId: 'formula-1' })
    mocks.getAgentRun.mockReset()
  })

  it('unions fragmented locator boxes for a coarse scroll fallback', () => {
    expect(unionBoundingBoxes([
      { x: 0.2, y: 0.1, width: 0.2, height: 0.05 },
      { x: 0.1, y: 0.15, width: 0.6, height: 0.08 },
    ])).toEqual({ x: 0.1, y: 0.1, width: 0.6, height: 0.13 })
  })

  it('keeps the complete evidence text and every physical locator', () => {
    const fullText = '完整证据内容。'.repeat(80)
    const mapped = mapAgentEvidenceItem({
      sourceObjectId: 'source-1', paperId: 7, quote: fullText.slice(0, 319) + '…', fullText,
      evidenceKey: 'ev-1', contentType: 'TEXT', textFormat: 'PLAIN_TEXT', textReliable: true,
      locators: [
        { locatorId: 'loc-1', pageNumber: 3, rects: [{ x: .1, y: .2, width: .3, height: .04 }] },
        { locatorId: 'loc-2', pageNumber: 3, rects: [{ x: .55, y: .08, width: .3, height: .04 }] },
      ],
    })

    expect(mapped.fullText).toBe(fullText)
    expect(mapped.fullTextAvailable).toBe(true)
    expect(mapped.text).toBe(fullText)
    expect(mapped.locators).toHaveLength(2)
    expect(mapped.pages).toEqual([3])
    expect(mapped.locator.targetBoxes).toHaveLength(1)
  })

  it('keeps separate complete and focus geometry when the backend provides it', () => {
    const mapped = mapAgentEvidenceItem({
      sourceObjectId: 'formula-12', paperId: 197, quote: '公式 (12)',
      fullText: 'G = ...', contentType: 'FORMULA',
      locators: [{
        pageNumber: 3,
        rects: [{ x: .2, y: .3, width: .55, height: .08 }],
        focusRects: [{ x: .68, y: .32, width: .07, height: .02 }],
        precision: 'FORMULA_REGION',
      }],
    })

    expect(mapped.locator.targetBoxes).toEqual([{ x: .2, y: .3, width: .55, height: .08 }])
    expect(mapped.locator.focusBoxes).toEqual([{ x: .68, y: .32, width: .07, height: .02 }])
    expect(mapped.locator.focusBbox).toEqual({ x: .68, y: .32, width: .07, height: .02 })
  })

  it('prefers every authoritative locator box over a partial PDFium text match', () => {
    const locatorBoxes = [
      { x: .1, y: .2, width: .8, height: .03 },
      { x: .1, y: .24, width: .3, height: .03 },
    ]
    const partialSearch = [{ x: .1, y: .2, width: .8, height: .012 }]

    expect(selectEvidenceFocusBoxes({ locatorBoxes, exactBoxes: partialSearch }))
      .toEqual(locatorBoxes)
  })

  it('identifies legacy physical duplicates without pretending their preview is complete', () => {
    const locator = {
      pageNumber: 11,
      targetText: 'The paragraph ends infor-',
      rects: [{ x: .08, y: .87, width: .41, height: .06 }],
    }
    const first = mapAgentEvidenceItem({
      sourceObjectId: 'legacy-a', paperId: 204,
      quote: 'The paragraph ends infor-', locators: [locator],
    })
    const duplicate = mapAgentEvidenceItem({
      sourceObjectId: 'legacy-b', paperId: 204,
      quote: 'The paragraph ends infor-', locators: [locator],
    })

    expect(first.fullTextAvailable).toBe(false)
    expect(first.evidenceKey).toBe(duplicate.evidenceKey)
  })

  it('uploads stable attachments before executing the unified turn', async () => {
    let agent
    const wrapper = mount({
      setup() { agent = usePaperAgent(); return () => h('div') },
    })
    await agent.run({
      paperId: 7,
      researchSessionId: 9,
      userMessage: '分析附件',
      selectionAnchor: null,
      attachments: [
        { name: 'paper.txt', mimeType: 'text/plain', content: 'paper', kind: 'FILE' },
        { name: '公式1', mimeType: 'application/x-latex', content: 'x^2', kind: 'FORMULA_TEXT' },
      ],
    })

    expect(mocks.uploadAgentAttachment).toHaveBeenCalledTimes(2)
    expect(mocks.executeAgentTurn).toHaveBeenCalledWith(expect.objectContaining({
      conversationId: 9,
      primaryPaperId: 7,
      userMessage: '分析附件',
      attachmentIds: ['file-1'],
      formulaAttachmentIds: ['formula-1'],
    }))
    wrapper.unmount()
  })

  it('polls a persisted run after the server accepts it for background execution', async () => {
    mocks.executeAgentTurn.mockResolvedValueOnce({
      turnId: 'turn-1', runId: 'run-1', status: 'RUNNING', message: null,
      citations: [], evidence: [], pendingActions: [],
    })
    mocks.getAgentRun.mockResolvedValueOnce({
      turnId: 'turn-1', runId: 'run-1', status: 'COMPLETED', message: '后台完成',
      citations: [{ answerStart: 0, answerEnd: 4, sourceObjectId: 'source-1' }],
      evidence: [{
        sourceObjectId: 'source-1', paperId: 7, quote: '原文证据',
        locators: [{ pageNumber: 3, rects: [{ x: 0.1, y: 0.2, width: 0.3, height: 0.04 }], precision: 'TEXT_RANGE' }],
      }],
      pendingActions: [],
    })
    let agent
    const wrapper = mount({
      setup() { agent = usePaperAgent(); return () => h('div') },
    })

    const accepted = vi.fn()
    const result = await agent.run({
      paperId: 7,
      researchSessionId: 9,
      userMessage: '普通问题',
      attachments: [],
    }, { onAccepted: accepted })

    expect(accepted).toHaveBeenCalledWith(expect.objectContaining({ runId: 'run-1', status: 'RUNNING' }))
    expect(mocks.getAgentRun).toHaveBeenCalledWith('run-1', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(result.result.answer).toBe('后台完成')
    expect(result.result.evidence[0]).toMatchObject({
      evidenceId: 'source-1', paperId: 7, page: 3,
      locator: { targetBbox: { x: 0.1, y: 0.2, width: 0.3, height: 0.04 } },
    })
    wrapper.unmount()
  })

  it('marks persisted failed and cancelled runs as terminal recovery errors', async () => {
    mocks.getAgentRun.mockResolvedValueOnce({
      turnId: 'turn-1', runId: 'failed-run', status: 'FAILED', message: '模型调用失败',
      citations: [], evidence: [], pendingActions: [],
    })
    let agent
    const wrapper = mount({
      setup() { agent = usePaperAgent(); return () => h('div') },
    })

    await expect(agent.watchRun('failed-run')).rejects.toMatchObject({
      message: '模型调用失败', agentTerminal: true, agentRunStatus: 'FAILED',
    })
    expect(mocks.getAgentRun).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})
