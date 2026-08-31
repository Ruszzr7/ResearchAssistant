<template>
  <div class="search-page">
    <div class="search-heading">
      <div>
        <h1>文献检索</h1>
        <p>用自然语言描述研究方向，由 Agent 提炼关键词并进行多源检索</p>
      </div>
      <el-tooltip content="开启后调用 literature-survey 工作流，支持多源检索与人机确认入库">
        <el-switch v-model="workflowMode" active-text="工作流模式" size="small" />
      </el-tooltip>
    </div>

    <div class="search-progress" aria-label="检索进度">
      <div v-for="(label, index) in ['描述研究方向', 'Agent 理解确认', '精选搜索结果', '扩展检索']" :key="label"
        class="progress-step" :class="{ active: step === index + 1, done: step > index + 1 }">
        <span class="progress-dot">{{ step > index + 1 ? '✓' : index + 1 }}</span>
        <span>{{ label }}</span>
      </div>
    </div>

    <div v-if="step >= 3 && extraction" class="search-context">
      <div><small>当前研究方向</small><strong>{{ userInput }}</strong></div>
      <div class="context-tags">
        <el-tag v-for="kw in extraction.keywords_en" :key="kw" size="small" effect="plain">{{ kw }}</el-tag>
        <el-tag v-if="extraction.time_range" size="small" type="warning" effect="plain">{{ extraction.time_range }}</el-tag>
      </div>
      <el-button size="small" plain @click="step = 1; extraction = null; results = []">重新描述</el-button>
    </div>

    <!-- ====== Step 1: 用户输入 ====== -->
    <div v-if="step === 1" class="step active">
      <div class="step-header">
        <span><span class="step-num">1</span> 描述研究方向</span>
      </div>
      <div v-if="step >= 1" class="step-body">
        <div class="input-row">
          <el-input v-model="userInput" placeholder="用自然语言描述你想研究的方向…"
            size="large" :disabled="step > 1 || extractLoading" @keyup.enter="startExtract" />
          <div class="action-col">
            <el-button type="primary" size="large" @click="extractLoading ? cancelExtractWithHint() : startExtract()"
              :disabled="!userInput.trim()">
              {{ extractLoading ? '取消检索' : '开始检索' }}
            </el-button>
            <span v-if="extractLoading" class="stage-text">{{ extractStatus }}</span>
            <span v-else-if="extractError" class="error-text">{{ extractError.message }}
              <el-button size="small" link type="primary" @click="retryExtract()">重试</el-button>
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- ====== Step 2: Agent 提炼确认 ====== -->
    <div class="step active" v-if="step === 2">
      <div class="step-header"><span class="step-num">2</span> Agent 理解确认 <span style="font-weight:400;font-size:12px;color:#909399">— 可编辑每一项</span></div>
      <div v-if="extraction" class="step-body agent-confirm">
        <div class="extraction-grid">
          <div class="ext-item"><span class="ext-label">领域</span><el-input v-model="extraction.domain" size="small" /></div>
          <div class="ext-item"><span class="ext-label">子方向</span><el-input v-model="extraction.sub_direction" size="small" /></div>
          <div class="ext-item"><span class="ext-label">英文关键词</span>
            <div style="display:flex;gap:4px;flex-wrap:wrap;align-items:center;width:100%">
              <el-tag v-for="(kw,i) in extraction.keywords_en" :key="i" size="small" type="success" closable @close="extraction.keywords_en.splice(i,1)">{{ kw }}</el-tag>
              <el-input v-model="newKeyword" size="small" placeholder="+ 添加" style="width:80px" @keyup.enter="addKeyword" />
            </div>
          </div>
          <div class="ext-item"><span class="ext-label">时间范围</span><el-input v-model="extraction.time_range" size="small" /></div>
          <div class="ext-item"><span class="ext-label">论文类型</span>
            <div style="display:flex;gap:4px;flex-wrap:wrap;align-items:center;width:100%">
              <el-tag v-for="(t,i) in extraction.paper_type" :key="i" size="small" type="warning" closable @close="extraction.paper_type.splice(i,1)">{{ t }}</el-tag>
              <el-input v-model="newPaperType" size="small" placeholder="+ 添加" style="width:100px" @keyup.enter="addPaperType" />
            </div>
          </div>
        </div>
        <div class="confirm-actions" style="margin-top:12px">
          <div class="action-col">
            <el-button type="primary" @click="searchLoading ? cancelSearchWithHint() : confirmAndSearch()"
              :disabled="searchError">
              {{ searchLoading ? '取消搜索' : '✓ 确认，开始搜索' }}
            </el-button>
            <span v-if="searchLoading" class="stage-text">{{ searchStatus }}</span>
            <span v-else-if="searchError" class="error-text">{{ searchError.message }}
              <el-button size="small" link type="primary" @click="retrySearch()">重试</el-button>
            </span>
          </div>
          <el-button @click="step = 1; extraction = null">✏ 重新描述</el-button>
        </div>
      </div>
    </div>

    <!-- ====== Step 3: 搜索结果 ====== -->
    <div class="step active results-step" v-if="step === 3">
      <div class="step-header"><span class="step-num">3</span> 精选搜索结果 <span v-if="results.length" class="result-count">（Top {{ results.length }}）</span></div>
      <div v-if="searchLoading" class="step-body">
        <div class="action-col">
          <p style="color:#409eff">{{ searchStatus }}</p>
          <el-button size="small" @click="cancelSearchWithHint()">取消搜索</el-button>
        </div>
      </div>
      <div v-else-if="results.length" class="step-body">
        <div class="result-card" v-for="(paper, idx) in results" :key="idx">
          <el-checkbox v-model="paper._checked" style="margin-right:8px" />
          <div class="result-main">
            <div class="result-title">{{ paper.title }}</div>
            <div class="result-meta">
              <span>{{ paper.authors }}</span><span class="meta-sep">·</span>
              <span>{{ paper.published?.substring(0, 4) }}</span><span class="meta-sep">·</span>
              <span style="font-family:monospace;color:#409eff">{{ paper.arxivId }}</span>
            </div>
            <div class="result-summary">{{ truncate(paper.summary, 250) }}</div>
            <div class="result-reason" v-if="paper.recommendReason">
              <span class="reason-icon">💡</span>{{ paper.recommendReason }}
            </div>
          </div>
        </div>
        <div class="step-actions">
          <el-button type="primary" @click="openImportDialog" :disabled="!checkedPapers.length">
            入库选中（{{ checkedPapers.length }}）
          </el-button>
          <el-button @click="step=2; results=[]">重新搜索</el-button>
        </div>
      </div>
      <div v-else class="step-body"><p style="color:#c0c4cc">未找到匹配论文。请尝试修改关键词。</p></div>
    </div>

    <!-- ====== Step 5-6: 扩展检索 ====== -->
    <div class="step" :class="{ active: step >= 4 }" v-if="step >= 4">
      <div class="step-header"><span class="step-num">4</span> 扩展检索</div>
      <div class="step-body">
        <div class="agent-msg">
          <p><strong>扩展策略建议：</strong></p>
          <ul>
            <li><strong>Cited by</strong> — 检索引用已入库论文的后续研究</li>
            <li><strong>Related articles</strong> — 检索 arXiv 上的相关工作</li>
            <li><strong>作者追踪</strong> — 追踪一作和通信作者的其他论文</li>
            <li>建议扩展深度：1-2 层</li>
          </ul>
        </div>
        <div class="confirm-actions">
          <div class="action-col">
            <el-button type="primary" @click="expandLoading ? cancelExpandWithHint() : doExpand()"
              :disabled="expandError">
              {{ expandLoading ? '取消扩展' : '执行扩展检索' }}
            </el-button>
            <span v-if="expandLoading" class="stage-text">{{ expandStatus }}</span>
            <span v-else-if="expandError" class="error-text">{{ expandError.message }}
              <el-button size="small" link type="primary" @click="retryExpand()">重试</el-button>
            </span>
          </div>
          <el-button @click="step = 3">跳过，回到结果</el-button>
        </div>

        <!-- 扩展结果 -->
        <div v-if="expandResults.length" style="margin-top:16px">
          <h4>扩展结果（{{ expandResults.length }} 篇）</h4>
          <div class="result-card" v-for="(paper, idx) in expandResults" :key="'e'+idx">
            <el-checkbox v-model="paper._checked" style="margin-right:8px" />
            <div class="result-main">
              <div class="result-title">{{ paper.title }}</div>
              <div class="result-meta">
                <span>{{ paper.authors }}</span><span class="meta-sep">·</span>
                <span>{{ paper.published?.substring(0, 4) }}</span>
              </div>
              <div class="result-summary">{{ truncate(paper.summary, 200) }}</div>
            </div>
          </div>
          <div class="step-actions">
            <el-button type="primary" @click="openImportDialog" :disabled="!checkedPapers.length">
              入库选中（{{ checkedPapers.length }}）
            </el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- ====== 入库对话框 ====== -->
    <el-dialog v-model="importDialogVisible" title="导入论文到文库" width="450px">
      <p style="font-size:12px;color:#909399;margin:0 0 12px">已选 {{ checkedPapers.length }} 篇论文</p>
      <el-form label-width="80px">
        <el-form-item label="目标文件夹">
          <div style="display:flex;gap:6px;width:100%">
            <el-tree-select v-model="importFolderId" :data="folders" :props="treeProps"
              check-strictly node-key="id" placeholder="暂不分类（可选）" clearable style="flex:1" />
            <el-button size="small" text type="primary" @click="recommendFolderForSearch"
              :loading="recommendingFolder" :disabled="!checkedPapers.length">Agent 推荐</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="doImport" :loading="importing">确认导入</el-button>
      </template>
    </el-dialog>

  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import api from '@/api'
