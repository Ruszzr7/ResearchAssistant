<template>
  <div class="analysis-page">
    <!-- ====== 左栏：论文选择 ====== -->
    <div class="left-panel">
      <h4>论文选择</h4>
      <el-select v-model="mainPaperId" placeholder="从文库选择论文…" filterable style="width:100%"
        @change="onMainPaperSelect" clearable>
        <el-option v-for="p in papers" :key="p.id" :label="p.title" :value="p.id" />
        <template #empty>
          <div style="padding:10px 0;text-align:center;color:#c0c4cc;font-size:13px">暂无论文</div>
        </template>
      </el-select>

      <template v-if="mode === 'compare'">
        <h4 style="margin-top:16px">对比论文</h4>
        <div class="compare-list">
          <div v-for="(p, i) in comparePapers" :key="p.id" class="selected-paper">
            <span class="paper-name">{{ p.title }}</span>
            <span class="paper-remove" @click="comparePapers.splice(i, 1)">✕</span>
          </div>
        </div>
        <el-select v-if="comparePapers.length < 4" v-model="addCompareId" placeholder="+ 添加对比论文"
          filterable style="width:100%" @change="onAddCompare">
          <el-option v-for="p in availablePapers" :key="p.id" :label="p.title" :value="p.id" />
        </el-select>
        <p v-if="comparePapers.length >= 4" style="font-size:11px;color:#909399">最多 5 篇对比</p>
      </template>
    </div>

    <!-- ====== 右栏：分析区域 ====== -->
    <div class="right-panel">
      <!-- 模式切换 -->
      <div class="mode-tabs">
        <button :class="{ active: mode === 'read' }" @click="mode = 'read'">精读</button>
        <button :class="{ active: mode === 'compare' }" @click="mode = 'compare'" :disabled="!mainPaper">对比</button>
        <button :class="{ active: mode === 'recommend' }" @click="mode = 'recommend'" :disabled="!mainPaper">推荐</button>
      </div>

      <!-- 精读 -->
      <div v-if="mode === 'read'" class="mode-content">
        <div v-if="!mainPaper" class="empty-hint">请先在左侧选择一篇论文</div>
        <template v-else>
          <div class="action-row">
            <el-button type="primary" @click="readLoading ? cancelReadWithHint() : doRead()"
              :disabled="!readLoading && (readError || mainPaper.processingStatus === 'COMPLETED')">
              {{ readLoading ? '取消精读' : '开始精读分析' }}
            </el-button>
            <span v-if="readLoading" class="stage-text">{{ readStatus }}</span>
            <span v-else-if="readError" class="error-text">{{ readError.message }}
              <el-button size="small" link type="primary" @click="retryRead()">重试</el-button>
            </span>
          </div>
          <div v-if="readReport" class="view-toggle">
            <el-radio-group v-model="readView" size="small">
              <el-radio-button label="report">报告</el-radio-button>
              <el-radio-button label="structured">结构化</el-radio-button>
            </el-radio-group>
          </div>
          <div v-if="readView === 'report' && readReport" class="report" v-html="readReport"></div>
          <div v-if="readView === 'structured'" class="structured-panel">
            <StructuredAnalysis v-if="analysis" :data="analysis" />
            <div v-else class="empty-hint">暂无结构化分析数据，请先点击「开始精读分析」。</div>
          </div>
        </template>
      </div>

      <!-- 对比 -->
      <div v-if="mode === 'compare'" class="mode-content">
        <div v-if="comparePapers.length < 1" class="empty-hint">请添加至少 1 篇对比论文</div>
        <template v-else>
          <el-input v-model="customDimensions" placeholder="自定义对比维度（可选），如：只对比损失函数设计"
            size="small" clearable style="margin-bottom:8px" />
          <div class="action-row">
            <el-button type="primary" @click="compareLoading ? cancelCompareWithHint() : doCompare()"
              :disabled="!compareLoading && compareError">
              {{ compareLoading ? '取消对比' : '开始对比分析' }}
            </el-button>
            <span v-if="compareLoading" class="stage-text">{{ compareStatus }}</span>
            <span v-else-if="compareError" class="error-text">{{ compareError.message }}
              <el-button size="small" link type="primary" @click="retryCompare()">重试</el-button>
            </span>
          </div>
          <div v-if="compareReport" class="report" v-html="compareReport"></div>
        </template>
      </div>

      <!-- 推荐（库内） -->
      <div v-if="mode === 'recommend'" class="mode-content">
        <div v-if="!mainPaper" class="empty-hint">请先在左侧选择一篇论文</div>
        <template v-else>
          <el-button @click="doRecommend">查找库内相关论文</el-button>
          <div v-if="recommendations.length" class="rec-list">
            <div v-for="r in recommendations" :key="r.id" class="rec-card">
              <div class="rec-title">{{ r.title }}</div>
              <div class="rec-reason">{{ r.matchReason }}</div>
            </div>
          </div>
        </template>
      </div>

      <!-- ====== 追问区 ====== -->
      <div v-if="mainPaper && (readReport || compareReport)" class="chat-area">
        <div class="chat-header">追问</div>
        <div class="chat-messages" ref="chatBox">
          <div v-for="(m, i) in chatHistory" :key="i" :class="'chat-msg ' + m.role">
            <div class="msg-content">{{ m.content }}</div>
          </div>
        </div>
        <div class="chat-input-row">
          <el-input v-model="chatInput" placeholder="对这篇论文还有什么想问的？" size="small"
            @keyup.enter="sendChat" />
          <el-button size="small" type="primary" @click="sendChat" :loading="chatting">发送</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'

