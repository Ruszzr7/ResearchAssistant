<template>
  <div class="gap-page">
    <!-- ====== 左栏：论文勾选 ====== -->
    <div class="left-panel">
      <div class="left-header">
        <h4>选择论文</h4>
        <div class="left-actions">
          <el-button size="small" text @click="selectAll">全选</el-button>
          <el-button size="small" text @click="selectedIds = []">取消</el-button>
        </div>
      </div>
      <el-input v-model="paperFilter" placeholder="搜索论文…" size="small" clearable style="margin-bottom:8px" />
      <div class="paper-checklist">
        <el-checkbox-group v-model="selectedIds">
          <div v-for="p in filteredPapers" :key="p.id" class="paper-check-item">
            <el-checkbox :value="p.id" :label="p.id">
              <span class="check-title">{{ p.title }}</span>
              <span class="check-meta">{{ p.year }}</span>
            </el-checkbox>
          </div>
        </el-checkbox-group>
        <p v-if="filteredPapers.length === 0" style="color:#c0c4cc;font-size:12px;text-align:center;padding:20px 0">暂无论文</p>
      </div>
    </div>

    <!-- ====== 右栏：Gap 报告 ====== -->
    <div class="right-panel">
      <div class="gap-toolbar">
        <span>已选 <strong>{{ selectedIds.length }}</strong> 篇论文</span>
        <el-button type="primary" @click="gapLoading ? cancelGapWithHint() : startGapAnalysis()"
          :disabled="selectedIds.length < 3">
          {{ gapLoading ? '取消空白分析' : '开始空白分析' }}
        </el-button>
        <span v-if="gapLoading" class="stage-text">{{ gapStatus }}</span>
        <span v-else-if="gapError" class="error-text">{{ gapError.message }}
          <el-button size="small" link type="primary" @click="retryGap()">重试</el-button>
        </span>
      </div>

      <!-- Gap 报告 -->
      <div v-if="gapResults.length" class="gap-report">
        <div v-for="(gap, idx) in gapResults" :key="idx" class="gap-card" :class="'level-' + gap.level">
          <div class="gap-header">
            <span class="gap-level">
              {{ levelMeta[gap.level]?.emoji || '🟢' }}
              {{ levelMeta[gap.level]?.label || '已被明确解决' }}
            </span>
            <el-tag size="small" :type="levelMeta[gap.level]?.tagType || 'success'">
              {{ gap.category }}
            </el-tag>
          </div>
          <h4>{{ gap.title }}</h4>
          <div class="gap-section">
            <span class="gap-label">描述</span>
            <p>{{ gap.description }}</p>
          </div>
          <div class="gap-section">
            <span class="gap-label">依据</span>
            <p>{{ gap.basis }}</p>
          </div>
          <div class="gap-section" v-if="gap.externalResult">
            <span class="gap-label">外部验证</span>
            <p>{{ gap.externalResult }}</p>
          </div>
          <div class="gap-section" v-if="gap.evidence?.length">
            <span class="gap-label">证据</span>
            <div class="evidence-list">
              <div v-for="(ev, i) in gap.evidence" :key="i" class="evidence-item">
                <div class="evidence-header">
                  <a :href="ev.url" target="_blank" rel="noopener" class="evidence-title">{{ ev.title }}</a>
                  <el-tag size="small" type="info">{{ ev.source }}</el-tag>
                  <span v-if="ev.year" class="evidence-year">{{ ev.year }}</span>
                </div>
                <p class="evidence-snippet">{{ ev.snippet }}</p>
              </div>
            </div>
          </div>
          <div class="gap-section">
            <span class="gap-label">建议</span>
            <p>{{ gap.suggestion }}</p>
          </div>
        </div>

        <!-- 导出 -->
        <div class="gap-actions">
          <el-button size="small" @click="copyGapReport">复制报告</el-button>
          <el-button size="small" @click="downloadGapMd">导出 Markdown</el-button>
        </div>
      </div>

      <!-- 空状态 -->
      <div v-else-if="!gapLoading" class="empty-hint">
        从库中勾选至少 3 篇代表领域现状的论文，点击"开始空白分析"
      </div>

      <!-- ====== 追问 ====== -->
      <div v-if="gapResults.length" class="chat-area">
        <div class="chat-header">追问</div>
        <div class="chat-messages">
          <div v-for="(m, i) in chatHistory" :key="i" :class="'chat-msg ' + m.role">
            <div class="msg-content">{{ m.content }}</div>
          </div>
        </div>
        <div class="chat-input-row">
          <el-input v-model="chatInput" placeholder="对这些 Gap 还有什么想问的？" size="small"
            @keyup.enter="sendGapChat" />
          <el-button size="small" type="primary" @click="sendGapChat" :loading="chatting">发送</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import api from '@/api'
import { useGlobalTask } from '@/composables/useGlobalTask.js'
import { waitForTask } from '@/utils/task.js'
import { ElMessage } from 'element-plus'

const papers = ref([])
const selectedIds = ref([])
const paperFilter = ref('')

