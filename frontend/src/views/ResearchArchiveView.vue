<template>
  <div class="research-archive-page">
    <header class="archive-header">
      <div>
        <h2>研究档案</h2>
        <p>按论文保留对话、分析、对比与证据记录。</p>
      </div>
      <div class="archive-actions">
        <el-input
          v-model="keyword"
          clearable
          placeholder="搜索档案"
          @keyup.enter="loadSessions"
          @clear="loadSessions"
        />
        <el-button :icon="Refresh" :loading="loading" @click="loadSessions">刷新</el-button>
      </div>
    </header>

    <el-tabs v-model="activeTab" @tab-change="loadSessions">
      <el-tab-pane label="最近研究" name="active" />
      <el-tab-pane label="已归档" name="archived" />
    </el-tabs>

    <div v-loading="loading" class="archive-grid">
      <article
        v-for="session in sessions"
        :key="session.id"
        class="archive-card"
        tabindex="0"
        @click="openDetail(session)"
        @keydown.enter="openDetail(session)"
      >
        <div class="archive-card__heading">
          <el-tag size="small" effect="plain">{{ session.sessionType === 'MULTI' ? '多篇研究' : '单篇研究' }}</el-tag>
          <span>{{ formatTime(session.lastActivityAt) }}</span>
        </div>
        <h3>{{ session.title }}</h3>
        <p>{{ paperNames(session) }}</p>
        <footer>
          <span>{{ session.messageCount }} 条对话</span>
          <span>{{ session.runCount }} 次分析</span>
          <span>第 {{ session.lastPage || 1 }} 页</span>
        </footer>
      </article>
    </div>

    <el-empty v-if="!loading && !sessions.length" :description="activeTab === 'active' ? '暂无研究档案' : '暂无已归档档案'" />

    <el-drawer v-model="detailVisible" :title="detail?.session?.title || '研究档案'" size="520px" destroy-on-close>
      <div v-if="detail" class="archive-detail">
        <section class="detail-summary">
          <div>
            <span>关联论文</span>
            <button
              v-for="paper in detail.session.papers"
              :key="paper.id"
              type="button"
              @click="openResearch(detail.session, paper.id)"
            >{{ paper.title || `论文 #${paper.id}` }}</button>
          </div>
          <div class="detail-summary__stats">
            <span>{{ detail.messages.length }} 条对话</span>
            <span>{{ detail.runs.length }} 次分析</span>
          </div>
        </section>

        <el-tabs v-model="detailTab">
          <el-tab-pane label="对话" name="messages">
            <div v-if="detail.messages.length" class="message-list">
              <article
                v-for="message in detail.messages"
                :key="message.id"
                :class="['archive-message', message.role === 'USER' ? 'is-user' : 'is-assistant']"
              >
                <small>{{ message.role === 'USER' ? '我' : '论文助手' }} · {{ formatTime(message.createdAt) }}</small>
                <p>{{ message.content }}</p>
                <button v-if="message.selectionAnchor?.page" type="button" @click="openMessageEvidence(message)">
                  第 {{ message.selectionAnchor.page }} 页
                </button>
              </article>
            </div>
            <el-empty v-else description="暂无选区对话" :image-size="64" />
          </el-tab-pane>
          <el-tab-pane label="分析记录" name="runs">
            <div v-if="detail.runs.length" class="run-list">
              <article v-for="run in detail.runs" :key="run.runId">
                <div><b>{{ workflowLabel(run.plan?.workflow || run.invocation?.workflow) }}</b><el-tag size="small" :type="runStatusType(run.status)">{{ runStatusLabel(run.status) }}</el-tag></div>
                <small>{{ formatTime(run.completedAt || run.createdAt) }}</small>
                <p v-if="run.result?.answer">{{ run.result.answer }}</p>
              </article>
            </div>
            <el-empty v-else description="暂无分析记录" :image-size="64" />
          </el-tab-pane>
        </el-tabs>
      </div>

      <template #footer>
        <div v-if="detail?.session" class="detail-footer">
          <el-button @click="renameSession">重命名</el-button>
          <el-button @click="toggleArchive">{{ detail.session.archived ? '移出归档' : '归档' }}</el-button>
          <el-button type="danger" plain @click="removeSession">删除</el-button>
          <el-button type="primary" @click="openResearch(detail.session)">继续研究</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteResearchSession,
  getResearchSession,
  listResearchSessions,
  updateResearchSession,
} from '@/api/researchArchive.js'

