import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PaperWorkbenchPanel from '@/components/pdf/PaperWorkbenchPanel.vue'

const mocks = vi.hoisted(() => ({
  listPapers: vi.fn(),
  translateTexts: vi.fn(),
  state: {
    trace: { __v_isRef: true, value: null },
    recentRuns: { __v_isRef: true, value: [] },
    running: { __v_isRef: true, value: false },
    stageText: { __v_isRef: true, value: '' },
    error: { __v_isRef: true, value: '' },
    run: vi.fn(),
    loadRecent: vi.fn(),
    selectRun: vi.fn(),
  },
}))

vi.mock('@/api/paper.js', () => ({ listPapers: mocks.listPapers }))
vi.mock('@/api/workbench.js', () => ({ translateTexts: mocks.translateTexts }))
vi.mock('@/composables/usePaperWorkbench.js', () => ({
  usePaperWorkbench: () => mocks.state,
}))

const passthrough = { template: '<div><slot /></div>' }
const buttonStub = { template: '<button :disabled="$attrs.disabled"><slot /></button>' }
const inputStub = {
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
}
const defaultStubs = {
  'el-tag': passthrough,
  'el-select': passthrough,
  'el-option': true,
  'el-input': inputStub,
  'el-button': buttonStub,
}

describe('PaperWorkbenchPanel comparison result', () => {
  beforeEach(() => {
    mocks.listPapers.mockReset()
    mocks.state.loadRecent.mockReset()
    mocks.state.selectRun.mockReset()
    mocks.state.run.mockReset()
    mocks.translateTexts.mockReset()
    mocks.state.running.value = false
    mocks.state.stageText.value = ''
    mocks.state.error.value = ''
    mocks.state.selectRun.mockImplementation(run => { mocks.state.trace.value = run })
    mocks.listPapers.mockResolvedValue([{ id: 2, title: 'Comparison Paper' }])
    mocks.state.loadRecent.mockResolvedValue([])
    mocks.state.trace.value = {
      runId: 'run-compare',
      status: 'COMPLETED',
      invocation: { paperIds: [1, 2] },
      plan: { workflow: 'PAPER_COMPARISON' },
      steps: [],
      result: {
        answer: 'Comparison result',
        paperIds: [1, 2],
        evidence: [
          { evidenceId: 'e1', paperId: 1, page: 2, text: 'first evidence' },
          { evidenceId: 'e2', paperId: 2, page: 5, text: 'second evidence' },
        ],
        claims: [
          { text: 'First claim', evidenceIds: ['e1'] },
          { text: 'Second claim', evidenceIds: ['e2'] },
        ],
      },
    }
  })

  it('renders titles and per-paper citation coverage instead of raw IDs', async () => {
    const wrapper = mount(PaperWorkbenchPanel, {
      props: { paper: { id: 1, title: 'Current Paper' } },
      global: {
        stubs: defaultStubs,
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('逐论文证据覆盖')
    expect(wrapper.text()).toContain('Current Paper')
    expect(wrapper.text()).toContain('Comparison Paper')
    expect(wrapper.findAll('.coverage-row')).toHaveLength(2)
    expect(wrapper.findAll('.coverage-row').every(row => row.classes().includes('covered'))).toBe(true)
    expect(wrapper.text()).not.toContain('论文 1 ·')
    expect(wrapper.text()).not.toContain('论文 2 ·')
    expect(wrapper.findAll('.workflow-tabs button')).toHaveLength(4)
    expect(wrapper.text()).not.toContain('批注建议')
    expect(wrapper.text()).not.toContain('添加内容')
    expect(wrapper.text()).not.toContain('添加选中内容')

    await wrapper.findAll('.coverage-row')[1].trigger('click')
    expect(wrapper.emitted('jump-evidence')?.[0]?.[0]).toMatchObject({
      evidenceId: 'e2', paperId: 2, page: 5,
    })

    await wrapper.get('.workflow-tabs button:nth-child(2)').trigger('click')
    await flushPromises()
    expect(mocks.state.selectRun).toHaveBeenCalledWith(null)
    expect(wrapper.find('.result-card').exists()).toBe(false)
  })

  it('shows only the exact selection and renders four compact status dots', async () => {
    mocks.state.running.value = true
    mocks.state.trace.value = {
      runId: 'run-selection',
      status: 'RUNNING',
      invocation: { paperIds: [1] },
      plan: { workflow: 'SELECTION_QA' },
      steps: [
        { index: 0, name: '解析选区', status: 'COMPLETED', latencyMs: 10 },
        { index: 1, name: '检索局部证据', status: 'COMPLETED', evidenceCount: 3, latencyMs: 10 },
        { index: 2, name: '生成回答', status: 'RUNNING', totalTokens: 400, latencyMs: 10 },
        { index: 3, name: '证据门禁', status: 'PENDING', latencyMs: 0 },
      ],
    }
    const wrapper = mount(PaperWorkbenchPanel, {
      props: {
        paper: { id: 1, title: 'Current Paper' },
        selection: { text: '用户真正选中的句子' },
        selectionAnchor: { kind: 'TEXT', page: 2, confidence: 0.96 },
      },
      global: {
        stubs: defaultStubs,
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('用户真正选中的句子')
    expect(wrapper.text()).toContain('选区对话')
    expect(wrapper.find('.compact-evidence-list').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('所有结论均需通过证据门禁')
    expect(wrapper.findAll('.trace-dot-item')).toHaveLength(4)
    expect(wrapper.findAll('.trace-dot-item')[2].classes()).toContain('is-running')
    expect(wrapper.findAll('.trace-dot-item')[2].attributes('title')).toContain('生成回答：执行中')
    expect(wrapper.find('.run-card').exists()).toBe(false)
  })

  it('translates only the exact selection and preserves the original text', async () => {
    mocks.state.trace.value = null
    mocks.translateTexts.mockResolvedValue({
      provider: 'deepl',
      targetLanguage: 'ZH',
      items: [{ text: '有限块长速率 $R_k$ 见式 [12]。', detectedSourceLanguage: 'EN' }],
    })
    const wrapper = mount(PaperWorkbenchPanel, {
      props: {
        paper: { id: 1, title: 'Current Paper' },
        selection: { text: 'The finite-blocklength rate $R_k$ follows [12].' },
        selectionAnchor: { kind: 'TEXT', page: 2, confidence: 0.96 },
      },
      global: { stubs: defaultStubs },
    })
    await flushPromises()

    const translateButton = wrapper.findAll('.selection-tools button')
      .find(button => button.text().includes('翻译选区为中文'))
    expect(translateButton).toBeTruthy()
    await translateButton.trigger('click')
    await flushPromises()

    expect(mocks.translateTexts).toHaveBeenCalledWith({
      texts: ['The finite-blocklength rate $R_k$ follows [12].'],
      sourceLanguage: 'EN',
      targetLanguage: 'ZH',
    })
    expect(wrapper.get('.selection-card p').text()).toContain('finite-blocklength')
    expect(wrapper.get('.selection-translation').text()).toContain('有限块长速率')
  })

  it('keeps follow-up questions in one grounded selection conversation', async () => {
    mocks.state.trace.value = null
    mocks.state.run
      .mockResolvedValueOnce({
        runId: 'selection-turn-1',
        result: {
          answer: '第一轮回答',
          claims: [{ text: '第一轮结论', evidenceIds: ['e1'] }],
          evidence: [{ evidenceId: 'e1', paperId: 1, page: 2, text: '证据一' }],
        },
      })
      .mockResolvedValueOnce({
        runId: 'selection-turn-2',
        result: {
          answer: '第二轮回答',
          claims: [{ text: '第二轮结论', evidenceIds: ['e2'] }],
          evidence: [{ evidenceId: 'e2', paperId: 1, page: 4, text: '证据二' }],
        },
      })
    const wrapper = mount(PaperWorkbenchPanel, {
      props: {
        paper: { id: 1, title: 'Current Paper' },
        selection: { text: 'The selected method.' },
        selectionAnchor: { kind: 'TEXT', page: 2, confidence: 0.96 },
        initialMode: 'SELECTION_QA',
      },
      global: { stubs: defaultStubs },
    })
    await flushPromises()

    const input = wrapper.get('.selection-chat textarea')
    await input.setValue('这段方法解决什么问题？')
    await wrapper.get('.selection-chat__actions button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('第一轮回答')
    const firstRequest = mocks.state.run.mock.calls[0][0]
    expect(firstRequest.conversationId).toMatch(/^selection-/)
    expect(firstRequest).not.toHaveProperty('conversationContext')

    await input.setValue('它和全文实验结果有什么关系？')
    await wrapper.get('.selection-chat__actions button').trigger('click')
    await flushPromises()

    const secondRequest = mocks.state.run.mock.calls[1][0]
    expect(secondRequest.conversationId).toBe(firstRequest.conversationId)
    expect(secondRequest.conversationContext).toContain('用户：这段方法解决什么问题？')
    expect(secondRequest.conversationContext).toContain('论文助手：第一轮回答')
    expect(wrapper.text()).toContain('第二轮回答')
    expect(wrapper.findAll('.chat-message')).toHaveLength(4)
  })

  it('switches an existing answer and claims without rerunning the workflow', async () => {
    mocks.state.trace.value = {
      runId: 'run-english',
      status: 'COMPLETED',
      invocation: { paperIds: [1] },
      plan: { workflow: 'PAPER_ANALYSIS' },
      steps: [],
      result: {
        answer: 'The paper studies finite blocklength.',
        claims: [{ text: 'It studies reliability.', evidenceIds: [] }],
        evidence: [],
      },
    }
    mocks.translateTexts.mockResolvedValue({
      provider: 'deepl',
      targetLanguage: 'ZH',
      items: [
        { text: '本文研究有限块长。', detectedSourceLanguage: 'EN' },
        { text: '本文研究可靠性。', detectedSourceLanguage: 'EN' },
      ],
    })
    const wrapper = mount(PaperWorkbenchPanel, {
      props: { paper: { id: 1, title: 'Current Paper' } },
      global: { stubs: defaultStubs },
    })
    await flushPromises()

    const languageButtons = wrapper.findAll('.result-language-switch button')
    expect(languageButtons[1].classes()).toContain('active')
    await languageButtons[0].trigger('click')
    await flushPromises()

    expect(mocks.translateTexts).toHaveBeenCalledWith({
      texts: ['The paper studies finite blocklength.', 'It studies reliability.'],
      sourceLanguage: 'EN',
      targetLanguage: 'ZH',
    })
    expect(wrapper.get('.answer-text').text()).toContain('本文研究有限块长')
    expect(wrapper.get('.claim-list').text()).toContain('本文研究可靠性')
    expect(mocks.state.run).not.toHaveBeenCalled()
  })

  it('starts field-gap analysis only from a completed three-paper comparison', async () => {
    mocks.listPapers.mockResolvedValue([
      { id: 2, title: 'Paper 2', pdfPath: '2.pdf' },
      { id: 3, title: 'Paper 3', pdfPath: '3.pdf' },
    ])
    mocks.state.trace.value = {
      runId: 'run-compare-three',
      status: 'COMPLETED',
      invocation: { paperIds: [1, 2, 3] },
      plan: { workflow: 'PAPER_COMPARISON' },
      steps: [],
      result: {
        answer: 'Comparison result',
        paperIds: [1, 2, 3],
        evidence: [],
        claims: [],
      },
    }
    const wrapper = mount(PaperWorkbenchPanel, {
      props: {
        paper: { id: 1, title: 'Current Paper', pdfPath: '1.pdf' },
        initialMode: 'PAPER_COMPARISON',
      },
      global: {
        stubs: defaultStubs,
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('继续分析领域研究空白')
    const action = wrapper.findAll('button').find(button => button.text().includes('分析领域研究空白'))
    await action.trigger('click')
    await flushPromises()
    expect(mocks.state.run).toHaveBeenCalledWith(expect.objectContaining({
      paperIds: [1, 2, 3],
      intent: 'FIND_RESEARCH_GAPS',
      scope: 'COMPARISON',
      sourceRunId: 'run-compare-three',
    }))
    expect(wrapper.findAll('.workflow-tabs button')[2].classes()).toContain('active')
  })

  it('submits paper improvement as a single-paper workflow', async () => {
    mocks.state.trace.value = null
    const wrapper = mount(PaperWorkbenchPanel, {
      props: { paper: { id: 1, title: 'Current Paper', pdfPath: '1.pdf' } },
      global: { stubs: defaultStubs },
    })
    await flushPromises()

    await wrapper.findAll('.workflow-tabs button')[3].trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.workflow-tabs button')[3].classes()).toContain('active')
    const action = wrapper.findAll('button').find(button => button.text().includes('分析改进空间'))
    await action.trigger('click')
    await flushPromises()
    expect(mocks.state.run).toHaveBeenCalledWith(expect.objectContaining({
      paperIds: [1],
      intent: 'IDENTIFY_PAPER_IMPROVEMENTS',
      scope: 'PAPER',
    }))
  })

})