import { waitForAnalysis } from '@/utils/analysis.js'
import { waitForTask } from '@/utils/task.js'
import { simpleMarkdownToHtml } from '@/utils/markdown.js'
import StructuredAnalysis from '@/components/StructuredAnalysis.vue'

const route = useRoute()
import api from '@/api'
import { useGlobalTask } from '@/composables/useGlobalTask.js'
import { ElMessage } from 'element-plus'

const papers = ref([])

// ===== 全局后台任务 + 页面状态持久化 =====
const {
  isLoading: readLoading,
  statusText: readStatus,
  error: readError,
  run: runRead,
  cancel: cancelRead,
  retry: retryRead
} = useGlobalTask('analysis-read')
const {
  isLoading: compareLoading,
  statusText: compareStatus,
  error: compareError,
  run: runCompare,
  cancel: cancelCompare,
  retry: retryCompare
} = useGlobalTask('analysis-compare')

const session = useGlobalTask('analysis-session').data
function restoreSession() {
  mainPaperId.value = session.mainPaperId || null
  mainPaper.value = session.mainPaper || null
  comparePapers.value = session.comparePapers || []
  mode.value = session.mode || 'read'
  readReport.value = session.readReport || null
  readView.value = session.readView || 'report'
  analysis.value = session.analysis || null
  compareReport.value = session.compareReport || null
  customDimensions.value = session.customDimensions || ''
}
function saveSession() {
  session.mainPaperId = mainPaperId.value
  session.mainPaper = mainPaper.value
  session.comparePapers = comparePapers.value
  session.mode = mode.value
  session.readReport = readReport.value
  session.readView = readView.value
  session.analysis = analysis.value
  session.compareReport = compareReport.value
  session.customDimensions = customDimensions.value
}

function cancelReadWithHint() {
  cancelRead()
  ElMessage.info('已取消')
}
function cancelCompareWithHint() {
  cancelCompare()
  ElMessage.info('已取消')
}

const mainPaperId = ref(null)
const mainPaper = ref(null)
const comparePapers = ref([])
const addCompareId = ref(null)
const mode = ref('read')
const readReport = ref(null)
const readView = ref('report')
const analysis = ref(null)
const compareReport = ref(null)
const customDimensions = ref('')
const recommendations = ref([])

restoreSession()
watch(mainPaperId, saveSession)
watch(mainPaper, saveSession, { deep: true })
watch(comparePapers, saveSession, { deep: true })
watch(mode, saveSession)
watch(readReport, saveSession)
watch(readView, saveSession)
watch(analysis, saveSession)
watch(compareReport, saveSession)
watch(customDimensions, saveSession)

const chatHistory = ref([])
const chatInput = ref('')
const chatting = ref(false)

const availablePapers = computed(() => {
  const compareIds = new Set(comparePapers.value.map(c => c.id))
  return papers.value.filter(p => p.id !== mainPaper.value?.id && !compareIds.has(p.id))
})

// 加载论文列表
onMounted(async () => {
  try {
    const r = await api.get('/papers', { params: { size: 200 } })
    papers.value = r.data.records || []
    // 从论文库跳转：自动选中论文并切换模式
    const paperId = route.query.paperId
    const qMode = route.query.mode
    if (paperId) {
      mainPaperId.value = Number(paperId)
      onMainPaperSelect(Number(paperId))
      if (qMode) mode.value = qMode
    } else if (mainPaperId.value) {
      onMainPaperSelect(mainPaperId.value)
    }
  } catch (e) { /* 静默 */ }
})