// ===== 全局后台任务 + 页面状态持久化 =====
const {
  isLoading: gapLoading,
  statusText: gapStatus,
  error: gapError,
  run: runGap,
  cancel: cancelGap,
  retry: retryGap
} = useGlobalTask('gap-analysis')

const session = useGlobalTask('gap-session').data
function restoreSession() {
  selectedIds.value = session.selectedIds || []
  paperFilter.value = session.paperFilter || ''
  gapResults.value = session.gapResults || []
  chatHistory.value = session.chatHistory || []
}
function saveSession() {
  session.selectedIds = selectedIds.value
  session.paperFilter = paperFilter.value
  session.gapResults = gapResults.value
  session.chatHistory = chatHistory.value
}

function cancelGapWithHint() {
  cancelGap()
  ElMessage.info('已取消')
}

const gapResults = ref([])
const chatHistory = ref([])
const chatInput = ref('')
const chatting = ref(false)

// Gap 验证等级 → 展示元数据
const levelMeta = {
  red: { emoji: '🔴', label: '未发现相关研究', tagType: 'danger' },
  yellow: { emoji: '🟡', label: '有相关工作', tagType: 'warning' },
  green: { emoji: '🟢', label: '已被明确解决', tagType: 'success' }
}

restoreSession()
watch(selectedIds, saveSession, { deep: true })
watch(paperFilter, saveSession)
watch(gapResults, saveSession, { deep: true })
watch(chatHistory, saveSession, { deep: true })

const filteredPapers = computed(() => {
  if (!paperFilter.value) return papers.value
  const kw = paperFilter.value.toLowerCase()
  return papers.value.filter(p => p.title?.toLowerCase().includes(kw))
})

function selectAll() {
  selectedIds.value = filteredPapers.value.map(p => p.id)
}

// 加载论文
onMounted(async () => {
  try {
    const r = await api.get('/papers', { params: { size: 500 } })
    papers.value = r.data.records || []
  } catch (e) { /* 静默 */ }
})

// ==== Gap 分析：提交异步任务并轮询 ====
async function startGapAnalysis() {
  await runGap(async ({ signal, setStage }) => {
    gapResults.value = []
    chatHistory.value = []

    setStage('正在提交空白分析任务…')
    const submitRes = await api.post('/agent/gap', { paperIds: selectedIds.value }, { signal })
    const taskId = submitRes.data.taskId

    setStage('正在分析研究空白…')
    const result = await waitForTask(api.get.bind(api), taskId, signal, setStage)
    const gapsMarkdown = result.gaps || ''
    const verified = result.verified || []

    // 合并渲染
    gapResults.value = parseGapsFromMarkdown(gapsMarkdown, verified)
  })
}

/**
 * 从 markdown 报告中解析结构化 Gap，并与外部验证结果合并。
 * @param {string} md - LLM 生成的 Gap 报告（markdown）
 * @param {Array} verified - 外部验证结果 [{gapTitle, level, reason, evidence, label, resultCount}]
 */
