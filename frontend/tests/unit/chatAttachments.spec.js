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

  it('keeps PDF, Word and image bytes for the document API', async () => {
    const rawFile = { name: 'paper.docx', type: '', size: 12, arrayBuffer: async () => new ArrayBuffer(0) }
    const attachment = await prepareChatAttachment(rawFile)

    expect(attachment).toMatchObject({
      name: 'paper.docx',
      mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      content: '',
      truncated: false,
      rawFile,
    })
  })

  it('rejects oversized text and image attachments instead of truncating them', async () => {
    await expect(prepareChatAttachment({
      name: 'notes.txt', type: 'text/plain', size: 10,
      text: async () => '字'.repeat(3_001),
    })).rejects.toThrow('附件内容过长')

    await expect(prepareChatAttachment({
      name: 'figure.png', type: 'image/png', size: 5 * 1024 * 1024 + 1,
    })).rejects.toThrow('附件过大')
  })
})