import { useGlobalTask } from '@/composables/useGlobalTask.js'
import { ElMessage } from 'element-plus'

const router = useRouter()
const step = ref(1)
const userInput = ref('')
const workflowMode = ref(false)

// ===== 三个全局后台任务：切换页面不取消 =====
const {
  isLoading: extractLoading,
  statusText: extractStatus,
  error: extractError,
  run: runExtract,
  cancel: cancelExtract,
  retry: retryExtract
} = useGlobalTask('search-extract')
const {
  isLoading: searchLoading,
  statusText: searchStatus,
  error: searchError,
  run: runSearch,
  cancel: cancelSearch,
  retry: retrySearch
} = useGlobalTask('search-execute')
const {
  isLoading: expandLoading,
  statusText: expandStatus,
  error: expandError,
  run: runExpand,
  cancel: cancelExpand,
  retry: retryExpand
} = useGlobalTask('search-expand')

// ===== 页面状态持久化：切换路由后恢复 =====
const session = useGlobalTask('search-session').data
function restoreSession() {
  step.value = session.step || 1
  userInput.value = session.userInput || ''
  extraction.value = session.extraction || null
  results.value = session.results || []
  expandResults.value = session.expandResults || []
}
function saveSession() {
  session.step = step.value
  session.userInput = userInput.value
  session.extraction = extraction.value
  session.results = results.value
  session.expandResults = expandResults.value
}

