import { describe, expect, it } from 'vitest'
import {
  buildAgentTurnRequest,
  MAX_AGENT_MESSAGE_CHARACTERS,
  MAX_FORMULA_SELECTION_CHARACTERS,
  MAX_TEXT_SELECTION_CHARACTERS,
} from '@/utils/agentTurn.js'

describe('agent turn request limits', () => {
  it('keeps accepted input intact', () => {
    const content = '字'.repeat(3_000)
    const request = buildAgentTurnRequest({
      paperId: 7,
      userMessage: '问'.repeat(MAX_AGENT_MESSAGE_CHARACTERS),
      selectionAnchor: { contentType: 'TEXT', text: '选'.repeat(MAX_TEXT_SELECTION_CHARACTERS) },
      attachments: [{ name: 'notes.txt', mimeType: 'text/plain', content }],
    })

    expect(request.userMessage).toHaveLength(MAX_AGENT_MESSAGE_CHARACTERS)
    expect(request.attachments[0].content).toBe(content)
  })

  it('rejects overlong messages, selections and excess attachments', () => {
    expect(() => buildAgentTurnRequest({
      paperId: 7, userMessage: '问'.repeat(MAX_AGENT_MESSAGE_CHARACTERS + 1),
    })).toThrow('输入内容过长')
    expect(() => buildAgentTurnRequest({
      paperId: 7, userMessage: '解释',
      selectionAnchor: { contentType: 'TEXT', text: '选'.repeat(MAX_TEXT_SELECTION_CHARACTERS + 1) },
    })).toThrow('选取内容过长')
    expect(() => buildAgentTurnRequest({
      paperId: 7, userMessage: '解释',
      selectionAnchor: { contentType: 'FORMULA', text: 'x'.repeat(MAX_FORMULA_SELECTION_CHARACTERS + 1) },
    })).toThrow('选取内容过长')
    expect(() => buildAgentTurnRequest({
      paperId: 7, userMessage: '解释',
      attachments: [1, 2, 3].map(index => ({
        name: `${index}.txt`, mimeType: 'text/plain', content: 'x',
      })),
    })).toThrow('每条消息最多添加 2 个附件')
  })
})
