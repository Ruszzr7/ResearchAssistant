import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PaperWorkbenchPanel from '@/components/pdf/PaperWorkbenchPanel.vue'

const mocks = vi.hoisted(() => ({
  listPapers: vi.fn(),
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
vi.mock('@/composables/usePaperWorkbench.js', () => ({
  usePaperWorkbench: () => mocks.state,
}))

const passthrough = { template: '<div><slot /></div>' }
const buttonStub = { template: '<button :disabled="$attrs.disabled"><slot /></button>' }

describe('PaperWorkbenchPanel comparison result', () => {
  beforeEach(() => {
    mocks.listPapers.mockReset()
    mocks.state.loadRecent.mockReset()
    mocks.state.selectRun.mockReset()
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
        stubs: {
          'el-tag': passthrough,
          'el-select': passthrough,
          'el-option': true,
          'el-input': true,
          'el-button': buttonStub,
        },
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
        stubs: {
          'el-tag': passthrough,
          'el-select': passthrough,
          'el-option': true,
          'el-input': true,
          'el-button': buttonStub,
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('用户真正选中的句子')
    expect(wrapper.find('.compact-evidence-list').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('所有结论均需通过证据门禁')
    expect(wrapper.findAll('.trace-dot-item')).toHaveLength(4)
    expect(wrapper.findAll('.trace-dot-item')[2].classes()).toContain('is-running')
    expect(wrapper.findAll('.trace-dot-item')[2].attributes('title')).toContain('生成回答：执行中')
    expect(wrapper.find('.run-card').text()).not.toContain('证据门禁')
  })

  it('opens the routed Gap mode and submits three papers through the fixed workflow', async () => {
    mocks.listPapers.mockResolvedValue([
      { id: 2, title: 'Paper 2', pdfPath: '2.pdf' },
      { id: 3, title: 'Paper 3', pdfPath: '3.pdf' },
    ])
    mocks.state.trace.value = null
    const wrapper = mount(PaperWorkbenchPanel, {
      props: {
        paper: { id: 1, title: 'Current Paper', pdfPath: '1.pdf' },
        initialMode: 'RESEARCH_GAP',
        initialPaperIds: [1, 2, 3],
      },
      global: {
        stubs: {
          'el-tag': passthrough,
          'el-select': passthrough,
          'el-option': true,
          'el-input': true,
          'el-button': buttonStub,
        },
      },
    })
    await flushPromises()

    expect(wrapper.findAll('.workflow-tabs button').at(-1).classes()).toContain('active')
    expect(wrapper.text()).toContain('已选择 3 篇论文，可以开始识别候选 Gap')
    const action = wrapper.findAll('button').find(button => button.text().includes('识别候选 Gap'))
    await action.trigger('click')
    await flushPromises()
    expect(mocks.state.run).toHaveBeenCalledWith(expect.objectContaining({
      paperIds: [1, 2, 3],
      intent: 'FIND_RESEARCH_GAPS',
      scope: 'COMPARISON',
    }))
  })
})
