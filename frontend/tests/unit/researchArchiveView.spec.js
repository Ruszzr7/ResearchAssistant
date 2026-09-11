import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ResearchArchiveView from '@/views/ResearchArchiveView.vue'

const mocks = vi.hoisted(() => ({
  listResearchSessions: vi.fn(),
  normalizeResearchSessionPage: vi.fn(value => Array.isArray(value)
    ? { records: value, total: value.length, current: 1, size: value.length || 1, pages: value.length ? 1 : 0 }
    : value),
  getResearchSession: vi.fn(),
  routerPush: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.routerPush }),
}))
vi.mock('@/api/researchArchive.js', () => ({
  listResearchSessions: mocks.listResearchSessions,
  normalizeResearchSessionPage: mocks.normalizeResearchSessionPage,
  getResearchSession: mocks.getResearchSession,
  deleteResearchSession: vi.fn(),
  updateResearchSession: vi.fn(),
}))

const passthrough = { template: '<div><slot /><slot name="header" /><slot name="footer" /></div>' }
const paginationStub = {
  props: ['currentPage', 'pageSize', 'total'],
  template: '<button class="pagination-stub" @click="$emit(\'current-change\', 2)">分页</button>',
}
const buttonStub = {
  emits: ['click'],
  template: '<button @click="$emit(\'click\')"><slot /></button>',
}
const alertStub = {
  props: ['title'],
  template: '<div class="alert">{{ title }}<slot /></div>',
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
          'el-alert': alertStub,
          'el-pagination': paginationStub,
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

  it('shows a retryable state when archive loading fails', async () => {
    mocks.listResearchSessions.mockRejectedValueOnce(new Error('档案服务不可用'))
    const wrapper = mount(ResearchArchiveView, {
      global: {
        directives: { loading: () => {} },
        stubs: {
          'el-button': buttonStub,
          'el-input': passthrough,
          'el-tabs': passthrough,
          'el-tab-pane': passthrough,
          'el-alert': alertStub,
          'el-empty': passthrough,
          'el-drawer': passthrough,
          'el-pagination': paginationStub,
        },
      },
    })
    await flushPromises()

    expect(wrapper.find('.alert').text()).toContain('档案服务不可用')
    expect(wrapper.find('.alert button').text()).toContain('重试')
  })

  it('replays saved evidence with full text and a real paper jump target', async () => {
    mocks.getResearchSession.mockResolvedValue({
      session: {
        id: 31,
        primaryPaperId: 184,
        title: '历史对话',
        runCount: 1,
        lastPage: 1,
        papers: [{ id: 184, title: '论文 A' }],
      },
      messages: [{
        id: 101,
        messageKey: 'assistant-101',
        role: 'ASSISTANT',
        content: '核心创新是联合优化。',
        evidence: {
          citations: [{ answerStart: 0, answerEnd: 9, sourceObjectId: 'source-a' }],
          evidence: [{
            evidenceId: 'source-a', paperId: 184, page: 6,
            quote: '联合优化', fullText: '第一行完整内容。第二行完整内容。',
            locators: [{ pageNumber: 6, contentRects: [
              { x: 0.1, y: 0.2, width: 0.3, height: 0.02 },
              { x: 0.1, y: 0.24, width: 0.25, height: 0.02 },
            ] }],
          }],
        },
      }],
    })
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
          'el-alert': alertStub,
          'el-pagination': paginationStub,
        },
      },
    })
    await flushPromises()
    await wrapper.get('.conversation-card').trigger('click')
    await flushPromises()

    expect(wrapper.get('.archive-message__content').text()).toContain('核心创新是联合优化')
    expect(wrapper.get('.archive-message__content a').attributes('href')).toBe('#evidence-source-a')
    expect(wrapper.get('.evidence-source-list').text()).toContain('第一行完整内容。第二行完整内容。')
    await wrapper.get('.evidence-source__jump').trigger('click')

    expect(mocks.routerPush).toHaveBeenCalledWith(expect.objectContaining({
      path: '/research/184',
      query: expect.objectContaining({ session: '31', page: '6', returnTo: '/archive' }),
    }))
  })

  it('passes page metadata to the archive list and exposes pagination', async () => {
    mocks.listResearchSessions.mockResolvedValue({
      records: [{ id: 44, primaryPaperId: 1, title: '第 1 页', papers: [{ id: 1, title: '论文 A' }] }],
      total: 21,
      current: 1,
      size: 20,
      pages: 2,
    })
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
          'el-alert': alertStub,
          'el-pagination': paginationStub,
        },
      },
    })
    await flushPromises()

    expect(mocks.listResearchSessions).toHaveBeenCalledWith({
      archived: false, keyword: undefined, page: 1, size: 20,
    })
    expect(wrapper.find('.archive-pagination').exists()).toBe(true)
  })

  it('does not offer a fake jump for a historical source without locators', async () => {
    mocks.getResearchSession.mockResolvedValue({
      session: { id: 32, primaryPaperId: 184, title: '无定位历史', papers: [{ id: 184, title: '论文 A' }] },
      messages: [{
        id: 102, messageKey: 'assistant-102', role: 'ASSISTANT', content: '论文事实。',
        evidence: {
          citations: [{ answerStart: 0, answerEnd: 4, sourceObjectId: 'legacy-source' }],
          evidence: [{ evidenceId: 'legacy-source', paperId: 184, page: 2, quote: '论文事实' }],
        },
      }],
    })
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
          'el-alert': alertStub,
          'el-pagination': paginationStub,
        },
      },
    })
    await flushPromises()
    await wrapper.get('.conversation-card').trigger('click')
    await flushPromises()
    await wrapper.get('.evidence-source-list summary').trigger('click')

    expect(wrapper.find('.evidence-source__jump').exists()).toBe(false)
    expect(mocks.routerPush).not.toHaveBeenCalled()
  })
})
