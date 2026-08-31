import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PaperWorkbenchPanel from '@/components/pdf/PaperWorkbenchPanel.vue'
import FormulaRegionCard from '@/components/pdf/FormulaRegionCard.vue'

const mocks = vi.hoisted(() => ({
  translateTexts: vi.fn(),
  createResearchSession: vi.fn(),
  getResearchSession: vi.fn(),
  listResearchSessions: vi.fn(),
  getPaperMemoryStatus: vi.fn(),
  startPaperUnderstanding: vi.fn(),
  prepareChatAttachment: vi.fn(),
  state: {
    running: { __v_isRef: true, value: false },
    error: { __v_isRef: true, value: '' },
    run: vi.fn(),
    watchRun: vi.fn(),
  },
}))

vi.mock('@/api/workbench.js', () => ({ translateTexts: mocks.translateTexts }))
vi.mock('@/api/paperMemory.js', () => ({
  getPaperMemoryStatus: mocks.getPaperMemoryStatus,
  startPaperUnderstanding: mocks.startPaperUnderstanding,
}))
vi.mock('@/api/researchArchive.js', () => ({
  createResearchSession: mocks.createResearchSession,
  getResearchSession: mocks.getResearchSession,
  listResearchSessions: mocks.listResearchSessions,
}))
vi.mock('@/utils/chatAttachments.js', () => ({
  CHAT_ATTACHMENT_ACCEPT: '.pdf,.txt,.md,.tex',
  prepareChatAttachment: mocks.prepareChatAttachment,
}))
vi.mock('@/composables/usePaperAgent.js', () => ({
  usePaperAgent: () => mocks.state,
}))

const passthrough = { template: '<span><slot /></span>' }
const buttonStub = {
  emits: ['click'],
  template: '<button :disabled="$attrs.disabled" @click="$emit(\'click\')"><slot /></button>',
}
const inputStub = {
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
}
const defaultStubs = {
  'el-tag': passthrough,
  'el-input': inputStub,
  'el-button': buttonStub,
}
const paper = { id: 1, title: 'Current Paper', pdfPath: 'paper.pdf' }
const textSelection = { text: 'The selected method solves the estimation problem.' }
const textAnchor = {
  kind: 'TEXT',
  page: 2,
  confidence: 0.96,
  boxes: [{ x: 0.1, y: 0.2, width: 0.5, height: 0.08 }],
}

