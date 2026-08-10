import { describe, expect, it } from 'vitest'
import { prepareChatAttachment } from '@/utils/chatAttachments.js'

describe('chat attachment preparation', () => {
  it('reads supported text attachments without changing their content meaning', async () => {
    const attachment = await prepareChatAttachment({
      name: 'notes.tex',
      type: 'application/x-tex',
      size: 32,
      text: async () => '\\Gamma = a + b\n\nexplanation',
    })

    expect(attachment).toMatchObject({
      name: 'notes.tex',
      mimeType: 'application/x-tex',
      content: '\\Gamma = a + b\n\nexplanation',
      truncated: false,
    })
  })

  it('rejects binary formats that cannot be reliably placed in model context', async () => {
    await expect(prepareChatAttachment({
      name: 'archive.zip', type: 'application/zip', size: 20,
    })).rejects.toThrow('当前支持 PDF')
  })
})
