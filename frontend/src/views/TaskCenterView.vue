<template>
  <div class="task-center-page">
    <div class="task-toolbar">
      <h3>任务中心</h3>
      <div class="task-actions">
        <el-tag v-if="polling" type="info" effect="plain" size="small">轮询中…</el-tag>
        <el-button :icon="Refresh" size="small" @click="loadTasks">刷新</el-button>
      </div>
    </div>

    <el-alert
      v-if="showIntro"
      title="任务中心说明"
      type="info"
      :closable="true"
      @close="onIntroClose"
      description="这里展示由 AI Agent / 工作流自动创建的后台任务（如文献综述、Gap 分析、论文导入、AI 精读等），不需要手动新建。你可以查看进度、取消运行中任务、重试失败任务或确认需要人工决策的步骤。已完成 / 失败 / 已取消的任务可手动删除。"
      show-icon
      style="margin-bottom: 16px"
    />

    <el-table :data="tasks" v-loading="loading" style="width: 100%" row-key="taskId" empty-text="暂无后台任务">
      <el-table-column prop="title" label="任务" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="task-title">{{ row.title || '未命名任务' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="140">
        <template #default="{ row }">
          <el-tag size="small" :type="typeMeta[row.workflowType ? 'workflow' : 'plain'].type">
            {{ typeMeta[row.workflowType ? 'workflow' : 'plain'].label }}
          </el-tag>
          <el-tag v-if="row.workflowType" size="small" style="margin-left:4px">{{ row.workflowType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="statusMeta[row.status]?.type" size="small" effect="dark">
            {{ statusMeta[row.status]?.label || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="stageText" label="阶段" min-width="180" show-overflow-tooltip />
      <el-table-column prop="createdAt" label="创建时间" width="160">
        <template #default="{ row }">
          {{ formatTime(row.createdAt) }}
        </template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="160">
        <template #default="{ row }">
          {{ formatTime(row.updatedAt) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openDetail(row)">查看</el-button>
          <el-button
            v-if="row.status === 'PENDING_USER'"
            size="small"
            link
            type="success"
            @click="openDetail(row)"
          >确认</el-button>
          <el-button
            v-if="!isTerminal(row.status)"
            size="small"
            link
            type="warning"
            @click="cancelTask(row.taskId)"
          >取消</el-button>
          <el-button
            v-if="row.workflowType && ['FAILED', 'CANCELLED', 'EXPIRED'].includes(row.status)"
            size="small"
            link
            type="success"
            @click="retryTask(row.taskId)"
          >重试</el-button>
          <el-button
            v-if="isTerminal(row.status)"
            size="small"
            link
            type="danger"
            @click="deleteTask(row.taskId)"
          >删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && tasks.length === 0" description="暂无后台任务" />

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" :title="detailTitle" size="480px" destroy-on-close>
      <div v-if="selected" class="task-detail">
        <div class="detail-row">
          <span class="detail-label">任务 ID</span>
          <span class="detail-value mono">{{ selected.taskId }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">状态</span>
          <el-tag :type="statusMeta[selected.status]?.type" size="small" effect="dark">
            {{ statusMeta[selected.status]?.label || selected.status }}
          </el-tag>
        </div>
        <div class="detail-row" v-if="selected.stageText">
          <span class="detail-label">当前阶段</span>
          <span class="detail-value">{{ selected.stageText }}</span>
        </div>
        <div class="detail-row" v-if="selected.error">
          <span class="detail-label">错误</span>
          <span class="detail-value error">{{ selected.error }}</span>
        </div>
        <div class="detail-row" v-if="selected.workflowType">
          <span class="detail-label">工作流</span>
          <span class="detail-value">{{ selected.workflowType }}</span>
        </div>

        <!-- 工作流步骤 -->
        <div v-if="selected.steps?.length" class="steps-section">
          <div class="detail-label" style="margin-bottom:8px">执行步骤</div>
          <el-steps direction="vertical" :active="activeStepIndex(selected)" finish-status="success">
            <el-step v-for="step in selected.steps" :key="step.stepIndex" :title="step.stepName">
              <template #description>
                <div class="step-desc">
                  <el-tag size="small" :type="statusMeta[step.status]?.type">
                    {{ statusMeta[step.status]?.label || step.status }}
                  </el-tag>
                  <span v-if="step.error" class="error">{{ step.error }}</span>
                </div>
              </template>
            </el-step>
          </el-steps>
        </div>

        <!-- 结果预览 -->
        <div v-if="selected.result" class="result-section">
          <div class="detail-label" style="margin-bottom:8px">结果</div>
          <pre class="result-pre">{{ JSON.stringify(selected.result, null, 2) }}</pre>
        </div>

        <!-- 人机确认：文献调研 -->
        <div v-if="pendingSelection" class="confirm-section">
          <div class="detail-label" style="margin-bottom:8px">请选择要入库的论文</div>
          <el-tree-select
            v-model="confirmFolderId"
            :data="folders"
            :props="treeProps"
            check-strictly
            node-key="id"
            placeholder="目标文件夹"
            clearable
            size="small"
            style="width:100%;margin-bottom:12px"
          />
          <el-checkbox-group v-model="selectedCandidateKeys" style="display:flex;flex-direction:column;gap:8px">
            <div v-for="(c, idx) in pendingSelection.candidates" :key="idx" class="candidate-card">
              <el-checkbox :label="candidateKey(c)">
                <div class="candidate-title">{{ c.title || '未命名' }}</div>
                <div class="candidate-meta">{{ c.authors }} · {{ c.year }} · {{ c.arxivId || c.doi || '' }}</div>
                <div v-if="c.recommendReason" class="candidate-reason">{{ c.recommendReason }}</div>
              </el-checkbox>
            </div>
          </el-checkbox-group>
          <div style="margin-top:12px;display:flex;gap:8px">
            <el-button size="small" @click="checkAll(true)">全选</el-button>
            <el-button size="small" @click="checkAll(false)">取消全选</el-button>
            <el-button type="primary" size="small" :loading="confirming" :disabled="selectedCandidateKeys.length === 0" @click="confirmTask">确认入库</el-button>
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import api from '@/api'
import { ElMessage, ElMessageBox } from 'element-plus'

const route = useRoute()

const tasks = ref([])
const loading = ref(false)
const polling = ref(false)
let pollTimer = null
const showIntro = ref(localStorage.getItem('hideTaskCenterIntro') !== 'true')

const detailVisible = ref(false)
const selected = ref(null)
const folders = ref([])
const confirmCandidates = ref([])
const confirmFolderId = ref(null)
const confirming = ref(false)
const treeProps = { children: 'children', label: 'name' }

const statusMeta = {
  PENDING: { label: '排队中', type: 'info' },
  PROCESSING: { label: '运行中', type: 'primary' },
  RETRY_WAIT: { label: '等待重试', type: 'warning' },
  COMPLETED: { label: '已完成', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  CANCELLED: { label: '已取消', type: 'warning' },
  PENDING_USER: { label: '待确认', type: 'warning' },
  EXPIRED: { label: '已过期', type: 'info' },
  DEAD_LETTER: { label: '超过重试上限', type: 'danger' }
}

const typeMeta = {
  plain: { label: '普通任务', type: 'info' },
  workflow: { label: '工作流', type: 'primary' }
}

const detailTitle = computed(() => selected.value ? (selected.value.title || '任务详情') : '任务详情')
const pendingSelection = computed(() => selected.value?.result?.pendingSelection || null)
const selectedCandidateKeys = computed({
  get() {
    return confirmCandidates.value.map(c => candidateKey(c))
  },
  set(keys) {
    const all = pendingSelection.value?.candidates || []
    confirmCandidates.value = all.filter(c => keys.includes(candidateKey(c)))
  }
})

function candidateKey(c) {
  return (c.arxivId || c.doi || c.title || '') + '|' + (c.year || '')
}

function isTerminal(status) {
  return ['COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED', 'DEAD_LETTER'].includes(status)
}

function onIntroClose() {
  localStorage.setItem('hideTaskCenterIntro', 'true')
}

function formatTime(value) {
  if (!value) return '-'
  const d = new Date(value)
  if (isNaN(d.getTime())) return value
  return d.toLocaleString('zh-CN', { hour12: false })
}

function activeStepIndex(task) {
  if (!task.steps) return 0
  let idx = task.steps.findIndex(s => s.status !== 'COMPLETED')
  if (idx === -1) idx = task.steps.length
  return idx
}

async function loadTasks() {
  loading.value = true
  try {
    const r = await api.get('/agent/tasks', { params: { limit: 100 } })
    tasks.value = r.data || []
    schedulePoll()
  } catch (e) {
    ElMessage.error('加载任务列表失败')
  } finally {
    loading.value = false
  }
}

function schedulePoll() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  const hasRunning = tasks.value.some(t => !isTerminal(t.status))
  polling.value = hasRunning
  if (hasRunning) {
    pollTimer = setTimeout(loadTasks, 3000)
  }
}

async function cancelTask(taskId) {
  try {
    await ElMessageBox.confirm('确定取消该任务吗？', '提示', { type: 'warning' })
    await api.post(`/agent/task/${taskId}/cancel`)
    ElMessage.success('已取消')
    loadTasks()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('取消失败')
    }
  }
}

async function retryTask(taskId) {
  try {
    const r = await api.post(`/agent/workflow/${taskId}/retry`)
    ElMessage.success('已重新提交，新任务 ID: ' + r.data.taskId)
    loadTasks()
  } catch (e) {
    ElMessage.error('重试失败: ' + (e.response?.data?.message || e.message))
  }
}

async function deleteTask(taskId) {
  try {
    await ElMessageBox.confirm('删除后不可恢复，确定删除该任务记录？', '确认删除', { type: 'warning' })
    await api.delete(`/agent/task/${taskId}`)
    ElMessage.success('已删除')
    loadTasks()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败: ' + (e.response?.data?.message || e.message))
    }
  }
}

async function loadFolders() {
  try {
    const r = await api.get('/folders')
    folders.value = r.data || []
  } catch (e) { /* 静默 */ }
}

function checkAll(checked) {
  const all = pendingSelection.value?.candidates || []
  confirmCandidates.value = checked ? [...all] : []
}

async function confirmTask() {
  if (!selected.value) return
  confirming.value = true
  try {
    await api.post(`/agent/workflow/${selected.value.taskId}/confirm`, {
      selected: confirmCandidates.value,
      folderId: confirmFolderId.value || null
    })
    ElMessage.success('已确认，继续批量入库')
    detailVisible.value = false
    loadTasks()
  } catch (e) {
    ElMessage.error('确认失败：' + (e.response?.data?.message || e.message))
  } finally {
    confirming.value = false
  }
}

function openDetail(row) {
  selected.value = row
  const ps = row.result?.pendingSelection
  if (ps) {
    confirmCandidates.value = [...(ps.candidates || [])]
    confirmFolderId.value = ps.defaultFolderId || null
  } else {
    confirmCandidates.value = []
    confirmFolderId.value = null
  }
  detailVisible.value = true
}

onMounted(async () => {
  await loadFolders()
  await loadTasks()
  const highlight = route.query?.highlight
  if (highlight) {
    const row = tasks.value.find(t => t.taskId === highlight)
    if (row) openDetail(row)
  }
})
onUnmounted(() => {
  if (pollTimer) clearTimeout(pollTimer)
})
</script>

<style scoped>
.task-center-page {
  padding: 20px;
}
.task-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.task-toolbar h3 {
  margin: 0;
}
.task-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.task-title {
  font-weight: 500;
}
.task-detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.detail-row {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.detail-label {
  color: var(--ra-text-tertiary);
  font-size: 13px;
  width: 72px;
  flex-shrink: 0;
}
.detail-value {
  flex: 1;
  word-break: break-all;
}
.detail-value.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}
.detail-value.error,
.step-desc .error {
  color: #f56c6c;
}
.steps-section,
.result-section {
  margin-top: 8px;
}
.step-desc {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 4px;
}
.result-pre {
  background: var(--ra-bg);
  padding: 12px;
  border-radius: 6px;
  font-size: 12px;
  max-height: 320px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
.confirm-section {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--ra-border-light);
}
.candidate-card {
  padding: 8px 10px;
  border: 1px solid var(--ra-border);
  border-radius: 6px;
  background: var(--ra-panel-bg);
}
.candidate-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ra-text);
}
.candidate-meta {
  font-size: 12px;
  color: var(--ra-text-tertiary);
  margin-top: 2px;
}
.candidate-reason {
  font-size: 12px;
  color: #67c23a;
  margin-top: 4px;
}
</style>