function cancelExtractWithHint() {
  cancelExtract()
  ElMessage.info('已取消')
}
function cancelSearchWithHint() {
  cancelSearch()
  ElMessage.info('已取消')
}
function cancelExpandWithHint() {
  cancelExpand()
  ElMessage.info('已取消')
}

const extraction = ref(null)
const newKeyword = ref('')
const newPaperType = ref('')
const results = ref([])
const expandResults = ref([])

restoreSession()
watch(step, saveSession)
watch(userInput, saveSession)
watch(extraction, saveSession, { deep: true })
watch(results, saveSession, { deep: true })
watch(expandResults, saveSession, { deep: true })

const importing = ref(false)

const importDialogVisible = ref(false)
const importFolderId = ref(null)
const recommendingFolder = ref(false)
const folders = ref([])
const treeProps = { children: 'children', label: 'name' }

const checkedPapers = computed(() =>
  [...results.value, ...expandResults.value].filter(p => p._checked))

function addKeyword() {
  if (newKeyword.value.trim()) { extraction.value.keywords_en.push(newKeyword.value.trim()); newKeyword.value = '' }
}
function addPaperType() {
  if (newPaperType.value.trim()) { extraction.value.paper_type.push(newPaperType.value.trim()); newPaperType.value = '' }
}

function truncate(text, len) {
  if (!text) return ''
  return text.length > len ? text.slice(0, len) + '…' : text
}