function onMainPaperSelect(id) {
  if (!id) { mainPaper.value = null; return }
  mainPaper.value = papers.value.find(p => p.id === id)
  readReport.value = null; compareReport.value = null
  analysis.value = null
  readView.value = 'report'
}

function onAddCompare(id) {
  const p = papers.value.find(pp => pp.id === id)
  if (p) { comparePapers.value.push(p); addCompareId.value = null }
}

// 精读（流式 SSE）
async function doRead() {
  const paperId = mainPaper.value?.id
  if (!paperId) return

  await runRead(async ({ signal, setStage }) => {
    readReport.value = ''
    analysis.value = null
    chatHistory.value = []

    // 先确保论文有 AI 分析
    setStage('正在提交分析任务…')
    await api.post('/agent/process/' + paperId, {}, { signal })

    // 轮询等待分析完成
    setStage('等待分析完成…')
    await waitForAnalysis(api.get.bind(api), paperId, signal)

    // 获取结构化分析结果
    try {
      const { data } = await api.get('/agent/analysis/' + paperId, { signal })
      analysis.value = data
    } catch (e) {
      console.warn('获取结构化分析失败', e)
    }

    // SSE 流式消费
    setStage('正在流式输出分析报告…')
    await startStream(paperId, signal)
  })
}

async function startStream(paperId, signal) {
  const response = await fetch(`/api/agent/process/${paperId}/stream`, { signal })
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    while (true) {
      let done, value
      try {
        ({ done, value } = await reader.read())
      } catch (e) {
        if (e.name === 'AbortError') break
        throw e
      }
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      // 解析 SSE：按双换行分割
      const parts = buffer.split('\n\n')
      buffer = parts.pop() // 最后一段可能不完整
      for (const part of parts) {
        const event = part.startsWith('event:error') || part.includes('\nevent:error') ? 'error' : 'token'
        const lines = part.split('\n')
        for (const line of lines) {
          if (line.startsWith('data:')) {
            const data = line.slice(5).trim()
            if (event === 'token') {
              readReport.value += data
            } else {
              ElMessage.error('分析错误：' + data)
            }
          }
        }
      }
    }
  } finally {
    try { await reader.cancel() } catch {}
  }
  // 将 markdown 转为 HTML
  readReport.value = simpleMarkdownToHtml(readReport.value)
}

// 对比
async function doCompare() {
  const allIds = [mainPaper.value.id, ...comparePapers.value.map(p => p.id)]
  await runCompare(async ({ signal, setStage }) => {
    compareReport.value = null
    setStage('正在提交对比任务…')
    const submitRes = await api.post('/agent/compare', {
      paperIds: allIds,
      customDimensions: customDimensions.value || null
    }, { signal })
    const taskId = submitRes.data.taskId
    setStage('正在生成对比报告…')
    const result = await waitForTask(api.get.bind(api), taskId, signal, setStage)
    compareReport.value = result
  })
}

// 库内推荐
function doRecommend() {
  const paper = mainPaper.value
  if (!paper) return

  // 预先解析主论文的作者与关键词，避免循环内重复计算
  let mainAuthors = []
  try {
    mainAuthors = (typeof paper.authors === 'string' ? JSON.parse(paper.authors) : paper.authors || [])
      .map(a => a.name?.trim().toLowerCase())
      .filter(Boolean)
  } catch {}
  const mainAuthorSet = new Set(mainAuthors)
  const mainKeywords = (paper.keywords || '').split(',').map(k => k.trim().toLowerCase()).filter(Boolean)

  const results = []
  for (const p of papers.value) {
    if (p.id === paper.id) continue
    const reasons = []
    // 同作者
    try {
      const authors2 = (typeof p.authors === 'string' ? JSON.parse(p.authors) : p.authors || [])
        .map(a => a.name?.trim().toLowerCase())
        .filter(Boolean)
      const overlap = authors2.filter(n => mainAuthorSet.has(n))
      if (overlap.length) reasons.push(`共享作者：${overlap.join(', ')}`)
    } catch {}
    // 同关键词
    const k2 = (p.keywords || '').split(',').map(k => k.trim().toLowerCase()).filter(Boolean)
    const kOverlap = k2.filter(k => mainKeywords.includes(k))
    if (kOverlap.length) reasons.push(`共享 ${kOverlap.length} 个关键词：${kOverlap.join(', ')}`)
    // 同文件夹
    if (paper.folderId && paper.folderId === p.folderId) reasons.push('同一文件夹')
    if (reasons.length) {
      results.push({ ...p, matchReason: reasons.join('；') })
    }
  }
  results.sort((a, b) => b.matchReason.length - a.matchReason.length)
  recommendations.value = results.slice(0, 10)
}