defineOptions({ name: 'ResearchArchiveView' })

const router = useRouter()
const sessions = ref([])
const loading = ref(false)
const keyword = ref('')
const activeTab = ref('active')
const detailVisible = ref(false)
const detail = ref(null)
const detailTab = ref('messages')

onMounted(loadSessions)

async function loadSessions() {
  loading.value = true
  try {
    sessions.value = await listResearchSessions({
      archived: activeTab.value === 'archived',
      keyword: keyword.value.trim() || undefined,
      limit: 100,
    })
  } catch (reason) {
    ElMessage.error(reason?.response?.data?.message || reason?.message || '研究档案加载失败')
  } finally {
    loading.value = false
  }
}

async function openDetail(session) {
  detailVisible.value = true
  detailTab.value = session.messageCount ? 'messages' : 'runs'
  try { detail.value = await getResearchSession(session.id) }
  catch (reason) {
    detailVisible.value = false
    ElMessage.error(reason?.response?.data?.message || reason?.message || '档案详情加载失败')
  }
}

function openResearch(session, paperId = session.primaryPaperId) {
  const ids = (session.papers || []).map(item => item.id).filter(id => id !== Number(paperId))
  router.push({
    path: `/research/${paperId}`,
    query: {
      session: String(session.id),
      page: String(session.lastPage || 1),
      mode: session.mode || 'analysis',
      ...(ids.length ? { paperIds: ids.join(',') } : {}),
    },
  })
}

function openMessageEvidence(message) {
  const session = detail.value?.session
  const paperId = message.selectionAnchor?.paperId || session?.primaryPaperId
  if (!session || !paperId) return
  router.push({
    path: `/research/${paperId}`,
    query: {
      session: String(session.id),
      page: String(message.selectionAnchor.page || session.lastPage || 1),
      mode: 'selection',
    },
  })
}

async function renameSession() {
  const session = detail.value?.session
  if (!session) return
  try {
    const { value } = await ElMessageBox.prompt('输入新的档案名称', '重命名', {
      inputValue: session.title,
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
    })
    const updated = await updateResearchSession(session.id, { title: value.trim() })
    detail.value.session = updated
    await loadSessions()
  } catch (reason) {
    if (reason !== 'cancel' && reason !== 'close') ElMessage.error('重命名失败')
  }
}

async function toggleArchive() {
  const session = detail.value?.session
  if (!session) return
  try {
    await updateResearchSession(session.id, { archived: !session.archived })
    detailVisible.value = false
    detail.value = null
    await loadSessions()
    ElMessage.success(session.archived ? '已移出归档' : '已归档')
  } catch { ElMessage.error('操作失败') }
}

async function removeSession() {
  const session = detail.value?.session
  if (!session) return
  try {
    await ElMessageBox.confirm('删除档案不会删除论文，但会移除其中的对话与分析记录。', '删除研究档案', { type: 'warning' })
    await deleteResearchSession(session.id)
    detailVisible.value = false
    detail.value = null
    await loadSessions()
    ElMessage.success('已删除')
  } catch (reason) {
    if (reason !== 'cancel' && reason !== 'close') ElMessage.error('删除失败')
  }
}

function paperNames(session) {
  const names = (session.papers || []).map(item => item.title || `论文 #${item.id}`)
  return names.join(' · ') || '关联论文已删除'
}