// Step 1→2: Agent 提炼
async function startExtract() {
  if (workflowMode.value) {
    try {
      const res = await api.post('/research-automation/workflow/literature-survey', { query: userInput.value })
      ElMessage.success('已启动文献调研工作流')
      router.push({ path: '/tasks', query: { highlight: res.data.taskId } })
    } catch (e) {
      ElMessage.error('启动工作流失败：' + (e.response?.data?.message || e.message))
    }
    return
  }
  await runExtract(async ({ signal, setStage }) => {
    setStage('正在提炼检索要素…')
    const res = await api.post('/search/extract', { query: userInput.value }, { signal })
    extraction.value = res.data
    step.value = 2
  })
}

// Step 2→3: 确认并执行检索
async function confirmAndSearch() {
  await runSearch(async ({ signal, setStage }) => {
    setStage('正在检索论文…')
    step.value = 3
    results.value = []
    const res = await api.post('/search/execute', extraction.value, { signal })
    results.value = (res.data || []).map(p => ({ ...p, _checked: false }))
  })
}

// Step 3→4: 扩展检索触发
async function doExpand() {
  await runExpand(async ({ signal, setStage }) => {
    setStage('正在扩展检索…')
    // 使用选中论文的标题作为扩展关键词（非索引号）
    const titles = results.value.filter(p => p._checked).map(p => p.title).filter(Boolean)
    const res = await api.post('/search/expand', { queries: titles }, { signal })
    expandResults.value = (res.data.results || []).map(p => ({ ...p, _checked: false }))
    step.value = 4
  })
}

// 导入
function openImportDialog() {
  importDialogVisible.value = true
}

async function doImport() {
  importing.value = true
  try {
    // 批量导入——提交原始检索结果（后端负责转换入库）
    const payload = checkedPapers.value.map(p => ({
      title: p.title,
      authors: p.authors,
      summary: p.summary,
      published: p.published,
      arxivId: p.arxivId,
      pdfUrl: p.pdfUrl
    }))
    const res = await api.post('/search/import', { papers: payload, folderId: importFolderId.value || null })
    const r = res.data
    ElMessage.success(`已导入 ${r.imported} 篇论文${r.skipped > 0 ? `，跳过 ${r.skipped} 篇` : ''}`)
    importDialogVisible.value = false
  } catch (e) {
    ElMessage.error('导入失败：' + (e.response?.data?.message || e.message))
  } finally {
    importing.value = false
  }
}

/** Agent 推荐文件夹（基于第一篇选中论文的标题） */
async function recommendFolderForSearch() {
  const first = checkedPapers.value[0]
  if (!first?.title) return
  recommendingFolder.value = true
  try {
    const res = await api.post('/research-automation/folder-suggest', {
      title: first.title,
      abstractText: first.summary || ''
    })
    const data = res.data
    if (data.recommended) {
      importFolderId.value = data.recommended
      ElMessage.success(`已推荐${data.reason ? '：' + data.reason : ''}`)
    } else if (data.suggestNew) {
      ElMessage.info(`建议新建文件夹「${data.newName}」`)
    }
  } catch (e) {
    ElMessage.error('推荐失败')
  } finally {
    recommendingFolder.value = false
  }
}

onMounted(async () => {
  try {
    const r = await api.get('/folders')
    folders.value = r.data || []
  } catch (e) { /* 静默 */ }
})
</script>

