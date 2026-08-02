import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PaperWorkbenchPanel from '@/components/pdf/PaperWorkbenchPanel.vue'
import FormulaRegionCard from '@/components/pdf/FormulaRegionCard.vue'

const mocks = vi.hoisted(() => ({
  translateTexts: vi.fn(),
  createResearchSession: vi.fn(),
  getResearchSession: vi.fn(),
  listResearchSessions: vi.fn(),
  appendResearchMessages: vi.fn(),
  getPaperMemoryStatus: vi.fn(),
  startPaperUnderstanding: vi.fn(),
  state: {
    trace: { __v_isRef: true, value: null },
    running: { __v_isRef: true, value: false },
    error: { __v_isRef: true, value: '' },
    run: vi.fn(),
    loadRecent: vi.fn(),
  },
}))

vi.mock('@/api/workbench.js', () => ({ translateTexts: mocks.translateTexts }))
vi.mock('@/api/paperMemory.js', () => ({
  getPaperMemoryStatus: mocks.getPaperMemoryStatus,
  startPaperUnderstanding: mocks.startPaperUnderstanding,
}))
vi.mock('@/api/researchArchive.js', () => ({
  createResearchSession: mocks.createResearchSession,
  getResearchSession: mocks.getResearchSession,
  listResearchSessions: mocks.listResearchSessions,
  appendResearchMessages: mocks.appendResearchMessages,
}))
vi.mock('@/composables/usePaperWorkbench.js', () => ({
  usePaperWorkbench: () => mocks.state,
}))

const passthrough = { template: '<span><slot /></span>' }
const buttonStub = {
  emits: ['click'],
  template: '<button :disabled="$attrs.disabled" @click="$emit(\'click\')"><slot /></button>',
}
const inputStub = {
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
}
const defaultStubs = {
  'el-tag': passthrough,
  'el-input': inputStub,
  'el-button': buttonStub,
}
const paper = { id: 1, title: 'Current Paper', pdfPath: 'paper.pdf' }
const textSelection = { text: 'The selected method solves the estimation problem.' }
const textAnchor = {
  kind: 'TEXT',
  page: 2,
  confidence: 0.96,
  boxes: [{ x: 0.1, y: 0.2, width: 0.5, height: 0.08 }],
}

