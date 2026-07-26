import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ResearchMarkdown from '@/components/ResearchMarkdown.vue'
import { renderResearchMarkdown } from '@/utils/researchMarkdown.js'

describe('research Markdown renderer', () => {
  it('renders GFM tables and both common LaTeX delimiter styles', () => {
    const wrapper = mount(ResearchMarkdown, {
      props: {
        content: [
          '| 方法 | 准确率 |',
          '| --- | ---: |',
          '| Proposed | 92% |',
          '',
          '行内公式 $x_k^2$ 和 \\(y_k\\)。',
          '',
          '$$\\sum_{k=1}^{K} x_k$$',
          '',
          '\\[\\mathbf{A}\\mathbf{x}=\\mathbf{b}\\]',
        ].join('\n'),
      },
    })

    expect(wrapper.find('table').exists()).toBe(true)
    expect(wrapper.findAll('.katex')).toHaveLength(4)
    expect(wrapper.text()).toContain('Proposed')
  })

  it('keeps code fences literal and blocks model-provided HTML and unsafe links', () => {
    const html = renderResearchMarkdown([
      '<img src=x onerror=alert(1)>',
      '',
      '[bad](javascript:alert(1))',
      '',
      '```js',
      'const price = \"$5\"',
      '```',
    ].join('\n'))

    expect(html).toContain('&lt;img')
    expect(html).not.toContain('<img')
    expect(html).not.toContain('href="javascript:')
    expect(html).toContain('const price')
    expect(html).not.toContain('class="katex"')
  })

  it('leaves malformed LaTeX readable instead of dropping the answer', () => {
    const wrapper = mount(ResearchMarkdown, {
      props: { content: '结果为 $\\notacommand{x$，请回原文核对。' },
    })

    expect(wrapper.text()).toContain('请回原文核对')
    expect(wrapper.text()).toContain('\\notacommand')
  })
})
