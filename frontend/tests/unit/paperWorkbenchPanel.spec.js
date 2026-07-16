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
})
