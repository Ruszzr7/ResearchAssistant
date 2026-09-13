import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import EvidenceSourceList from '@/components/EvidenceSourceList.vue'

describe('EvidenceSourceList', () => {
  it('keeps the complete evidence details collapsed by default', () => {
    const wrapper = mount(EvidenceSourceList, {
      props: {
        sources: [{
          key: 'source-1', kind: '正文', page: 3, excerpt: '摘要',
          fullText: '完整段落', excerptTruncated: true, fullTextAvailable: true,
          target: { locator: { pageNumber: 3, targetBoxes: [{ x: .1, y: .2, width: .3, height: .04 }] } },
        }],
      },
      global: { stubs: { ResearchMarkdown: true } },
    })

    const fullEvidence = wrapper.get('.evidence-source__full')
    expect(fullEvidence.attributes('open')).toBeUndefined()
    expect(fullEvidence.get('summary').text()).toContain('完整依据')
  })
})