function parseGapsFromMarkdown(md, verified) {
  const gaps = []
  const sections = md.split(/(?=^###?\s+Gap\s*\d|^###?\s+🟡|^###?\s+🔴|^###?\s+🟢)/m)
  const verifiedByTitle = new Map()
  for (const v of verified || []) {
    if (v.gapTitle) verifiedByTitle.set(v.gapTitle.slice(0, 10), v)
  }
  for (const sec of sections) {
    if (!sec.trim()) continue
    const title = sec.match(/^###?\s*(.+)/m)
    const gapTitle = title ? title[1].trim() : 'Gap'

    const key = gapTitle.slice(0, 10)
    const v = verifiedByTitle.get(key) || verified?.find(v => v.gapTitle?.includes(key))

    const level = v ? v.level : (sec.includes('🔴') ? 'red' : sec.includes('🟡') ? 'yellow' : 'green')
    const category = v
      ? (level === 'red' ? '潜在 Gap' : level === 'yellow' ? '待验证 Gap' : '已有研究')
      : '方法 Gap'
    const reasonText = v?.reason || v?.label || '未进行外部验证'
    const countText = v?.resultCount !== undefined ? `检索到 ${v.resultCount} 项证据` : '未计数'

    const gap = {
      title: gapTitle,
      category,
      description: sec.slice(title ? title[0].length : 0).trim().slice(0, 300),
      basis: '库内论文覆盖空白',
      level,
      suggestion: '建议深入调研后评估可行性',
      externalResult: v ? `${reasonText}（${countText}）` : null,
      evidence: v?.evidence || []
    }
    gaps.push(gap)
  }
  return gaps.length ? gaps : [{
    title: '分析结果',
    category: '综合',
    description: md.slice(0, 500),
    basis: '详见原始报告',
    level: 'yellow',
    suggestion: '请查看完整报告',
    evidence: []
  }]
}

// ==== 追问 ====
async function sendGapChat() {
  if (!chatInput.value.trim()) return
  chatHistory.value.push({ role: 'user', content: chatInput.value })
  chatting.value = true
  const q = chatInput.value
  chatInput.value = ''
  try {
    const res = await api.post('/agent/gap/chat', {
      context: JSON.stringify(gapResults.value),
      question: q
    })
    chatHistory.value.push({ role: 'assistant', content: res.data || '无法回答' })
  } catch (e) {
    chatHistory.value.push({ role: 'assistant', content: '抱歉，暂时无法回答。' })
  } finally {
    chatting.value = false
  }
}

// ==== 导出 ====
function buildGapReportText() {
  let text = '# 研究空白分析报告\n\n'
  for (const g of gapResults.value) {
    const meta = levelMeta[g.level]
    const levelEmoji = meta?.emoji || '🟢'
    text += `## ${levelEmoji} ${g.title}\n- **分类**: ${g.category}\n- **描述**: ${g.description}\n- **依据**: ${g.basis}\n- **建议**: ${g.suggestion}\n\n`
  }
  return text
}

function copyGapReport() {
  navigator.clipboard.writeText(buildGapReportText()).then(() => ElMessage.success('已复制'))
}

function downloadGapMd() {
  const text = buildGapReportText()
  const blob = new Blob([text], { type: 'text/markdown' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = 'gap-analysis.md'
  a.click()
}
</script>

<style scoped>
.gap-page {
  display: flex;
  height: calc(100vh - 61px);
}

/* 左栏 */
.left-panel {
  width: 240px; flex-shrink: 0; padding: 10px 14px;
  border-right: 1px solid var(--ra-border); overflow-y: auto;
  background: var(--ra-bg);
}
.left-header {
  display: flex; align-items: center;
  margin-bottom: 8px;
}
.left-header h4 { font-size: 15px; font-weight: 600; color: var(--ra-text); margin: 0; }
.left-actions { margin-left: auto; display: flex; gap: 4px; }
.left-actions :deep(.el-button + .el-button) { margin-left: 0 !important; }
.paper-checklist { max-height: calc(100vh - 180px); overflow-y: auto; }
.paper-check-item { padding: 4px 0; border-bottom: 1px solid var(--ra-border-light); }
.check-title { font-size: 12px; color: var(--ra-text); }
.check-meta { font-size: 11px; color: var(--ra-text-tertiary); margin-left: 6px; }

/* 右栏 */
.right-panel {
  flex: 1; padding: 16px 24px; overflow-y: auto;
}
.gap-toolbar {
  display: flex; align-items: center; gap: 12px; margin-bottom: 24px;
  font-size: 14px; color: var(--ra-text-secondary);
}
.stage-text { font-size: 13px; color: var(--ra-link); }
.error-text { font-size: 13px; color: #f56c6c; }

/* Gap 卡片 */
.gap-card {
  border: 1px solid var(--ra-border); border-radius: 8px; padding: 16px; margin-bottom: 16px;
}
.gap-card.level-red { border-left: 4px solid #f56c6c; }
.gap-card.level-yellow { border-left: 4px solid #e6a23c; }
.gap-card.level-green { border-left: 4px solid #67c23a; }

.gap-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 8px;
}
.gap-level { font-size: 13px; font-weight: 600; }

.gap-card h4 {
  font-size: 15px; margin: 0 0 12px; color: var(--ra-text);
}

.gap-section {
  margin-bottom: 8px;
}
.gap-label {
  font-size: 11px; color: var(--ra-text-tertiary); display: block; margin-bottom: 2px;
}
.gap-section p {
  font-size: 13px; color: var(--ra-text-secondary); margin: 0; line-height: 1.6;
}

.gap-actions {
  display: flex; gap: 8px; margin-top: 16px;
}

.evidence-list {
  display: flex; flex-direction: column; gap: 10px;
}
.evidence-item {
  border: 1px solid var(--ra-border-light); border-radius: 6px; padding: 10px 12px;
  background: var(--ra-bg);
}
.evidence-header {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 6px;
}
.evidence-title {
  font-size: 13px; color: var(--ra-link); text-decoration: none; font-weight: 500;
}
.evidence-title:hover { text-decoration: underline; }
.evidence-year {
  font-size: 12px; color: var(--ra-text-tertiary);
}
.evidence-snippet {
  font-size: 12px; color: var(--ra-text-secondary); margin: 0; line-height: 1.5;
}

.empty-hint {
  text-align: center; color: var(--ra-text-tertiary); padding: 80px 0; font-size: 14px;
}

/* 追问 */
.chat-area {
  margin-top: 32px; border-top: 1px solid var(--ra-border); padding-top: 16px;
}
.chat-header { font-size: 14px; font-weight: 600; margin-bottom: 8px; }
.chat-messages { max-height: 200px; overflow-y: auto; margin-bottom: 8px; }
.chat-msg { margin-bottom: 8px; }
.chat-msg.user .msg-content { background: var(--ra-active-bg); color: var(--ra-text); }
.chat-msg.assistant .msg-content { background: var(--ra-bg); color: var(--ra-text-secondary); }
.msg-content {
  display: inline-block; max-width: 80%; padding: 6px 12px; border-radius: 8px;
  font-size: 13px; line-height: 1.5; white-space: pre-wrap;
}
.chat-input-row { display: flex; gap: 8px; }
</style>