// 追问
async function sendChat() {
  if (!chatInput.value.trim()) return
  chatHistory.value.push({ role: 'user', content: chatInput.value })
  chatting.value = true
  const q = chatInput.value
  chatInput.value = ''
  try {
    const ctx = readReport.value || compareReport.value || ''
    const res = await api.post('/agent/chat', {
      context: ctx,
      question: q
    })
    chatHistory.value.push({ role: 'assistant', content: res.data || '抱歉，我无法回答这个问题。' })
  } catch (e) {
    chatHistory.value.push({ role: 'assistant', content: '对话出错：' + (e.response?.data?.message || e.message) })
  } finally {
    chatting.value = false
  }
}
</script>

<style scoped>
.analysis-page {
  display: flex;
  height: calc(100vh - 61px);
}

/* 左栏 */
.left-panel {
  width: 240px;
  flex-shrink: 0;
  padding: 10px 14px;
  border-right: 1px solid var(--ra-border);
  overflow-y: auto;
  background: var(--ra-bg);
}
.left-panel h4 {
  font-size: 15px; font-weight: 600; color: var(--ra-text); margin: 0 0 10px;
}

.selected-paper {
  display: flex; align-items: center; justify-content: space-between;
  padding: 6px 8px; background: var(--ra-active-bg); border-radius: 4px; margin-bottom: 4px;
  font-size: 12px;
}
.paper-name {
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1;
}
.paper-remove {
  cursor: pointer; color: #f56c6c; margin-left: 4px; flex-shrink: 0;
}

.compare-list { margin-bottom: 8px; }

/* 右栏 */
.right-panel {
  flex: 1; padding: 16px 24px; overflow-y: auto;
}

.mode-tabs {
  display: flex; gap: 0; margin-bottom: 20px; border-bottom: 2px solid var(--ra-border);
}
.mode-tabs button {
  padding: 8px 20px; border: none; background: none;
  font-size: 14px; cursor: pointer; color: var(--ra-text-tertiary);
  border-bottom: 2px solid transparent; margin-bottom: -2px;
  transition: color 0.2s, border-color 0.2s;
}
.mode-tabs button.active {
  color: var(--ra-link); border-bottom-color: var(--ra-link); font-weight: 600;
}
.mode-tabs button:disabled { color: var(--ra-text-tertiary); cursor: not-allowed; }

.mode-content { min-height: 200px; }

.action-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.stage-text {
  font-size: 13px;
  color: var(--ra-link);
}
.error-text {
  font-size: 13px;
  color: #f56c6c;
}

.domain-tag { margin: 12px 0; font-size: 13px; color: var(--ra-text-secondary); }

.empty-hint {
  text-align: center; color: var(--ra-text-tertiary); padding: 60px 0; font-size: 14px;
}

.report {
  margin-top: 16px; font-size: 14px; line-height: 1.8; color: var(--ra-text);
}
.report :deep(h4) { font-size: 15px; margin: 16px 0 6px; }
.report :deep(p) { margin: 6px 0; }

.view-toggle {
  margin: 12px 0;
}

.structured-panel {
  margin-top: 16px;
}

.rec-list { margin-top: 16px; }
.rec-card {
  padding: 10px 0; border-bottom: 1px solid var(--ra-border-light);
}
.rec-title { font-size: 13px; font-weight: 600; color: var(--ra-text); }
.rec-reason { font-size: 12px; color: #67c23a; margin-top: 2px; }

/* 追问 */
.chat-area {
  margin-top: 32px; border-top: 1px solid var(--ra-border); padding-top: 16px;
}
.chat-header { font-size: 14px; font-weight: 600; margin-bottom: 8px; }
.chat-messages {
  max-height: 200px; overflow-y: auto; margin-bottom: 8px;
}
.chat-msg { margin-bottom: 8px; }
.chat-msg.user .msg-content { background: var(--ra-active-bg); color: var(--ra-text); }
.chat-msg.assistant .msg-content { background: var(--ra-bg); color: var(--ra-text-secondary); }
.msg-content {
  display: inline-block; max-width: 80%; padding: 6px 12px; border-radius: 8px;
  font-size: 13px; line-height: 1.5; white-space: pre-wrap;
}
.chat-input-row {
  display: flex; gap: 8px;
}
</style>
