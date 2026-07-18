import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import FormulaRegionCard from '@/components/pdf/FormulaRegionCard.vue'

const buttonStub = { template: '<button :disabled="$attrs.disabled" @click="$emit(\'click\')"><slot /></button>' }
const inputStub = {
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
}

describe('FormulaRegionCard', () => {
  it('renders safe KaTeX and emits the editable source only on confirmation', async () => {
    const wrapper = mount(FormulaRegionCard, {
      props: {
        region: { page: 4, bbox: { x: 0.1, y: 0.2, width: 0.5, height: 0.1 } },
        recognition: {
          id: 12,
          latex: '\\sum_{k=1}^{K} r_k',
          confidence: 0.91,
          source: 'MULTIMODAL',
          status: 'CANDIDATE',
          confirmed: false,
          previewDataUrl: 'data:image/png;base64,AA==',
          message: '请核对',
        },
      },
      global: {
        stubs: {
          'el-button': buttonStub,
          'el-input': inputStub,
          'el-tag': { template: '<span><slot /></span>' },
        },
      },
    })

    expect(wrapper.find('.katex').exists()).toBe(true)
    expect(wrapper.text()).toContain('待确认')
    expect(wrapper.text()).toContain('图像识别候选')
    await wrapper.get('textarea').setValue('\\int_0^1 x\\,dx')
    expect(wrapper.get('.formula-region-card__rendered').text()).toContain('∫')
    await wrapper.findAll('.formula-region-card__actions button')[1].trigger('click')

    expect(wrapper.emitted('confirm')?.[0]).toEqual(['\\int_0^1 x\\,dx'])
  })
})