describe('PaperWorkbenchPanel paper-reading workspace', () => {
  beforeEach(() => {
    mocks.translateTexts.mockReset()
    mocks.createResearchSession.mockReset().mockResolvedValue({ id: 91 })
    mocks.getResearchSession.mockReset().mockResolvedValue({ messages: [], runs: [] })
    mocks.listResearchSessions.mockReset().mockResolvedValue([])
    mocks.appendResearchMessages.mockReset().mockResolvedValue([])
    mocks.getPaperMemoryStatus.mockReset().mockResolvedValue({
      paperId: 1, status: 'READY', stageText: '论文记忆已就绪', progress: 100,
      totalChunks: 4, completedChunks: 4, failedChunks: 0,
      canStart: false, canRetry: false, revision: 2,
    })
    mocks.startPaperUnderstanding.mockReset().mockResolvedValue({ taskId: 'memory-task-1' })
    mocks.state.trace.value = null
    mocks.state.running.value = false
    mocks.state.error.value = ''
    mocks.state.run.mockReset()
    mocks.state.loadRecent.mockReset().mockResolvedValue([])
  })

  it('uses one unified research conversation and leaves comparison as an interface', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.find('.product-tabs').exists()).toBe(false)
    expect(wrapper.get('.assistant-context-header').text()).toContain('论文助手')
    expect(wrapper.get('.assistant-context-header').text()).toContain('连续科研对话')
    await wrapper.get('.assistant-context-header button').trigger('click')
    expect(wrapper.emitted('add-comparison-paper')).toHaveLength(1)
    expect(mocks.state.run).not.toHaveBeenCalled()
  })

  it('switches content and formula capture from the sliding selector', async () => {
    const wrapper = mountPanel({ captureMode: 'text' })
    const buttons = wrapper.findAll('.capture-switch button')
    expect(buttons.map(button => button.text())).toEqual(['内容选取', '公式精确框选'])
    expect(buttons[0].classes()).toContain('active')
    expect(wrapper.get('.capture-hint').text()).toContain('选择文字或公式')

    await buttons[1].trigger('click')
    expect(wrapper.emitted('capture-mode-change')?.[0]).toEqual(['formula'])

    await wrapper.setProps({ captureMode: 'formula' })
    expect(wrapper.get('.capture-switch').classes()).toContain('is-formula')
    expect(wrapper.findAll('.capture-switch button')[1].classes()).toContain('active')
    expect(wrapper.get('.capture-hint').text()).toContain('直接选取不完整')
    expect(wrapper.get('.capture-hint').text()).toContain('固定内容')
    expect(wrapper.get('.capture-hint').text()).toContain('可编辑 LaTeX')
  })

  it('shows blocking whole-paper understanding progress and retries partial memory', async () => {
    mocks.getPaperMemoryStatus.mockResolvedValue({
      paperId: 1, status: 'PARTIAL', stageText: '论文记忆部分就绪，可重试失败分块',
      progress: 100, totalChunks: 5, completedChunks: 4, failedChunks: 1,
      canStart: true, canRetry: true, revision: 3,
    })
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.get('.memory-status').text()).toContain('部分就绪')
    expect(wrapper.get('.memory-status').text()).toContain('5/5')
    expect(wrapper.get('.memory-status').text()).toContain('1 个待重试')
    await wrapper.get('.memory-status__action').trigger('click')
    await flushPromises()

    expect(mocks.startPaperUnderstanding).toHaveBeenCalledWith(1, 'paper-memory-ui:1:3')
    expect(wrapper.get('.memory-status').text()).toContain('任务已提交')
    wrapper.unmount()
  })

  it('keeps paper questions disabled until the global profile is ready', async () => {
    mocks.getPaperMemoryStatus.mockResolvedValue({
      paperId: 1, status: 'UNDERSTANDING', stageText: '正在理解论文全文…',
      progress: 0, totalChunks: 1, completedChunks: 0, failedChunks: 0,
      promptTokens: 420, completionTokens: 80,
      profileReady: false, canStart: false, canRetry: false, revision: 3,
    })
    const wrapper = mountPanel({ selection: textSelection, selectionAnchor: textAnchor })
    await flushPromises()

    await wrapper.get('.assistant-composer textarea').setValue('现在可以提问吗？')

    expect(wrapper.get('.memory-status').text()).toContain('全文理解')
    expect(wrapper.get('.memory-status').text()).toContain('500 Token')
    expect(sendButton(wrapper).attributes()).toHaveProperty('disabled')
    expect(wrapper.get('.assistant-composer textarea').attributes('placeholder'))
      .toBe('论文理解完成后即可提问')
    wrapper.unmount()
  })

  it('uses only a confirmed selection as the persistent conversation focus', async () => {
    mocks.state.run.mockResolvedValue({
      runId: 'selection-turn-1',
      result: { answer: '该方法解决估计问题。', claims: [], evidence: [] },
    })
    const wrapper = mountPanel({ selection: textSelection, selectionAnchor: textAnchor })
    await flushPromises()

    expect(wrapper.get('.selection-card__text').text()).toContain('selected method')
    expect(wrapper.text()).not.toContain('正文已映射')
    expect(wrapper.text()).toContain('确认并固定')
    expect(wrapper.get('.selection-chat__heading').text()).not.toContain('当前焦点')
    await wrapper.findAll('.selection-tools button')
      .find(button => button.text().includes('确认并固定')).trigger('click')
    await wrapper.get('.assistant-composer textarea').setValue('这段方法解决什么问题？')
    expect(wrapper.get('.selection-chat__heading').text()).toContain('当前焦点')
    expect(sendButton(wrapper).attributes()).not.toHaveProperty('disabled')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    expect(mocks.state.run).toHaveBeenCalledWith(expect.objectContaining({
      question: '这段方法解决什么问题？',
      selectionAnchor: textAnchor,
      conversationId: expect.stringMatching(/^session-91-/),
    }))
    expect(wrapper.emitted('clear-selection')).toBeUndefined()
    expect(wrapper.text()).toContain('已固定')
    expect(wrapper.text()).toContain('该方法解决估计问题')
  })

  it('distinguishes a recoverable region fallback from a precise text anchor', async () => {
    const wrapper = mountPanel({
      selection: textSelection,
      selectionAnchor: { ...textAnchor, kind: 'REGION', confidence: 0.42 },
    })
    await flushPromises()

    expect(wrapper.get('.warning-state').text()).toContain('只能定位到页面区域')
    expect(wrapper.get('.warning-state').text()).toContain('重新选择')
    expect(wrapper.get('.warning-state').text()).toContain('回原页核对')
  })

  it('explains local math enhancement for an exact math-rich selection', async () => {
    const wrapper = mountPanel({
      selection: textSelection,
      selectionAnchor: {
        ...textAnchor,
        mappingStatus: 'EXACT',
        contentType: 'MATH_RICH_TEXT',
      },
    })
    await flushPromises()

    expect(wrapper.find('.warning-state').exists()).toBe(false)
    expect(wrapper.get('.math-rich-state').text()).toContain('多个行内数学片段')
    expect(wrapper.get('.math-rich-state').text()).toContain('本地 LaTeX')
    expect(wrapper.get('.math-rich-state').text()).toContain('原页排版为准')
  })

  it('does not render a long source crop for an ordinary text selection', async () => {
    const wrapper = mountPanel({
      selection: {
        text: 'ensures E ssH = I.',
        visualFallback: {
          dataUrl: 'data:image/png;base64,preview',
          reason: 'PDF 数学字体包含无法可靠映射的字符，公式以原页图像为准。',
        },
      },
      selectionAnchor: textAnchor,
    })
    await flushPromises()

    expect(wrapper.find('.selection-source-preview').exists()).toBe(false)
    expect(wrapper.get('.selection-card__text').text()).not.toContain('□')
  })

  it('keeps follow-up questions in one grounded conversation', async () => {
    mocks.state.run
      .mockResolvedValueOnce({
        runId: 'turn-1',
        result: { answer: '第一轮回答', claims: [], evidence: [] },
      })
      .mockResolvedValueOnce({
        runId: 'turn-2',
        result: { answer: '第二轮回答', claims: [], evidence: [] },
      })
    const wrapper = mountPanel({ selection: textSelection, selectionAnchor: textAnchor })
    await flushPromises()
    await wrapper.findAll('.selection-tools button')
      .find(button => button.text().includes('确认并固定')).trigger('click')
    await wrapper.get('.assistant-composer textarea').setValue('第一问')
    await sendButton(wrapper).trigger('click')
    await flushPromises()
    await wrapper.get('.selection-clear-action').trigger('click')
    await wrapper.setProps({ selection: null, selectionAnchor: null })
    await wrapper.get('.assistant-composer textarea').setValue('第二问')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    const first = mocks.state.run.mock.calls[0][0]
    const second = mocks.state.run.mock.calls[1][0]
    expect(second.conversationId).toBe(first.conversationId)
    expect(second).not.toHaveProperty('conversationContext')
    expect(wrapper.findAll('.chat-message')).toHaveLength(4)
  })

  it('continues the existing conversation after the current selection is cleared', async () => {
    mocks.state.run
      .mockResolvedValueOnce({
        runId: 'turn-before-clear',
        result: { answer: '第一轮回答', claims: [], evidence: [] },
      })
      .mockResolvedValueOnce({
        runId: 'turn-after-clear',
        result: { answer: '连续追问回答', claims: [], evidence: [] },
      })
      .mockResolvedValueOnce({
        runId: 'turn-new-conversation',
        result: { answer: '独立对话回答', claims: [], evidence: [] },
      })
    const wrapper = mountPanel({ selection: textSelection, selectionAnchor: textAnchor })
    await flushPromises()
    await wrapper.findAll('.selection-tools button')
      .find(button => button.text().includes('确认并固定')).trigger('click')
    await wrapper.get('.assistant-composer textarea').setValue('第一问')
    await sendButton(wrapper).trigger('click')
    await flushPromises()
    const firstRequest = mocks.state.run.mock.calls[0][0]

    await wrapper.get('.selection-clear-action').trigger('click')
    await wrapper.setProps({ selection: null, selectionAnchor: null })
    await wrapper.get('.assistant-composer textarea').setValue('没有新选区时继续追问')

    expect(wrapper.get('.selection-chat__heading').text()).toContain('沿用本对话历史与论文理解')
    expect(wrapper.get('.assistant-composer textarea').attributes('placeholder'))
      .toContain('继续当前对话')
    expect(sendButton(wrapper).attributes()).not.toHaveProperty('disabled')

    await sendButton(wrapper).trigger('click')
    await flushPromises()

    const secondRequest = mocks.state.run.mock.calls[1][0]
    expect(secondRequest.conversationId).toBe(firstRequest.conversationId)
    expect(secondRequest).not.toHaveProperty('selectionAnchor')
    expect(secondRequest.scope).toBe('PAPER')
    expect(wrapper.findAll('.chat-message.is-user')[1].text()).toContain('沿用对话上下文')

    await wrapper.findAll('.selection-chat__actions button')
      .find(button => button.text() === '新对话').trigger('click')
    expect(wrapper.findAll('.chat-message')).toHaveLength(0)
    expect(wrapper.get('.selection-chat__heading').text()).toContain('基于论文理解开始对话')
    await wrapper.get('.assistant-composer textarea').setValue('新对话基于论文理解')
    expect(sendButton(wrapper).attributes()).not.toHaveProperty('disabled')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    const newConversationRequest = mocks.state.run.mock.calls[2][0]
    expect(newConversationRequest.conversationId).not.toBe(firstRequest.conversationId)
    expect(newConversationRequest).not.toHaveProperty('selectionAnchor')
    expect(wrapper.findAll('.chat-message')).toHaveLength(2)
  })

  it('offers existing conversations for the paper and starts a new independent session', async () => {
    mocks.listResearchSessions.mockResolvedValue([
      { id: 91, primaryPaperId: 1, title: '方法讨论', messageCount: 4, lastActivityAt: '2026-07-29T10:00:00' },
      { id: 92, primaryPaperId: 1, title: '实验讨论', messageCount: 2, lastActivityAt: '2026-07-29T11:00:00' },
      { id: 93, primaryPaperId: 2, title: '其他论文', messageCount: 8, lastActivityAt: '2026-07-29T12:00:00' },
    ])
    mocks.getResearchSession.mockResolvedValue({ messages: [], runs: [] })
    mocks.state.run.mockResolvedValue({
      runId: 'new-session-run',
      result: { answer: '回答', claims: [], evidence: [] },
    })
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.get('.conversation-picker').text()).toContain('方法讨论')
    expect(wrapper.get('.conversation-picker').text()).toContain('实验讨论')
    expect(wrapper.get('.conversation-picker').text()).not.toContain('其他论文')

    await wrapper.findAll('.conversation-picker__item')[0].trigger('click')
    await flushPromises()
    expect(wrapper.emitted('research-session-change')).toContainEqual([91])
    expect(mocks.getResearchSession).toHaveBeenCalledWith(91)

    await wrapper.findAll('.selection-chat__actions button')
      .find(button => button.text() === '新对话').trigger('click')
    expect(wrapper.emitted('research-session-change')).toContainEqual([null])

    await wrapper.get('.assistant-composer textarea').setValue('这个方法的核心假设是什么？')
    await sendButton(wrapper).trigger('click')
    await flushPromises()
    expect(mocks.createResearchSession).toHaveBeenCalledWith(expect.objectContaining({
      primaryPaperId: 1,
      title: '这个方法的核心假设是什么？',
    }))
  })

  it('does not restore a conversation that belongs to another paper', async () => {
    mocks.getResearchSession.mockResolvedValue({
      session: { id: 91, primaryPaperId: 2, papers: [{ id: 2, title: '其他论文' }] },
      messages: [{ id: 1, role: 'USER', content: '不应出现', evidence: {} }],
      runs: [],
    })
    const wrapper = mountPanel({ researchSessionId: 91 })
    await flushPromises()

    expect(wrapper.emitted('research-session-change')).toContainEqual([null])
    expect(wrapper.findAll('.chat-message')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('不应出现')
  })

  it('renders math and tables while keeping evidence collapsed by default', async () => {
    mocks.state.run.mockResolvedValue({
      runId: 'rich-answer',
      result: {
        answer: '变量取值为 1。\n\n| 变量 | 值 |\n| --- | --- |\n| $x$ | 1 |',
        claims: [{ text: '变量取值为 1', evidenceIds: ['e-1'] }],
        evidence: [{ evidenceId: 'e-1', page: 2, text: 'x = 1' }],
      },
    })
    const wrapper = mountPanel({ selection: textSelection, selectionAnchor: textAnchor })
    await flushPromises()
    await wrapper.get('.assistant-composer textarea').setValue('给出表格')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    expect(wrapper.find('.answer-text table').exists()).toBe(true)
    expect(wrapper.find('.answer-text .katex').exists()).toBe(true)
    const inlineCitation = wrapper.get('.answer-text a[href="#evidence-e-1"]')
    await inlineCitation.trigger('click')
    expect(wrapper.emitted('jump-evidence')?.[0][0]).toEqual(
      expect.objectContaining({ evidenceId: 'e-1', page: 2 }),
    )
    const details = wrapper.get('.chat-claim-list')
    expect(details.attributes('open')).toBeUndefined()
    expect(details.get('summary').text()).toContain('查看依据（1）')
    await details.get('.evidence-links button').trigger('click')
    expect(wrapper.emitted('jump-evidence')?.[1][0]).toEqual(
      expect.objectContaining({ evidenceId: 'e-1', page: 2 }),
    )
  })

  it('keeps the conversation while each valid new selection replaces the next-message anchor', async () => {
    mocks.state.run.mockResolvedValue({
      runId: 'turn', result: { answer: '回答', claims: [], evidence: [] },
    })
    const wrapper = mountPanel({ selection: textSelection, selectionAnchor: textAnchor })
    await flushPromises()
    await wrapper.findAll('.selection-tools button')
      .find(button => button.text().includes('确认并固定')).trigger('click')
    await wrapper.get('.assistant-composer textarea').setValue('第一问')
    await sendButton(wrapper).trigger('click')
    await flushPromises()
    const firstConversation = mocks.state.run.mock.calls[0][0].conversationId
    await wrapper.get('.selection-clear-action').trigger('click')
    await wrapper.setProps({ selection: null, selectionAnchor: null })

    await wrapper.setProps({
      selection: { text: 'A different selected passage.' },
      selectionAnchor: { ...textAnchor, page: 3 },
    })
    await flushPromises()
    await wrapper.findAll('.selection-tools button')
      .find(button => button.text().includes('确认并固定')).trigger('click')
    expect(wrapper.text()).toContain('第一问')
    await wrapper.get('.assistant-composer textarea').setValue('新选区问题')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    expect(mocks.state.run.mock.calls[1][0].conversationId).toBe(firstConversation)
    expect(mocks.state.run.mock.calls[1][0].selectionAnchor.page).toBe(3)
    expect(wrapper.text()).toContain('引用第 2 页选区')
    expect(wrapper.text()).toContain('引用第 3 页选区')
  })

  it('restores the latest server conversation id from the research archive', async () => {
    mocks.getResearchSession.mockResolvedValue({
      messages: [
        { messageKey: 'old:user', runId: 'old', role: 'USER', content: '旧对话' },
        { messageKey: 'latest:user', runId: 'latest', role: 'USER', content: '当前对话' },
      ],
      runs: [
        {
          runId: 'latest',
          plan: { workflow: 'SELECTION_QA' },
          invocation: { conversationId: 'session-91-restored' },
        },
        {
          runId: 'old',
          plan: { workflow: 'SELECTION_QA' },
          invocation: { conversationId: 'session-91-old' },
        },
      ],
    })
    mocks.state.run.mockResolvedValue({
      runId: 'restored-turn', result: { answer: '恢复回答', claims: [], evidence: [] },
    })
    const wrapper = mountPanel({
      researchSessionId: 91, selection: textSelection, selectionAnchor: textAnchor,
    })
    await flushPromises()
    expect(wrapper.text()).toContain('当前对话')
    expect(wrapper.text()).not.toContain('旧对话')
    await wrapper.get('.assistant-composer textarea').setValue('继续追问')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    expect(mocks.state.run.mock.calls[0][0].conversationId).toBe('session-91-restored')
    expect(mocks.createResearchSession).not.toHaveBeenCalled()
  })

  it('renders the editable formula card and accepts only a confirmed formula anchor', async () => {
    const formulaAnchor = {
      paperId: 1,
      page: 3,
      boxes: [{ x: 0.2, y: 0.3, width: 0.4, height: 0.1 }],
      anchorText: '\\sum_{k=1}^{K} r_k',
      blockIds: ['formula-region-9'],
      kind: 'FORMULA',
      confidence: 1,
    }
    const wrapper = mountPanel({
      captureMode: 'formula',
      formulaRegion: { page: 3, bbox: formulaAnchor.boxes[0] },
      formulaRecognition: {
        id: 9,
        latex: '\\sum_{k=1}^{K} r_k',
        source: 'MULTIMODAL',
        status: 'CANDIDATE',
        confirmed: false,
        anchor: null,
      },
    })
    await flushPromises()

    expect(wrapper.findComponent(FormulaRegionCard).exists()).toBe(true)
    await wrapper.get('.assistant-composer textarea').setValue('解释这个公式')
    expect(sendButton(wrapper).attributes()).not.toHaveProperty('disabled')

    await wrapper.setProps({
      formulaRecognition: {
        id: 9,
        latex: '\\sum_{k=1}^{K} r_k',
        source: 'USER',
        status: 'CONFIRMED',
        confirmed: true,
        anchor: formulaAnchor,
      },
    })
    await wrapper.get('.assistant-composer textarea').setValue('解释这个公式')
    expect(sendButton(wrapper).attributes()).not.toHaveProperty('disabled')
  })

  it('translates the displayed selection without replacing its source text', async () => {
    mocks.translateTexts.mockResolvedValue({
      targetLanguage: 'ZH',
      items: [{ text: '所选方法解决估计问题。' }],
    })
    const wrapper = mountPanel({ selection: textSelection, selectionAnchor: textAnchor })
    await flushPromises()

    const translate = wrapper.findAll('.selection-tools button')
      .find(button => button.text().includes('翻译为中文'))
    await translate.trigger('click')
    await flushPromises()

    expect(mocks.translateTexts).toHaveBeenCalledWith({
      texts: [textSelection.text],
      sourceLanguage: 'EN',
      targetLanguage: 'ZH',
    })
    expect(wrapper.get('.selection-card__text').text()).toContain('selected method')
    expect(wrapper.get('.selection-translation').text()).toContain('所选方法')
  })
})

function mountPanel(extraProps = {}) {
  return mount(PaperWorkbenchPanel, {
    props: { paper, ...extraProps },
    global: { stubs: defaultStubs },
  })
}

function sendButton(wrapper) {
  return wrapper.findAll('.assistant-composer button')
    .find(button => button.text().includes('发送'))
}
