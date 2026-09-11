import {
  PHASE0_FORMULA_BOXES,
  PHASE0_FORMULA_FOCUS_BOXES,
  PHASE0_FORMULA_TEXT,
  PHASE0_PAPER_BOXES,
  PHASE0_PAPER_TEXT,
  createMinimalPdf,
} from './minimalPdf.js'

const PAPER_ID = 184
const SESSION_ID = 9001
const SOURCE_ID = 'phase0-source-1'
const FORMULA_SOURCE_ID = 'phase0-formula-1'
const RUN_ID = 'phase0-run-1'

const paper = {
  id: PAPER_ID,
  title: '阶段 0 固定论文',
  authors: 'Research Assistant',
  year: 2026,
  source: 'Phase 0 fixture',
  abstractText: 'Deterministic browser fixture for the core reading workflow.',
  keywords: 'e2e, evidence, pdf',
  pdfPath: 'phase0-fixture.pdf',
  processingStatus: 'COMPLETED',
  readingStatus: 'UNREAD',
  currentPage: 0,
  pageCount: 1,
  readSeconds: 0,
  tags: [],
  folderId: null,
}

const session = {
  id: SESSION_ID,
  title: '阶段 0 固定论文',
  primaryPaperId: PAPER_ID,
  papers: [{ id: PAPER_ID, title: paper.title }],
  messageCount: 0,
  lastPage: 1,
  lastActivityAt: '2026-09-10T00:00:00Z',
}

const completeText = PHASE0_PAPER_TEXT.join(' ')
const evidence = {
  evidenceId: SOURCE_ID,
  sourceObjectId: SOURCE_ID,
  evidenceKey: 'physical:phase0-source-1',
  paperId: PAPER_ID,
  blockId: 'phase0-block-1',
  page: 1,
  contentType: 'TEXT',
  textFormat: 'PLAIN_TEXT',
  textReliable: true,
  quote: PHASE0_PAPER_TEXT[0],
  fullText: completeText,
  locators: [{
    locatorId: 'phase0-locator-1',
    pageNumber: 1,
    contentRects: PHASE0_PAPER_BOXES,
    focusRects: PHASE0_PAPER_BOXES,
    precision: 'BLOCK',
  }],
}

const formulaEvidence = {
  evidenceId: FORMULA_SOURCE_ID,
  sourceObjectId: FORMULA_SOURCE_ID,
  evidenceKey: 'physical:phase0-formula-1',
  paperId: PAPER_ID,
  blockId: 'phase0-formula-block-1',
  page: 1,
  contentType: 'FORMULA',
  textFormat: 'LATEX',
  textReliable: true,
  formulaNumber: '1',
  quote: '公式 (1)',
  fullText: 'x = \\alpha + \\beta',
  locators: [{
    locatorId: 'phase0-formula-locator-1',
    pageNumber: 1,
    contentRects: PHASE0_FORMULA_BOXES,
    focusRects: PHASE0_FORMULA_FOCUS_BOXES,
    targetText: PHASE0_FORMULA_TEXT,
    formulaNumber: '1',
    precision: 'FORMULA_REGION',
  }],
}

const textAnswer = '核心创新是把完整来源与页面定位绑定在同一个可恢复的阅读链路中。'
const formulaAnswer = '关键公式为 $x = \\alpha + \\beta$（式 1）。'
const answer = `${textAnswer} ${formulaAnswer}`

function response(data, status = 200) {
  return { status, contentType: 'application/json', body: JSON.stringify({ code: status, data }) }
}

function errorResponse(status, message) {
  return { status, contentType: 'application/json', body: JSON.stringify({ code: status, message }) }
}

function answerMessage() {
  return {
    id: 'phase0-assistant-message',
    messageKey: 'phase0-assistant-message',
    role: 'ASSISTANT',
    content: answer,
    evidence: {
      claims: [
        { text: textAnswer, evidenceIds: [SOURCE_ID] },
        { text: formulaAnswer, evidenceIds: [FORMULA_SOURCE_ID] },
      ],
      evidence: [evidence, formulaEvidence],
      answerBlocks: [],
    },
    runId: RUN_ID,
  }
}

function sessionDetail(messages, baseSession = session) {
  return {
    session: { ...baseSession, messageCount: messages.length, lastActivityAt: '2026-09-10T00:00:01Z' },
    messages,
  }
}

