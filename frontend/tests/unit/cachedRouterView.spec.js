import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import CachedRouterView from '@/components/navigation/CachedRouterView.vue'

const LibraryView = defineComponent({
  name: 'LibraryView',
  data: () => ({ page: 1 }),
  template: '<section data-view="library"><button @click="page++">page {{ page }}</button></section>',
})

const OtherView = defineComponent({
  name: 'OtherView',
  template: '<section data-view="other">other</section>',
})

describe('cached router workspace', () => {
  it('keeps the named library workspace alive across route changes', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/library', component: LibraryView },
        { path: '/other', component: OtherView },
      ],
    })
    await router.push('/library')
    await router.isReady()

    const wrapper = mount(CachedRouterView, {
      props: { include: ['LibraryView'] },
      global: { plugins: [router] },
    })
    await wrapper.get('button').trigger('click')
    expect(wrapper.text()).toContain('page 2')

    await router.push('/other')
    await flushPromises()
    expect(wrapper.get('[data-view="other"]').exists()).toBe(true)

    await router.push('/library')
    await flushPromises()
    await nextTick()
    expect(wrapper.get('[data-view="library"]').text()).toContain('page 2')
  })
})
