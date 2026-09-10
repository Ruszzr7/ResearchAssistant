import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DashboardView from '@/views/DashboardView.vue'

const mocks = vi.hoisted(() => ({
  getDashboard: vi.fn(),
}))

vi.mock('@/api/dashboard', () => ({ getDashboard: mocks.getDashboard }))

const passthrough = { template: '<div><slot /><slot name="header" /></div>' }
const buttonStub = {
  emits: ['click'],
  template: '<button @click="$emit(\'click\')"><slot /></button>',
}

describe('DashboardView', () => {
  beforeEach(() => {
    mocks.getDashboard.mockReset().mockResolvedValue({
      paperStats: { total: 3, unread: 1, reading: 1, read: 1, uncategorized: 2, pinned: 0, thisMonth: 2 },
      folderBacklog: [{ id: 1, name: '通信', paperCount: 3 }],
      taskStats: {
        pending: 2, processing: 1, retryWait: 1, failed: 2, pendingUser: 1,
        deadLetter: 1, recent: [],
      },
    })
  })

  it('keeps the focused overview and omits the four removed dashboard modules', async () => {
    const wrapper = mount(DashboardView, {
      global: {
        mocks: { $router: { push: vi.fn() } },
        stubs: {
          'el-button': passthrough,
          'el-skeleton': passthrough,
          'el-row': passthrough,
          'el-col': passthrough,
          'el-card': passthrough,
          'el-statistic': { props: ['value'], template: '<span>{{ value }}</span>' },
          'el-progress': passthrough,
          'el-empty': passthrough,
          'el-tag': passthrough,
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('文件夹分布')
    expect(wrapper.text()).toContain('任务中心')
    expect(wrapper.text()).toContain('未分类')
    expect(wrapper.text()).toContain('处理中 4')
    expect(wrapper.text()).toContain('已完成 0')
    expect(wrapper.text()).toContain('失败 4')
    expect(wrapper.text()).not.toContain('最近笔记')
    expect(wrapper.text()).not.toContain('最近批注')
    expect(wrapper.text()).not.toContain('引用关系')
    expect(wrapper.text()).not.toContain('PDF 工作台质量')
  })

  it('shows an actionable error state when the dashboard request fails', async () => {
    mocks.getDashboard.mockRejectedValueOnce(new Error('服务不可用'))
    const wrapper = mount(DashboardView, {
      global: {
        mocks: { $router: { push: vi.fn() } },
        stubs: {
          'el-skeleton': passthrough,
          'el-result': { props: ['subTitle'], template: '<div class="result">{{ subTitle }}<slot /><slot name="extra" /></div>' },
          'el-button': buttonStub,
        },
      },
    })
    await flushPromises()

    expect(wrapper.find('.result').text()).toContain('服务不可用')
    expect(wrapper.find('.result button').text()).toContain('重试')
  })
})
