import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import FormulaRegionCard from '@/components/pdf/FormulaRegionCard.vue'

const buttonStub = {
  emits: ['click'],
  template: '<button :disabled="$attrs.disabled" @click="$emit(\'click\')"><slot /></button>',
}
const inputStub = {
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
}

describe('FormulaRegionCard', () => {
  it('presents formula conversion as an internal step of fixing the selected content', async () => {
    const wrapper = mount(FormulaRegionCard, {
      props: {
        region: { page: 4, bbox: { x: 0.1, y: 0.2, width: 0.5, height: 0.1 } },
      },
      global: {
        stubs: {
          'el-button': buttonStub,
          'el-input': inputStub,
          'el-tag': { template: '<span><slot /></span>' },
        },
      },
    })

    expect(wrapper.get('.formula-region-card__preview-placeholder').text())
      .toContain('固定时会自动生成可编辑 LaTeX')
    const action = wrapper.get('.formula-region-card__actions button')
    expect(action.text()).toContain('固定内容')
    await action.trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('shows the local canvas preview before model recognition completes', () => {
    const wrapper = mount(FormulaRegionCard, {
      props: {
        region: { page: 4, bbox: { x: 0.1, y: 0.2, width: 0.5, height: 0.1 } },
        previewDataUrl: 'data:image/png;base64,local',
        loading: true,
      },
      global: {
        stubs: {
          'el-button': buttonStub,
          'el-input': inputStub,
          'el-tag': { template: '<span><slot /></span>' },
        },
      },
    })

    expect(wrapper.get('.formula-region-card__preview').attributes('src'))
      .toBe('data:image/png;base64,local')
  })

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
    expect(wrapper.text()).not.toContain('请核对')
    expect(wrapper.findAll('.formula-region-card__actions button')[0].text())
      .toContain('重新转换 LaTeX')
    await wrapper.get('textarea').setValue('\\int_0^1 x\\,dx')
    expect(wrapper.get('.formula-region-card__rendered').text()).toContain('∫')
    await wrapper.findAll('.formula-region-card__actions button')[1].trigger('click')

    expect(wrapper.emitted('confirm')?.[0]).toEqual(['\\int_0^1 x\\,dx'])
  })

  it('labels a confirmed formula as fixed content', () => {
    const wrapper = mount(FormulaRegionCard, {
      props: {
        region: { page: 4, bbox: { x: 0.1, y: 0.2, width: 0.5, height: 0.1 } },
        recognition: {
          id: 13,
          latex: 'x+y',
          source: 'LAYOUT',
          status: 'CONFIRMED',
          confirmed: true,
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

    expect(wrapper.text()).toContain('已固定')
    expect(wrapper.text()).toContain('保存校正')
  })
})
