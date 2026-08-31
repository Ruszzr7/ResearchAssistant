import { h } from 'vue'
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { usePaperAgent, unionBoundingBoxes } from '@/composables/usePaperAgent.js'

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