<style scoped>
.search-page {
  box-sizing: border-box;
  padding: 25px 30px 44px;
  min-height: 100vh;
  max-width: 1440px;
  margin: 0 auto;
}
.search-heading { display:flex; align-items:center; justify-content:space-between; gap:24px; margin-bottom:18px; }
.search-heading h1 { margin:0; color:var(--ra-text); font-size:27px; line-height:1.2; letter-spacing:-.65px; }
.search-heading p { margin:7px 0 0; color:var(--ra-text-tertiary); font-size:12px; }
.search-progress { display:grid; grid-template-columns:repeat(4, 1fr); padding:10px 12px; margin-bottom:14px; border:1px solid var(--ra-border-light); border-radius:14px; background:var(--ra-panel-bg); box-shadow:0 4px 18px rgba(0,0,0,.03); }
.progress-step { position:relative; display:flex; min-height:40px; align-items:center; justify-content:center; gap:9px; color:var(--ra-text-tertiary); font-size:12px; }
.progress-step:not(:last-child)::after { content:''; position:absolute; right:-8px; width:16px; height:1px; background:var(--ra-border-light); }
.progress-step.active { border-radius:10px; background:var(--ra-active-bg); color:var(--ra-active-text); font-weight:600; }
.progress-step.done { color:var(--ra-text-secondary); }
.progress-dot { display:grid; width:21px; height:21px; flex:0 0 21px; place-items:center; border-radius:50%; background:var(--ra-hover-bg); font-size:10px; }
.progress-step.active .progress-dot { background:var(--ra-link); color:#fff; }
.progress-step.done .progress-dot { background:#e9f7ef; color:#2ca66f; }
.search-context { display:flex; align-items:center; gap:16px; padding:14px 16px; margin-bottom:14px; border:1px solid var(--ra-border-light); border-radius:13px; background:var(--ra-panel-bg); }
.search-context > div:first-child { display:flex; min-width:220px; flex-direction:column; gap:4px; }
.search-context small { color:var(--ra-text-tertiary); font-size:10px; }
.search-context strong { color:var(--ra-text); font-size:13px; }
.context-tags { display:flex; min-width:0; flex:1; flex-wrap:wrap; gap:5px; }

/* 步骤 */
.step {
  margin-bottom: 22px;
  padding:18px;
  border:1px solid var(--ra-border-light);
  border-radius:14px;
  background:var(--ra-panel-bg);
  box-shadow:0 5px 20px rgba(0,0,0,.03);
  opacity: 0.5;
  transition: opacity 0.3s;
}
.step.active { opacity: 1; }
.step.done { opacity: 0.6; }

.step-header {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 12px;
  color: var(--ra-text);
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.step-num {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px; height: 24px;
  background: var(--ra-link); color: #fff;
  border-radius: 50%;
  font-size: 13px;
  margin-right: 8px;
}
.step.done .step-num { background: #67c23a; }

.step-body {
  padding-left: 33px;
}

.input-row {
  display: flex;
  gap: 12px;
}

.action-col {
  display: flex;
  align-items: center;
  gap: 8px;
}
.stage-text { font-size: 13px; color: var(--ra-link); }
.error-text { font-size: 13px; color: #f56c6c; }

/* Agent 确认 */
.agent-confirm {
  background: var(--ra-bg);
  border-radius: 11px;
  padding: 16px;
}
.agent-msg { margin-bottom: 16px; }
.agent-msg p { margin: 0 0 8px; font-size: 14px; }

.extraction-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.ext-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.ext-label {
  color: #909399;
  min-width: 80px;
  flex-shrink: 0;
}

.confirm-actions {
  display: flex;
  gap: 8px;
}

/* 搜索结果 */
.result-count { font-weight: 400; font-size: 13px; color: #909399; }

.result-card {
  display: flex;
  align-items: flex-start;
  padding: 14px 0;
  border-bottom: 1px solid var(--ra-border-light);
}

.result-main { flex: 1; min-width: 0; }

.result-title {
  font-size: 14px; font-weight: 600;
  color: var(--ra-text); margin-bottom: 4px; line-height: 1.4;
}
.result-meta {
  font-size: 12px; color: var(--ra-text-tertiary);
  display: flex; align-items: center; flex-wrap: wrap; gap: 2px;
}
.meta-sep { margin: 0 4px; color: #dcdfe6; }
.result-summary {
  font-size: 13px; color: var(--ra-text-secondary); line-height: 1.5; margin: 5px 0;
}
.result-reason {
  font-size: 12px; color: #67c23a; font-style: italic;
}
.reason-icon { margin-right: 4px; }

.step-actions {
  display: flex; gap: 8px; margin-top: 16px;
}

.step-body ul {
  margin: 4px 0 0; padding-left: 18px; font-size: 13px; line-height: 1.8;
}

@media (max-width: 760px) {
  .search-page { padding:20px 14px 36px; }
  .search-progress { grid-template-columns:1fr 1fr; }
  .progress-step:not(:last-child)::after { display:none; }
  .search-context { align-items:flex-start; flex-direction:column; }
  .input-row, .extraction-grid { display:flex; flex-direction:column; }
}
</style>
