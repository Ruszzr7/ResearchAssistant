import { flushPromises, mount } from '@vue/test-utils'
import { KeepAlive, defineComponent, nextTick, shallowRef } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ReadingTimePanel from '@/components/ReadingTimePanel.vue'

const api = vi.hoisted(() => ({
  getReadingProgress: vi.fn(),
  addReadingTime: vi.fn(),
}))

vi.mock('@/api/readingProgress.js', () => api)

const ReaderHost = defineComponent({
  name: 'ReaderHost',
  components: { ReadingTimePanel },
  template: '<ReadingTimePanel :paper="{ id: 7, readSeconds: 0 }" />',
})
const OtherHost = defineComponent({ name: 'OtherHost', template: '<div>other</div>' })

describe('ReadingTimePanel cached lifecycle', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    api.getReadingProgress.mockReset().mockResolvedValue({ data: { readSeconds: 0 } })
    api.addReadingTime.mockReset().mockResolvedValue({})
  })

  afterEach(() => vi.useRealTimers())

  it('pauses counting while its cached route is deactivated', async () => {
    const active = shallowRef(ReaderHost)
    const wrapper = mount(defineComponent({
      components: { KeepAlive },
      setup: () => ({ active }),
      template: '<KeepAlive include="ReaderHost"><component :is="active" /></KeepAlive>',
    }))
    await flushPromises()
    expect(wrapper.text()).toContain('阅读时长')
    await vi.advanceTimersByTimeAsync(2000)

    active.value = OtherHost
    await nextTick()
    await flushPromises()
    expect(api.addReadingTime).toHaveBeenCalledTimes(1)
    expect(api.addReadingTime).toHaveBeenLastCalledWith(7, 2)

    await vi.advanceTimersByTimeAsync(5000)
    expect(api.addReadingTime).toHaveBeenCalledTimes(1)

    active.value = ReaderHost
    await nextTick()
    await vi.advanceTimersByTimeAsync(1000)
    wrapper.unmount()
    await flushPromises()
    expect(api.addReadingTime).toHaveBeenCalledTimes(2)
    expect(api.addReadingTime).toHaveBeenLastCalledWith(7, 1)
  })
})
