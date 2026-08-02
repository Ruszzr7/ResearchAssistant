import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ResearchArchiveView from '@/views/ResearchArchiveView.vue'

const mocks = vi.hoisted(() => ({
  listResearchSessions: vi.fn(),
  getResearchSession: vi.fn(),
  routerPush: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.routerPush }),
}))
vi.mock('@/api/researchArchive.js', () => ({
  listResearchSessions: mocks.listResearchSessions,
  getResearchSession: mocks.getResearchSession,
  deleteResearchSession: vi.fn(),
  updateResearchSession: vi.fn(),
}))

const passthrough = { template: '<div><slot /><slot name="header" /><slot name="footer" /></div>' }
const buttonStub = {
  emits: ['click'],
  template: '<button @click="$emit(\'click\')"><slot /></button>',
}

describe('ResearchArchiveView', () => {
  beforeEach(() => {
    mocks.routerPush.mockReset()
    mocks.getResearchSession.mockReset().mockResolvedValue({ session: {}, messages: [], runs: [] })
    mocks.listResearchSessions.mockReset().mockResolvedValue([
      {
        id: 11,
        primaryPaperId: 1,
        title: '方法讨论',
        messageCount: 4,
        runCount: 2,
        lastPage: 3,
        lastActivityAt: '2026-07-29T09:00:00',
        papers: [{ id: 1, title: '论文 A' }],
      },
      {
        id: 12,
        primaryPaperId: 1,
        title: '实验讨论',
        messageCount: 2,
        runCount: 1,
        lastPage: 5,
        lastActivityAt: '2026-07-29T10:00:00',
        papers: [{ id: 1, title: '论文 A' }],
      },
      {
        id: 21,
        primaryPaperId: 2,
        title: '结论讨论',
        messageCount: 2,
        runCount: 1,
        lastPage: 4,
        lastActivityAt: '2026-07-29T08:00:00',
        papers: [{ id: 2, title: '论文 B' }],
      },
    ])
  })

  it('groups independent conversation records under their paper', async () => {
    const wrapper = mount(ResearchArchiveView, {
      global: {
        directives: { loading: () => {} },
        stubs: {
          'el-button': buttonStub,
          'el-input': passthrough,
          'el-tabs': passthrough,
          'el-tab-pane': passthrough,
          'el-tag': passthrough,
          'el-empty': passthrough,
          'el-drawer': passthrough,
        },
      },
    })
    await flushPromises()

    expect(wrapper.findAll('.paper-column button')).toHaveLength(2)
    expect(wrapper.find('.paper-column').text()).toContain('论文 A')
    expect(wrapper.find('.paper-column').text()).toContain('2 个对话')
    expect(wrapper.findAll('.conversation-card')).toHaveLength(2)
    expect(wrapper.find('.conversation-column').text()).toContain('实验讨论')
    expect(wrapper.find('.conversation-column').text()).toContain('2 条消息')

    await wrapper.findAll('.paper-column button')[1].trigger('click')
    expect(wrapper.findAll('.conversation-card')).toHaveLength(1)
    expect(wrapper.find('.conversation-column').text()).toContain('结论讨论')
    expect(wrapper.find('.conversation-column').text()).not.toContain('方法讨论')
  })
})