function formatTime(value) {
  if (!value) return ''
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function workflowLabel(workflow) {
  return {
    SELECTION_QA: '选区问答', PAPER_ANALYSIS: '全文分析', PAPER_IMPROVEMENT: '论文改进空间',
    PAPER_COMPARISON: '跨论文对比', RESEARCH_GAP: '领域研究空白', ANNOTATION_SUGGESTION: '批注建议',
  }[workflow] || '论文分析'
}

function runStatusLabel(status) {
  return { COMPLETED: '已完成', FAILED: '失败', RUNNING: '运行中', QUEUED: '排队中', PLANNED: '已规划', CANCELLED: '已取消' }[status] || status
}

function runStatusType(status) {
  if (status === 'COMPLETED') return 'success'
  if (['FAILED', 'CANCELLED'].includes(status)) return 'danger'
  return 'info'
}
</script>

<style scoped>
.research-archive-page { min-height: 100%; padding: 20px; box-sizing: border-box; color: var(--ra-text); }
.archive-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 12px; }
.archive-header h2 { margin: 0 0 6px; font-size: 22px; }
.archive-header p { margin: 0; color: var(--ra-text-tertiary); font-size: 12px; }
.archive-actions { display: flex; gap: 8px; width: 360px; }
.archive-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; min-height: 120px; }
.archive-card { min-width: 0; padding: 14px; border: 1px solid var(--ra-border); border-radius: 9px; background: var(--ra-panel-bg); cursor: pointer; transition: border-color .15s, transform .15s; }
.archive-card:hover, .archive-card:focus-visible { border-color: var(--ra-link); outline: none; transform: translateY(-1px); }
.archive-card__heading, .archive-card footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: var(--ra-text-tertiary); font-size: 10px; }
.archive-card h3 { overflow: hidden; margin: 12px 0 7px; font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.archive-card p { overflow: hidden; height: 34px; margin: 0 0 13px; color: var(--ra-text-secondary); font-size: 11px; line-height: 1.5; }
.archive-card footer { justify-content: flex-start; gap: 14px; }
.archive-detail { color: var(--ra-text); }
.detail-summary { margin-bottom: 8px; padding: 10px; border: 1px solid var(--ra-border); border-radius: 8px; }
.detail-summary > div:first-child { display: flex; flex-direction: column; gap: 5px; }
.detail-summary span { color: var(--ra-text-tertiary); font-size: 10px; }
.detail-summary button { overflow: hidden; padding: 0; border: 0; color: var(--ra-link); background: none; text-align: left; text-overflow: ellipsis; white-space: nowrap; cursor: pointer; }
.detail-summary__stats { display: flex; gap: 12px; margin-top: 10px; }
.message-list, .run-list { display: flex; flex-direction: column; gap: 9px; }
.archive-message { max-width: 92%; padding: 10px; border: 1px solid var(--ra-border); border-radius: 8px; }
.archive-message.is-user { align-self: flex-end; border-color: color-mix(in srgb, var(--ra-link) 35%, var(--ra-border)); background: color-mix(in srgb, var(--ra-link) 7%, var(--ra-panel-bg)); }
.archive-message small, .run-list small { color: var(--ra-text-tertiary); font-size: 9px; }
.archive-message p { margin: 5px 0; font-size: 11px; line-height: 1.55; white-space: pre-wrap; }
.archive-message button { padding: 0; border: 0; color: var(--ra-link); background: none; font-size: 10px; cursor: pointer; }
.run-list article { padding: 10px; border: 1px solid var(--ra-border); border-radius: 8px; }
.run-list article > div { display: flex; align-items: center; justify-content: space-between; }
.run-list p { display: -webkit-box; overflow: hidden; margin: 7px 0 0; color: var(--ra-text-secondary); font-size: 11px; line-height: 1.5; -webkit-box-orient: vertical; -webkit-line-clamp: 4; }
.detail-footer { display: flex; justify-content: flex-end; gap: 8px; }
@media (max-width: 760px) { .archive-header { flex-direction: column; } .archive-actions { width: 100%; } }
</style>