function archiveSessions(messages) {
  if (!messages.length) return []
  return Array.from({ length: 21 }, (_, index) => ({
    ...session,
    id: SESSION_ID + index,
    title: index === 0 ? session.title : `阶段 2 历史对话 ${index + 1}`,
    messageCount: messages.length,
    lastActivityAt: `2026-09-${String(10 - Math.min(index, 8)).padStart(2, '0')}T00:00:01Z`,
  }))
}

/**
 * Install a deterministic browser-level adapter for the phase 0 chain.
 * It intercepts every /api request, so the test never touches the user's MySQL
 * database, PDF directory, model provider, or persisted browser storage.
 */
export async function installPaperFlowMock(page, options = {}) {
  const state = {
    imported: false,
    paper: { ...paper },
    messages: [],
    requests: [],
    blockedRequests: [],
    agentTrajectory: [],
    uploadAttempts: 0,
    pdf: createMinimalPdf(),
  }

  await page.addInitScript(() => {
    window.localStorage.clear()
    window.sessionStorage.clear()
  })

  await page.route('**/*', async route => {
    const request = route.request()
    const url = new URL(request.url())
    // A broad route pattern is used because Playwright's `**/api/**` also
    // matches Vite source modules such as `/src/api/index.js`.
    if (!url.pathname.startsWith('/api/')) return route.fallback()
    const path = url.pathname.replace(/^\/api/, '')
    state.requests.push({ method: request.method(), path })

    if (path === '/folders' && request.method() === 'GET') return route.fulfill(response([]))
    if (path === '/tags' && request.method() === 'GET') return route.fulfill(response([]))

    if (path === '/dashboard' && request.method() === 'GET') {
      const p = state.imported ? state.paper : null
      return route.fulfill(response({
        paperStats: {
          total: p ? 1 : 0,
          unread: p?.readingStatus === 'UNREAD' ? 1 : 0,
          reading: p?.readingStatus === 'READING' ? 1 : 0,
          read: p?.readingStatus === 'READ' ? 1 : 0,
          uncategorized: p && p.folderId == null ? 1 : 0,
          pinned: 0,
          thisMonth: p ? 1 : 0,
        },
        folderBacklog: [],
        taskStats: {
          pending: 0, processing: 0, retryWait: 0, completed: 0,
          failed: 0, cancelled: 0, pendingUser: 0, expired: 0,
          deadLetter: 0, historicalFailed: 0, recent: [],
        },
      }))
    }

    if (path === '/papers' && request.method() === 'GET') {
      const records = state.imported ? [{ ...state.paper }] : []
      return route.fulfill(response({ records, total: records.length, current: 1, size: 20, pages: 1 }))
    }
    if (path === '/papers/upload' && request.method() === 'POST') {
      state.uploadAttempts += 1
      const body = request.postData() || ''
      const overwrite = /name="overwrite"[\s\S]*?\r?\n\r?\ntrue/.test(body)
      if (options.invalidUploadOnce && state.uploadAttempts === 1) {
        return route.fulfill(errorResponse(400, '文件不是有效的 PDF，或 PDF 无法打开'))
      }
      if (options.overwriteFailure && state.uploadAttempts === 2 && !overwrite) {
        return route.fulfill(errorResponse(409, '文献已存在，是否覆盖？'))
      }
      if (options.overwriteFailure && state.uploadAttempts === 3 && overwrite) {
        return route.fulfill(errorResponse(500, '数据库写入失败'))
      }
      state.imported = true
      state.paper = { ...paper }
      return route.fulfill(response({ paper: { ...state.paper }, taskId: 'phase0-import-task' }))
    }
    if (path === `/papers/${PAPER_ID}` && request.method() === 'DELETE') {
      state.imported = false
      state.messages = []
      return route.fulfill(response(null))
    }
    if (path === '/research-automation/task/phase0-import-task' && request.method() === 'GET') {
      return route.fulfill(response({
        taskId: 'phase0-import-task',
        status: 'COMPLETED',
        stageText: '解析与论文理解已完成',
        result: { status: 'COMPLETED', paperId: PAPER_ID },
      }))
    }
    if (path === `/papers/${PAPER_ID}` && request.method() === 'GET') {
      return route.fulfill(response({ ...state.paper }))
    }
    if (path === `/papers/${PAPER_ID}/pdf` && request.method() === 'GET') {
      return route.fulfill({
        status: 200,
        contentType: 'application/pdf',
        headers: {
          'Accept-Ranges': 'bytes',
          'Content-Length': String(state.pdf.length),
        },
        body: state.pdf,
      })
    }
    if (path === `/papers/${PAPER_ID}/annotations` && request.method() === 'GET') {
      return route.fulfill(response([]))
    }
    if (path === `/papers/${PAPER_ID}/readiness` && request.method() === 'GET') {
      return route.fulfill(response({
        paperId: PAPER_ID,
        status: 'READY',
        statusText: '论文理解已就绪',
        fileReady: true,
        localReady: true,
        profileReady: true,
        visualReady: true,
        conversationReady: true,
      }))
    }
    if (path === `/papers/${PAPER_ID}/reading-progress` && request.method() === 'GET') {
      return route.fulfill(response({ ...state.paper }))
    }
    if (path === `/papers/${PAPER_ID}/reading-progress` && request.method() === 'POST') {
      const body = request.postDataJSON() || {}
      state.paper.currentPage = Number(body.currentPage) || state.paper.currentPage || 1
      if (state.paper.readingStatus === 'UNREAD') state.paper.readingStatus = 'READING'
      return route.fulfill(response({ ...state.paper }))
    }
    if (path === `/papers/${PAPER_ID}/reading-status` && request.method() === 'POST') {
      const body = request.postDataJSON() || {}
      state.paper.readingStatus = body.status || state.paper.readingStatus
      return route.fulfill(response({ ...state.paper }))
    }

    if (path === '/research/sessions' && request.method() === 'GET') {
      const allRecords = archiveSessions(state.messages)
      const requestedPage = Math.max(1, Number(url.searchParams.get('page')) || 1)
      const requestedSize = Math.max(1, Number(url.searchParams.get('size')) || 20)
      const records = allRecords.slice((requestedPage - 1) * requestedSize, requestedPage * requestedSize)
      return route.fulfill(response({
        records,
        total: allRecords.length,
        current: requestedPage,
        size: requestedSize,
        pages: allRecords.length ? Math.ceil(allRecords.length / requestedSize) : 0,
      }))
    }
    if (path === '/research/sessions' && request.method() === 'POST') {
      return route.fulfill(response({ ...session }))
    }
    if (/^\/research\/sessions\/\d+$/.test(path) && request.method() === 'GET') {
      const sessionId = Number(path.split('/').pop())
      const selected = archiveSessions(state.messages).find(item => item.id === sessionId) || session
      return route.fulfill(response(sessionDetail(state.messages, selected)))
    }
    if (/^\/research\/sessions\/\d+$/.test(path) && request.method() === 'PUT') {
      return route.fulfill(response({ ...session }))
    }
    if (path === '/agent/turns' && request.method() === 'POST') {
      state.agentTrajectory.push(
        { step: 'load_profile', skill: 'paper-profile', status: 'completed' },
        {
          step: 'retrieve_evidence',
          skill: 'paper-evidence',
          needs: [{
            id: 'core-innovation',
            objective: '确认论文的核心创新点',
            query: PHASE0_PAPER_TEXT[0],
          }],
          sourceObjectIds: [SOURCE_ID],
          status: 'found',
        },
        {
          step: 'submit_answer',
          sourceObjectIds: [SOURCE_ID, FORMULA_SOURCE_ID],
          status: 'completed',
        },
      )
      state.messages = [
        { id: 'phase0-user-message', messageKey: 'phase0-user-message', role: 'USER', content: '论文的核心创新点是什么？' },
        answerMessage(),
      ]
      return route.fulfill(response({
        runId: RUN_ID,
        status: 'COMPLETED',
        message: answer,
        citations: [
          { sourceObjectId: SOURCE_ID, answerStart: 0, answerEnd: textAnswer.length },
          {
            sourceObjectId: FORMULA_SOURCE_ID,
            answerStart: answer.indexOf(formulaAnswer),
            answerEnd: answer.length,
          },
        ],
        evidence: [evidence, formulaEvidence],
        pendingActions: [],
      }))
    }

    // Optional background persistence must not make the deterministic flow flaky.
    if (path.startsWith(`/papers/${PAPER_ID}/reading-time`)
      || path.startsWith(`/research/sessions/${SESSION_ID}`)) {
      if (path.startsWith(`/papers/${PAPER_ID}/reading-time`) && request.method() === 'POST') {
        const body = request.postDataJSON() || {}
        state.paper.readSeconds = (state.paper.readSeconds || 0) + Math.max(0, Number(body.seconds) || 0)
      }
      return route.fulfill(response({ ...session }))
    }

    state.blockedRequests.push({ method: request.method(), path })
    return route.abort('blockedbyclient')
  })

  return state
}