describe('PaperWorkbenchPanel paper-reading workspace', () => {
  beforeEach(() => {
    mocks.translateTexts.mockReset()
    mocks.createResearchSession.mockReset().mockResolvedValue({ id: 91 })
    mocks.getResearchSession.mockReset().mockResolvedValue({ messages: [], runs: [] })
    mocks.listResearchSessions.mockReset().mockResolvedValue([])
    mocks.getPaperMemoryStatus.mockReset().mockResolvedValue({
      paperId: 1, status: 'PROFILE_READY', statusText: '论文理解已完成',
      profileReady: true, conversationReady: true,
    })
    mocks.startPaperUnderstanding.mockReset().mockResolvedValue({ taskId: 'memory-task-1' })
    mocks.prepareChatAttachment.mockReset()
    mocks.state.running.value = false
    mocks.state.error.value = ''
    mocks.state.run.mockReset()
    mocks.state.watchRun.mockReset()
  })

  it('uses one unified research conversation and leaves comparison as an interface', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.find('.product-tabs').exists()).toBe(false)
    expect(wrapper.get('.assistant-context-header').text()).toContain('论文助手')
    expect(wrapper.get('.assistant-context-header').text()).toContain('连续科研对话')
    await wrapper.get('.comparison-paper-action').trigger('click')
    expect(wrapper.emitted('add-comparison-paper')).toHaveLength(1)
    expect(mocks.state.run).not.toHaveBeenCalled()
  })

  it('switches content and formula capture from the sliding selector', async () => {
    const wrapper = mountPanel({ captureMode: 'text' })
    expect(wrapper.get('.assistant-context-header').find('.capture-switch').exists()).toBe(true)
    expect(wrapper.find('.capture-section').exists()).toBe(false)
    const buttons = wrapper.findAll('.capture-switch button')
    expect(buttons.map(button => button.text())).toEqual(['内容选取', '公式框选'])
    expect(buttons[0].classes()).toContain('active')

    await buttons[1].trigger('click')
    expect(wrapper.emitted('capture-mode-change')?.[0]).toEqual(['formula'])

    await wrapper.setProps({ captureMode: 'formula' })
    expect(wrapper.get('.capture-switch').classes()).toContain('is-formula')
    expect(wrapper.findAll('.capture-switch button')[1].classes()).toContain('active')
  })

  it('keeps the initial capture prompt compact without a decorative icon', async () => {
    const wrapper = mountPanel({ captureMode: 'formula' })
    await flushPromises()

    expect(wrapper.find('.content-empty__icon').exists()).toBe(false)
    expect(wrapper.get('.content-empty').text()).toContain('框选一个公式')
    expect(wrapper.get('.content-empty').text())
      .toContain('框选预览和固定过程中生成的 LaTeX 会显示在这里。')
  })

  it('offers a scroll-to-latest control when the conversation is away from the bottom', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    const messages = wrapper.get('.selection-chat__messages')
    Object.defineProperties(messages.element, {
      scrollHeight: { configurable: true, value: 1000 },
      clientHeight: { configurable: true, value: 200 },
      scrollTop: { configurable: true, writable: true, value: 100 },
    })

    await messages.trigger('scroll')
    expect(wrapper.get('.scroll-to-latest').isVisible()).toBe(true)
    await wrapper.get('.scroll-to-latest').trigger('click')
    await flushPromises()

    expect(messages.element.scrollTop).toBe(1000)
    expect(wrapper.find('.scroll-to-latest').exists()).toBe(false)
  })

  it('collapses confirmed content while preserving a reopen control', async () => {
    const wrapper = mountPanel({ selection: textSelection, selectionAnchor: textAnchor })
    await flushPromises()

    await wrapper.findAll('.selection-tools button')
      .find(button => button.text().includes('附加到下一条消息')).trigger('click')

    expect(wrapper.find('.content-stage').exists()).toBe(false)
    expect(wrapper.get('.content-stage-collapsed').text()).toContain('已固定第 2 页选取内容')
    await wrapper.get('.content-stage-collapsed button').trigger('click')
    expect(wrapper.get('.content-stage').text()).toContain('selected method')
  })

  it('shows a concise blocking understanding status and permits retry', async () => {
    mocks.getPaperMemoryStatus.mockResolvedValue({
      paperId: 1, status: 'RETRY_REQUIRED', statusText: '论文理解未完成，请重试',
      profileReady: false, conversationReady: false,
    })
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.get('.memory-status').text()).toContain('论文理解未完成，请重试')
    expect(wrapper.get('.memory-status').text()).not.toContain('分块')
    expect(wrapper.get('.memory-status').text()).not.toContain('Token')
    await wrapper.get('.memory-status__action').trigger('click')
    await flushPromises()

    expect(mocks.startPaperUnderstanding).toHaveBeenCalledWith(1, expect.stringMatching(/^paper-memory-ui:1:/))
    expect(wrapper.get('.memory-status').text()).toContain('任务已提交')
    wrapper.unmount()
  })

  it('keeps the manual understanding button visible when readiness loading fails', async () => {
    mocks.getPaperMemoryStatus.mockRejectedValue(new Error('readiness unavailable'))
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.get('.memory-status').text()).toContain('论文理解尚未启动')
    expect(wrapper.get('.memory-status__action').text()).toContain('开始理解')
    await wrapper.get('.memory-status__action').trigger('click')
    await flushPromises()

    expect(mocks.startPaperUnderstanding).toHaveBeenCalledWith(1, expect.stringMatching(/^paper-memory-ui:1:/))
    wrapper.unmount()
  })

  it('keeps paper questions disabled until the global profile is ready', async () => {
    mocks.getPaperMemoryStatus.mockResolvedValue({
      paperId: 1, status: 'UNDERSTANDING', statusText: '正在理解论文',
      profileReady: false, conversationReady: false,
    })
    const wrapper = mountPanel({ selection: textSelection, selectionAnchor: textAnchor })
    await flushPromises()

    await wrapper.get('.assistant-composer textarea').setValue('现在可以提问吗？')

    expect(wrapper.get('.memory-status').text()).toContain('正在理解论文')
    expect(wrapper.get('.memory-status').text()).not.toContain('Token')
    expect(sendButton(wrapper).attributes()).toHaveProperty('disabled')
    expect(wrapper.get('.assistant-composer textarea').attributes('placeholder'))
      .toBe('论文理解完成后即可提问')
    wrapper.unmount()
  })

  it('uses a confirmed selection only as the next-message attachment', async () => {
    mocks.state.run.mockImplementation(async (_request, options) => {
      options?.onAccepted?.()
      return {
        runId: 'selection-turn-1',
        result: { answer: '该方法解决估计问题。', claims: [], evidence: [] },
      }
    })
    const wrapper = mountPanel({ selection: textSelection, selectionAnchor: textAnchor })
    await flushPromises()

    expect(wrapper.get('.selection-card__text').text()).toContain('selected method')
    expect(wrapper.text()).not.toContain('正文已映射')
    expect(wrapper.text()).toContain('附加到下一条消息')
    expect(wrapper.get('.selection-chat__heading').text()).not.toContain('下一条消息已附加')
    await wrapper.findAll('.selection-tools button')
      .find(button => button.text().includes('附加到下一条消息')).trigger('click')
    await wrapper.get('.assistant-composer textarea').setValue('这段方法解决什么问题？')
    expect(wrapper.get('.selection-chat__heading').text()).toContain('下一条消息已附加')
    expect(sendButton(wrapper).attributes()).not.toHaveProperty('disabled')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    expect(mocks.state.run).toHaveBeenCalledWith(expect.objectContaining({
      userMessage: '这段方法解决什么问题？',
      selectionAnchor: textAnchor,
      researchSessionId: 91,
    }), expect.objectContaining({
      onAccepted: expect.any(Function),
    }))
    expect(wrapper.emitted('clear-selection')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('待发送')
    expect(wrapper.text()).toContain('该方法解决估计问题')
  })

  it('sends with Enter and reserves Shift+Enter for a line break', async () => {
    mocks.state.run.mockResolvedValue({
      runId: 'keyboard-turn-1',
      result: { answer: '已发送。', claims: [], evidence: [] },
    })
    const wrapper = mountPanel()
    await flushPromises()
    const composer = wrapper.get('.assistant-composer textarea')

    expect(wrapper.get('.assistant-composer__footer span').text()).toBe('Shift+Enter 换行')
    await composer.setValue('键盘发送测试')
    await composer.trigger('keydown', { key: 'Enter', shiftKey: true })
    expect(mocks.state.run).not.toHaveBeenCalled()

    await composer.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(mocks.state.run).toHaveBeenCalledWith(expect.objectContaining({
      userMessage: '键盘发送测试',
    }), expect.any(Object))
  })

  it('keeps LaTeX as editable formula chips and sends it with local attachments', async () => {
    mocks.prepareChatAttachment.mockResolvedValue({
      name: 'derivation.tex', mimeType: 'application/x-tex',
      content: '\\gamma = a / b', truncated: false, size: 32,
    })
    mocks.state.run.mockResolvedValue({
      runId: 'attachment-turn-1',
      result: { answer: '已结合附件回答。', claims: [], evidence: [] },
    })
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('button[aria-label="输入 LaTeX"]').trigger('click')
    await wrapper.get('.assistant-composer__latex textarea').setValue('\\frac{a}{b}')
    await wrapper.findAll('.assistant-composer__latex button')
      .find(button => button.text().includes('添加公式')).trigger('click')
    expect(wrapper.get('.assistant-composer__input').element.value).toBe('')
    expect(wrapper.get('.assistant-composer__attachments').text()).toContain('公式1')

    await wrapper.get('.assistant-composer__formula-name').trigger('click')
    expect(wrapper.get('.assistant-composer__latex textarea').element.value).toBe('\\frac{a}{b}')
    await wrapper.get('.assistant-composer__latex textarea').setValue('\\frac{a+b}{c}')
    await wrapper.findAll('.assistant-composer__latex button')
      .find(button => button.text().includes('保存修改')).trigger('click')

    const input = wrapper.get('.assistant-composer__file-input')
    const file = new File(['formula'], 'derivation.tex', { type: 'application/x-tex' })
    Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
    await input.trigger('change')
    await flushPromises()
    expect(wrapper.get('.assistant-composer__attachments').text()).toContain('derivation.tex')

    await sendButton(wrapper).trigger('click')
    await flushPromises()
    expect(mocks.state.run).toHaveBeenCalledWith(expect.objectContaining({
      userMessage: '请分析所附附件。',
      attachments: [
        expect.objectContaining({ name: 'derivation.tex', content: '\\gamma = a / b' }),
        expect.objectContaining({ name: '公式1', mimeType: 'application/x-latex', content: '\\frac{a+b}{c}' }),
      ],
    }), expect.any(Object))
    expect(wrapper.text()).toContain('derivation.tex')
    expect(wrapper.text()).toContain('公式1')
  })

  it('distinguishes a recoverable region fallback from a precise text anchor', async () => {
    const wrapper = mountPanel({
      selection: textSelection,
      selectionAnchor: { ...textAnchor, kind: 'REGION', confidence: 0.42 },
    })
    await flushPromises()

    expect(wrapper.get('.warning-state').text()).toContain('只能定位到页面区域')
    expect(wrapper.get('.warning-state').text()).toContain('重新选择')
    expect(wrapper.get('.warning-state').text()).toContain('回原页核对')
  })

  it('explains local math enhancement for an exact math-rich selection', async () => {
    const wrapper = mountPanel({
      selection: textSelection,
      selectionAnchor: {
        ...textAnchor,
        mappingStatus: 'EXACT',
        contentType: 'MATH_RICH_TEXT',
      },
    })
    await flushPromises()

    expect(wrapper.find('.warning-state').exists()).toBe(false)
    expect(wrapper.get('.math-rich-state').text()).toContain('多个行内数学片段')
    expect(wrapper.get('.math-rich-state').text()).toContain('本地 LaTeX')
    expect(wrapper.get('.math-rich-state').text()).toContain('原页排版为准')
  })

  it('does not render a long source crop for an ordinary text selection', async () => {
    const wrapper = mountPanel({
      selection: {
        text: 'ensures E ssH = I.',
        visualFallback: {
          dataUrl: 'data:image/png;base64,preview',
          reason: 'PDF 数学字体包含无法可靠映射的字符，公式以原页图像为准。',
        },
      },
      selectionAnchor: textAnchor,
    })
    await flushPromises()

    expect(wrapper.find('.selection-source-preview').exists()).toBe(false)
    expect(wrapper.get('.selection-card__text').text()).not.toContain('□')
  })

  it('keeps follow-up questions in one grounded conversation', async () => {
    mocks.state.run
      .mockResolvedValueOnce({
        runId: 'turn-1',
        result: { answer: '第一轮回答', claims: [], evidence: [] },
      })
      .mockResolvedValueOnce({
        runId: 'turn-2',
        result: { answer: '第二轮回答', claims: [], evidence: [] },
      })
    const wrapper = mountPanel({ selection: textSelection, selectionAnchor: textAnchor })
    await flushPromises()
    await wrapper.findAll('.selection-tools button')
      .find(button => button.text().includes('附加到下一条消息')).trigger('click')
    await wrapper.get('.assistant-composer textarea').setValue('第一问')
    await sendButton(wrapper).trigger('click')
    await flushPromises()
    await wrapper.get('.content-stage-collapsed button').trigger('click')
    await wrapper.get('.selection-clear-action').trigger('click')
    await wrapper.setProps({ selection: null, selectionAnchor: null })
    await wrapper.get('.assistant-composer textarea').setValue('第二问')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    const first = mocks.state.run.mock.calls[0][0]
    const second = mocks.state.run.mock.calls[1][0]
    expect(second.researchSessionId).toBe(first.researchSessionId)
    expect(second).not.toHaveProperty('conversationContext')
    expect(wrapper.findAll('.chat-message')).toHaveLength(4)
  })

  it('continues the existing conversation after the current selection is cleared', async () => {
    mocks.state.run
      .mockResolvedValueOnce({
        runId: 'turn-before-clear',
        result: { answer: '第一轮回答', claims: [], evidence: [] },
      })
      .mockResolvedValueOnce({
        runId: 'turn-after-clear',
        result: {
          answer: '连续追问回答', claims: [], evidence: [],
          contextInherited: true, contextMode: 'FOLLOW_UP',
        },
      })
      .mockResolvedValueOnce({
        runId: 'turn-new-conversation',
        result: { answer: '独立对话回答', claims: [], evidence: [] },
      })
    const wrapper = mountPanel({ selection: textSelection, selectionAnchor: textAnchor })
    await flushPromises()
    await wrapper.findAll('.selection-tools button')
      .find(button => button.text().includes('附加到下一条消息')).trigger('click')
    await wrapper.get('.assistant-composer textarea').setValue('第一问')
    await sendButton(wrapper).trigger('click')
    await flushPromises()
    const firstRequest = mocks.state.run.mock.calls[0][0]

    await wrapper.get('.content-stage-collapsed button').trigger('click')
    await wrapper.get('.selection-clear-action').trigger('click')
    await wrapper.setProps({ selection: null, selectionAnchor: null })
    await wrapper.get('.assistant-composer textarea').setValue('没有新选区时继续追问')

    expect(wrapper.get('.selection-chat__heading').text()).toContain('结合本对话历史判断是否需要查阅论文')
    expect(wrapper.get('.assistant-composer textarea').attributes('placeholder'))
      .toContain('继续当前对话')
    expect(sendButton(wrapper).attributes()).not.toHaveProperty('disabled')

    await sendButton(wrapper).trigger('click')
    await flushPromises()

    const secondRequest = mocks.state.run.mock.calls[1][0]
    expect(secondRequest.researchSessionId).toBe(firstRequest.researchSessionId)
    expect(secondRequest.selectionAnchor).toBeNull()
    expect(wrapper.findAll('.chat-message.is-user')[1].text()).toContain('继续上一问题')

    mocks.createResearchSession.mockResolvedValueOnce({ id: 92 })
    await wrapper.findAll('.selection-chat__actions button')
      .find(button => button.text() === '新对话').trigger('click')
    expect(wrapper.findAll('.chat-message')).toHaveLength(0)
    expect(wrapper.get('.selection-chat__heading').text()).toContain('可直接提问')
    await wrapper.get('.assistant-composer textarea').setValue('新对话基于论文理解')
    expect(sendButton(wrapper).attributes()).not.toHaveProperty('disabled')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    const newConversationRequest = mocks.state.run.mock.calls[2][0]
    expect(newConversationRequest.researchSessionId).not.toBe(firstRequest.researchSessionId)
    expect(newConversationRequest.selectionAnchor).toBeNull()
    expect(wrapper.findAll('.chat-message')).toHaveLength(2)
  })

  it('shows an explicit PDF operation instead of describing it as inherited context', async () => {
    mocks.state.run.mockResolvedValue({
      runId: 'highlight-command',
      result: {
        answer: '已找到 2 处匹配内容，正在执行高亮。',
        claims: [], evidence: [], actions: [], contextMode: 'ACTION_EXPLICIT',
      },
    })
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('.assistant-composer textarea').setValue('将信噪比公式所在位置高亮')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    expect(wrapper.get('.chat-message.is-user').text()).toContain('执行论文操作')
    expect(wrapper.get('.chat-message.is-user').text()).not.toContain('继续上一问题')
  })

  it('offers existing conversations for the paper and starts a new independent session', async () => {
    mocks.listResearchSessions.mockResolvedValue([
      { id: 91, primaryPaperId: 1, title: '方法讨论', messageCount: 4, lastActivityAt: '2026-07-29T10:00:00' },
      { id: 92, primaryPaperId: 1, title: '实验讨论', messageCount: 2, lastActivityAt: '2026-07-29T11:00:00' },
      { id: 93, primaryPaperId: 2, title: '其他论文', messageCount: 8, lastActivityAt: '2026-07-29T12:00:00' },
    ])
    mocks.getResearchSession.mockResolvedValue({ messages: [], runs: [] })
    mocks.state.run.mockResolvedValue({
      runId: 'new-session-run',
      result: { answer: '回答', claims: [], evidence: [] },
    })
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.find('.conversation-picker').exists()).toBe(false)
    expect(wrapper.emitted('research-session-change')).toContainEqual([92])
    expect(mocks.getResearchSession).toHaveBeenCalledWith(92)

    await wrapper.findAll('.selection-chat__actions button')
      .find(button => button.text() === '切换对话').trigger('click')
    await flushPromises()
    expect(wrapper.get('.conversation-picker').text()).toContain('方法讨论')
    expect(wrapper.get('.conversation-picker').text()).toContain('实验讨论')
    expect(wrapper.get('.conversation-picker').text()).not.toContain('其他论文')

    await wrapper.findAll('.conversation-picker__item')
      .find(button => button.text().includes('方法讨论')).trigger('click')
    await flushPromises()
    expect(wrapper.emitted('research-session-change')).toContainEqual([91])
    expect(mocks.getResearchSession).toHaveBeenCalledWith(91)

    await wrapper.findAll('.selection-chat__actions button')
      .find(button => button.text() === '新对话').trigger('click')
    expect(wrapper.emitted('research-session-change')).toContainEqual([null])

    await wrapper.get('.assistant-composer textarea').setValue('这个方法的核心假设是什么？')
    await sendButton(wrapper).trigger('click')
    await flushPromises()
    expect(mocks.createResearchSession).toHaveBeenCalledWith(expect.objectContaining({
      primaryPaperId: 1,
      title: '这个方法的核心假设是什么？',
    }))
  })

  it('does not resume an old failed run when the conversation already has a newer assistant answer', async () => {
    mocks.listResearchSessions.mockResolvedValue([
      { id: 91, primaryPaperId: 1, title: '已有对话', lastActivityAt: '2026-08-21T16:00:00' },
    ])
    mocks.getResearchSession.mockResolvedValue({
      messages: [
        { messageKey: 'old-user', role: 'USER', content: '旧问题', runId: 'failed-run' },
        { messageKey: 'latest-user', role: 'USER', content: '新问题', runId: 'completed-run' },
        { messageKey: 'latest-answer', role: 'ASSISTANT', content: '新回答', runId: 'completed-run' },
      ],
    })

    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.text()).toContain('新回答')
    expect(mocks.state.watchRun).not.toHaveBeenCalled()
  })

  it('observes a persisted failed run only once instead of entering a restore loop', async () => {
    mocks.listResearchSessions.mockResolvedValue([
      { id: 91, primaryPaperId: 1, title: '失败对话', lastActivityAt: '2026-08-25T13:24:00' },
    ])
    mocks.getResearchSession.mockResolvedValue({
      messages: [
        { messageKey: 'failed-user', role: 'USER', content: '失败问题', runId: 'failed-run' },
      ],
    })
    const failedRun = new Error('论文助手执行失败，请稍后重试')
    failedRun.agentTerminal = true
    mocks.state.watchRun.mockRejectedValue(failedRun)

    const wrapper = mountPanel({ researchSessionId: 91 })
    await flushPromises()

    expect(mocks.state.watchRun).toHaveBeenCalledTimes(1)
    expect(wrapper.get('.error-state').text()).toContain('论文助手执行失败')

    await wrapper.findAll('.selection-chat__actions button')
      .find(button => button.text() === '新对话').trigger('click')
    expect(wrapper.find('.error-state').exists()).toBe(false)

    await wrapper.findAll('.selection-chat__actions button')
      .find(button => button.text() === '切换对话').trigger('click')
    await flushPromises()
    await wrapper.findAll('.conversation-picker__item')
      .find(button => button.text().includes('失败对话')).trigger('click')
    await flushPromises()

    expect(mocks.state.watchRun).toHaveBeenCalledTimes(1)
    expect(mocks.getResearchSession).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.error-state').text()).toContain('论文助手执行失败')
  })

  it('does not restore a conversation that belongs to another paper', async () => {
    mocks.getResearchSession.mockResolvedValue({
      session: { id: 91, primaryPaperId: 2, papers: [{ id: 2, title: '其他论文' }] },
      messages: [{ id: 1, role: 'USER', content: '不应出现', evidence: {} }],
      runs: [],
    })
    const wrapper = mountPanel({ researchSessionId: 91 })
    await flushPromises()

    expect(wrapper.emitted('research-session-change')).toContainEqual([null])
    expect(wrapper.findAll('.chat-message')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('不应出现')
  })

  it('renders math and tables while keeping evidence collapsed by default', async () => {
    mocks.state.run.mockResolvedValue({
      runId: 'rich-answer',
      result: {
        answer: '变量取值为 1。\n\n| 变量 | 值 |\n| --- | --- |\n| $x$ | 1 |',
        claims: [{ text: '变量取值为 1', evidenceIds: ['e-1'] }],
        evidence: [{ evidenceId: 'e-1', page: 2, text: 'x = 1' }],
      },
    })
    const wrapper = mountPanel({ selection: textSelection, selectionAnchor: textAnchor })
    await flushPromises()
    await wrapper.get('.assistant-composer textarea').setValue('给出表格')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    expect(wrapper.find('.answer-text table').exists()).toBe(true)
    expect(wrapper.find('.answer-text .katex').exists()).toBe(true)
    const inlineCitation = wrapper.get('.answer-text a[href="#evidence-e-1"]')
    await inlineCitation.trigger('click')
    expect(wrapper.emitted('jump-evidence')?.[0][0]).toEqual(
      expect.objectContaining({ evidenceId: 'e-1', page: 2 }),
    )
    const details = wrapper.get('.chat-claim-list')
    expect(details.attributes('open')).toBeUndefined()
    expect(details.get('summary').text()).toContain('查看依据（1）')
    await details.get('.evidence-source__jump').trigger('click')
    expect(wrapper.emitted('jump-evidence')?.[1][0]).toEqual(
      expect.objectContaining({ evidenceId: 'e-1', page: 2 }),
    )
  })

  it('keeps the conversation while each valid new selection replaces the next-message anchor', async () => {
    mocks.state.run.mockResolvedValue({
      runId: 'turn', result: { answer: '回答', claims: [], evidence: [] },
    })
    const wrapper = mountPanel({ selection: textSelection, selectionAnchor: textAnchor })
    await flushPromises()
    await wrapper.findAll('.selection-tools button')
      .find(button => button.text().includes('附加到下一条消息')).trigger('click')
    await wrapper.get('.assistant-composer textarea').setValue('第一问')
    await sendButton(wrapper).trigger('click')
    await flushPromises()
    const firstConversation = mocks.state.run.mock.calls[0][0].researchSessionId
    await wrapper.get('.content-stage-collapsed button').trigger('click')
    await wrapper.get('.selection-clear-action').trigger('click')
    await wrapper.setProps({ selection: null, selectionAnchor: null })

    await wrapper.setProps({
      selection: { text: 'A different selected passage.' },
      selectionAnchor: { ...textAnchor, page: 3 },
    })
    await flushPromises()
    await wrapper.findAll('.selection-tools button')
      .find(button => button.text().includes('附加到下一条消息')).trigger('click')
    expect(wrapper.text()).toContain('第一问')
    await wrapper.get('.assistant-composer textarea').setValue('新选区问题')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    expect(mocks.state.run.mock.calls[1][0].researchSessionId).toBe(firstConversation)
    expect(mocks.state.run.mock.calls[1][0].selectionAnchor.page).toBe(3)
    expect(wrapper.text()).toContain('引用第 2 页选区')
    expect(wrapper.text()).toContain('引用第 3 页选区')
  })

  it('restores all messages belonging to the selected research conversation', async () => {
    mocks.getResearchSession.mockResolvedValue({
      messages: [
        { messageKey: 'old:user', runId: 'old', role: 'USER', content: '旧对话' },
        { messageKey: 'latest:user', runId: 'latest', role: 'USER', content: '当前对话' },
      ],
    })
    mocks.state.run.mockResolvedValue({
      runId: 'restored-turn', result: { answer: '恢复回答', claims: [], evidence: [] },
    })
    const wrapper = mountPanel({
      researchSessionId: 91, selection: textSelection, selectionAnchor: textAnchor,
    })
    await flushPromises()
    expect(wrapper.text()).toContain('当前对话')
    expect(wrapper.text()).toContain('旧对话')
    await wrapper.get('.assistant-composer textarea').setValue('继续追问')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    expect(mocks.state.run.mock.calls[0][0].researchSessionId).toBe(91)
    expect(mocks.createResearchSession).not.toHaveBeenCalled()
  })

  it('collapses after both initial formula confirmation and later correction saves', async () => {
    const formulaAnchor = {
      paperId: 1,
      page: 3,
      boxes: [{ x: 0.2, y: 0.3, width: 0.4, height: 0.1 }],
      anchorText: '\\sum_{k=1}^{K} r_k',
      blockIds: ['formula-region-9'],
      kind: 'FORMULA',
      confidence: 1,
    }
    const wrapper = mountPanel({
      captureMode: 'formula',
      formulaRegion: { page: 3, bbox: formulaAnchor.boxes[0] },
      formulaRecognition: {
        id: 9,
        latex: '\\sum_{k=1}^{K} r_k',
        source: 'MULTIMODAL',
        status: 'CANDIDATE',
        confirmed: false,
        anchor: null,
      },
    })
    await flushPromises()

    expect(wrapper.findComponent(FormulaRegionCard).exists()).toBe(true)
    await wrapper.get('.assistant-composer textarea').setValue('解释这个公式')
    expect(sendButton(wrapper).attributes()).not.toHaveProperty('disabled')

    wrapper.findComponent(FormulaRegionCard).vm.$emit('confirm', ['\\sum_{k=1}^{K} r_k'])
    expect(wrapper.emitted('confirm-formula')?.[0]).toEqual([['\\sum_{k=1}^{K} r_k']])
    await wrapper.setProps({ formulaConfirming: true })
    await wrapper.setProps({
      formulaRecognition: {
        id: 9,
        latex: '\\sum_{k=1}^{K} r_k',
        source: 'USER',
        status: 'CONFIRMED',
        confirmed: true,
        anchor: formulaAnchor,
      },
    })
    await wrapper.setProps({ formulaConfirming: false })
    await flushPromises()
    expect(wrapper.get('.content-stage-collapsed').text()).toContain('已固定第 3 页公式内容')

    await wrapper.get('.content-stage-collapsed button').trigger('click')
    wrapper.findComponent(FormulaRegionCard).vm.$emit('confirm', ['\\sum_{k=1}^{K} r_k+1'])
    expect(wrapper.emitted('confirm-formula')?.[1]).toEqual([['\\sum_{k=1}^{K} r_k+1']])
    await wrapper.setProps({ formulaConfirming: true })
    await wrapper.setProps({ formulaConfirming: false })
    await flushPromises()
    expect(wrapper.get('.content-stage-collapsed').text()).toContain('已固定第 3 页公式内容')
    await wrapper.get('.assistant-composer textarea').setValue('解释这个公式')
    expect(sendButton(wrapper).attributes()).not.toHaveProperty('disabled')
  })

  it('translates the displayed selection without replacing its source text', async () => {
    mocks.translateTexts.mockResolvedValue({
      targetLanguage: 'ZH',
      items: [{ text: '所选方法解决估计问题。' }],
    })
    const wrapper = mountPanel({ selection: textSelection, selectionAnchor: textAnchor })
    await flushPromises()

    const translate = wrapper.findAll('.selection-tools button')
      .find(button => button.text().includes('翻译为中文'))
    await translate.trigger('click')
    await flushPromises()

    expect(mocks.translateTexts).toHaveBeenCalledWith({
      texts: [textSelection.text],
      sourceLanguage: 'EN',
      targetLanguage: 'ZH',
    })
    expect(wrapper.get('.selection-card__text').text()).toContain('selected method')
    expect(wrapper.get('.selection-translation').text()).toContain('所选方法')
  })
})

function mountPanel(extraProps = {}) {
  return mount(PaperWorkbenchPanel, {
    props: { paper, ...extraProps },
    global: { stubs: defaultStubs },
  })
}

function sendButton(wrapper) {
  return wrapper.findAll('.assistant-composer button')
    .find(button => button.text().includes('发送'))
}
