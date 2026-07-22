import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PaperWorkbenchPanel from '@/components/pdf/PaperWorkbenchPanel.vue'
import FormulaRegionCard from '@/components/pdf/FormulaRegionCard.vue'

const mocks = vi.hoisted(() => ({
  translateTexts: vi.fn(),
  createResearchSession: vi.fn(),
  getResearchSession: vi.fn(),
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

  it('shows exactly the three redesigned product tabs and keeps future areas as placeholders', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    const tabs = wrapper.findAll('.product-tabs button')
    expect(tabs.map(tab => tab.text())).toEqual(['论文精读', '缺陷分析', '论文对比'])
    expect(tabs[0].classes()).toContain('active')

    await tabs[1].trigger('click')
    expect(wrapper.get('.future-feature').text()).toContain('缺陷分析')
    expect(wrapper.emitted('mode-change')?.[0]).toEqual(['PAPER_IMPROVEMENT'])
    expect(mocks.state.run).not.toHaveBeenCalled()

    await wrapper.findAll('.product-tabs button')[2].trigger('click')
    expect(wrapper.get('.future-feature').text()).toContain('论文对比')
    expect(wrapper.emitted('mode-change')?.[1]).toEqual(['PAPER_COMPARISON'])
  })

  it('switches content and formula capture from the sliding selector', async () => {
    const wrapper = mountPanel({ captureMode: 'text' })
    const buttons = wrapper.findAll('.capture-switch button')
    expect(buttons.map(button => button.text())).toEqual(['内容选取', '公式框选'])
    expect(buttons[0].classes()).toContain('active')

    await buttons[1].trigger('click')
    expect(wrapper.emitted('capture-mode-change')?.[0]).toEqual(['formula'])

    await wrapper.setProps({ captureMode: 'formula' })
    expect(wrapper.get('.capture-switch').classes()).toContain('is-formula')
    expect(wrapper.findAll('.capture-switch button')[1].classes()).toContain('active')
  })

  it('shows non-blocking whole-paper understanding progress and retries partial memory', async () => {
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

  it('requires explicit text confirmation before enabling the GPT-style composer', async () => {
    mocks.state.run.mockResolvedValue({
      runId: 'selection-turn-1',
      result: { answer: '该方法解决估计问题。', claims: [], evidence: [] },
    })
    const wrapper = mountPanel({ selection: textSelection, selectionAnchor: textAnchor })
    await flushPromises()

    expect(wrapper.get('.selection-card__text').text()).toContain('selected method')
    expect(wrapper.text()).not.toContain('正文已映射')
    await wrapper.get('.assistant-composer textarea').setValue('这段方法解决什么问题？')
    expect(sendButton(wrapper).attributes()).toHaveProperty('disabled')

    await confirmButton(wrapper).trigger('click')
    expect(wrapper.text()).toContain('已固定')
    expect(sendButton(wrapper).attributes()).not.toHaveProperty('disabled')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    expect(mocks.state.run).toHaveBeenCalledWith(expect.objectContaining({
      question: '这段方法解决什么问题？',
      selectionAnchor: textAnchor,
      conversationId: expect.stringMatching(/^session-91-/),
    }))
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
    await confirmButton(wrapper).trigger('click')

    await wrapper.get('.assistant-composer textarea').setValue('第一问')
    await sendButton(wrapper).trigger('click')
    await flushPromises()
    await wrapper.get('.assistant-composer textarea').setValue('第二问')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    const first = mocks.state.run.mock.calls[0][0]
    const second = mocks.state.run.mock.calls[1][0]
    expect(second.conversationId).toBe(first.conversationId)
    expect(second).not.toHaveProperty('conversationContext')
    expect(wrapper.findAll('.chat-message')).toHaveLength(4)
  })

  it('starts a new server conversation when the selected content changes', async () => {
    mocks.state.run.mockResolvedValue({
      runId: 'turn', result: { answer: '回答', claims: [], evidence: [] },
    })
    const wrapper = mountPanel({ selection: textSelection, selectionAnchor: textAnchor })
    await flushPromises()
    await confirmButton(wrapper).trigger('click')
    await wrapper.get('.assistant-composer textarea').setValue('第一问')
    await sendButton(wrapper).trigger('click')
    await flushPromises()
    const firstConversation = mocks.state.run.mock.calls[0][0].conversationId

    await wrapper.setProps({
      selection: { text: 'A different selected passage.' },
      selectionAnchor: { ...textAnchor, page: 3 },
    })
    await flushPromises()
    await confirmButton(wrapper).trigger('click')
    await wrapper.get('.assistant-composer textarea').setValue('新选区问题')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    expect(mocks.state.run.mock.calls[1][0].conversationId).not.toBe(firstConversation)
  })

  it('restores the latest server conversation id from the research archive', async () => {
    mocks.getResearchSession.mockResolvedValue({
      messages: [],
      runs: [{
        plan: { workflow: 'SELECTION_QA' },
        invocation: { conversationId: 'session-91-restored' },
      }],
    })
    mocks.state.run.mockResolvedValue({
      runId: 'restored-turn', result: { answer: '恢复回答', claims: [], evidence: [] },
    })
    const wrapper = mountPanel({
      researchSessionId: 91, selection: textSelection, selectionAnchor: textAnchor,
    })
    await flushPromises()
    await confirmButton(wrapper).trigger('click')
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
    expect(sendButton(wrapper).attributes()).toHaveProperty('disabled')

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

function confirmButton(wrapper) {
  return wrapper.findAll('.selection-tools button')
    .find(button => button.text().includes('确认并固定'))
}

function sendButton(wrapper) {
  return wrapper.findAll('.assistant-composer button')
    .find(button => button.text().includes('发送'))
}
