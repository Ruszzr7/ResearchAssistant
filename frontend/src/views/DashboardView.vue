<template>
  <div class="dashboard-view">
    <div class="dashboard-header">
      <div>
        <span class="dashboard-eyebrow">科研工作台</span>
        <h2 class="dashboard-title">Research Assistant</h2>
        <p class="dashboard-subtitle">一眼掌握论文、分析与任务</p>
      </div>
      <el-button class="enter-library-button" type="primary" @click="$router.push('/library')">
        进入文库
      </el-button>
    </div>

    <el-skeleton v-if="loading" :rows="8" animated />

    <el-result
      v-else-if="error"
      icon="error"
      title="看板加载失败"
      :sub-title="error"
    >
      <template #extra>
        <el-button type="primary" @click="load">重试</el-button>
      </template>
    </el-result>

    <template v-else-if="data">
      <!-- 论文统计 -->
      <div class="stat-row">
        <div v-for="s in paperStatList" :key="s.key" class="stat-cell">
          <el-card shadow="never" class="stat-card" :body-style="{ padding: '16px' }">
            <div class="stat-label">{{ s.label }}</div>
            <el-statistic :value="s.value" />
          </el-card>
        </div>
      </div>

      <div class="panel-row">
        <!-- 文件夹分布 -->
        <div class="panel-cell">
          <el-card shadow="never" class="panel-card">
            <template #header>
              <div class="panel-header">
                <span>文件夹分布</span>
                <el-button text size="small" @click="$router.push('/library')">查看</el-button>
              </div>
            </template>
            <div v-if="data.folderBacklog.length" class="folder-list">
              <div v-for="f in folderBacklogWithPercent" :key="f.id" class="folder-item">
                <div class="folder-meta">
                  <span class="folder-name" :title="f.name">{{ f.name }}</span>
                  <span class="folder-count">{{ f.paperCount }}</span>
                </div>
                <el-progress :percentage="f.percent" :stroke-width="6" :show-text="false" />
              </div>
            </div>
            <el-empty v-else description="暂无文件夹数据" :image-size="60" />
          </el-card>
        </div>

        <!-- 任务状态 -->
        <div class="panel-cell">
          <el-card shadow="never" class="panel-card">
            <template #header>
              <div class="panel-header">
                <span>任务中心</span>
                <el-button text size="small" @click="$router.push('/tasks')">查看</el-button>
              </div>
            </template>
            <div class="task-stats">
              <div class="task-chip processing">处理中 {{ processingTaskCount }}</div>
              <div class="task-chip completed">已完成 {{ data.taskStats.completed || 0 }}</div>
              <div class="task-chip failed">失败 {{ failedTaskCount }}</div>
              <div v-if="data.taskStats.historicalFailed" class="task-chip historical-failed">
                历史失败 {{ data.taskStats.historicalFailed }}
              </div>
            </div>
            <div class="recent-tasks">
              <div class="recent-title">最近任务</div>
              <div v-for="t in data.taskStats.recent" :key="t.id" class="recent-task">
                <el-tag size="small" :type="taskTagType(t.status)">{{ taskStatusText(t.status) }}</el-tag>
                <span class="task-title" :title="t.title">{{ t.title || '未命名任务' }}</span>
                <span class="task-time">{{ formatTime(t.createdAt) }}</span>
              </div>
              <el-empty v-if="!data.taskStats.recent.length" description="暂无任务" :image-size="60" />
            </div>
          </el-card>
        </div>
      </div>

    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getDashboard } from '@/api/dashboard'
import { taskStatusMeta } from '@/utils/taskStatus.js'

const loading = ref(true)
const data = ref(null)
const error = ref('')

const paperStatList = computed(() => {
  const ps = data.value?.paperStats || {}
  return [
    { key: 'total', label: '论文总数', value: ps.total || 0 },
    { key: 'unread', label: '未读', value: ps.unread || 0 },
    { key: 'reading', label: '正读', value: ps.reading || 0 },
    { key: 'read', label: '已读', value: ps.read || 0 },
    { key: 'uncategorized', label: '未分类', value: ps.uncategorized || 0 },
    { key: 'pinned', label: '置顶', value: ps.pinned || 0 },
    { key: 'thisMonth', label: '本月新增', value: ps.thisMonth || 0 },
  ]
})

const folderBacklogWithPercent = computed(() => {
  const list = data.value?.folderBacklog || []
  const max = Math.max(...list.map(f => f.paperCount), 1)
  return list.map(f => ({ ...f, percent: Math.round((f.paperCount / max) * 100) }))
})
const processingTaskCount = computed(() => {
  const stats = data.value?.taskStats || {}
  return Number(stats.processing || 0) + Number(stats.pending || 0) + Number(stats.retryWait || 0)
})
const failedTaskCount = computed(() => {
  const stats = data.value?.taskStats || {}
  return Number(stats.failed || 0) + Number(stats.pendingUser || 0)
    + Number(stats.deadLetter || 0) + Number(stats.expired || 0)
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await getDashboard()
  } catch (e) {
    error.value = e.response?.data?.message || e.message || '加载看板失败'
    ElMessage.error(error.value)
  } finally {
    loading.value = false
  }
}

function formatTime(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function taskStatusText(status) {
  return taskStatusMeta(status).label
}

function taskTagType(status) {
  return taskStatusMeta(status).type
}

onMounted(load)
</script>

<style scoped>
.dashboard-view {
  box-sizing: border-box;
  padding: 30px 34px 44px;
  max-width: 1500px;
  margin: 0 auto;
}
.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}
.enter-library-button { align-self:center; margin-top:18px; font-size:18px; }
.dashboard-eyebrow { display: block; margin-bottom: 7px; color: var(--ra-text-tertiary); font-size: 11px; }
.dashboard-title {
  margin: 0;
  font-size: 27px;
  line-height: 1.12;
  letter-spacing: -.7px;
  color: var(--ra-text);
}
.dashboard-subtitle {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--ra-text-secondary);
}
.stat-row { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 12px; margin-bottom: 14px; }
.stat-cell { min-width: 0; }
.stat-card {
  height: 92px;
  border: 1px solid var(--ra-border-light);
  border-radius: 14px;
  background: var(--ra-panel-bg);
  box-shadow: 0 5px 20px rgba(0, 0, 0, .035);
}
.stat-card :deep(.el-card__body) { box-sizing:border-box; height:100%; overflow:hidden !important; }
.stat-label {
  font-size: 12px;
  color: var(--ra-text-secondary);
  margin-bottom: 8px;
}
.stat-card :deep(.el-statistic__number) { color: var(--ra-text); font-size: 25px; font-weight: 650; letter-spacing: -.5px; }
.panel-row { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 14px; margin-bottom: 16px; }
.panel-cell { min-width: 0; }
.panel-card {
  min-height: 370px;
  border: 1px solid var(--ra-border-light);
  border-radius: 15px;
  background: var(--ra-panel-bg);
  box-shadow: 0 5px 22px rgba(0, 0, 0, .035);
}
.panel-card :deep(.el-card__header) { padding: 16px 18px; border-bottom: 1px solid var(--ra-border-light); }
.panel-card :deep(.el-card__body) { padding: 17px 18px; }
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  color: var(--ra-text);
}
.folder-list {
  display: flex;
  flex-direction: column;
  gap: 15px;
}
.folder-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.folder-meta {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: var(--ra-text);
}
.folder-item :deep(.el-progress-bar__outer) { background: var(--ra-bg); }
.folder-item :deep(.el-progress-bar__inner) { background: linear-gradient(90deg, #168cff, #73baff); }
.folder-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 70%;
}
.folder-count {
  color: var(--ra-text-secondary);
}
.task-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}
.task-chip {
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 999px;
  background: var(--ra-bg);
  color: var(--ra-text);
  border: 1px solid var(--ra-border);
}
.task-chip.pending { color: #909399; }
.task-chip.processing { color: var(--el-color-primary); }
.task-chip.completed { color: #67c23a; }
.task-chip.failed { color: #f56c6c; }
.task-chip.historical-failed { color: #909399; }
.task-chip.cancelled { color: #909399; }
.recent-tasks {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.recent-title {
  font-size: 12px;
  color: var(--ra-text-secondary);
  margin-bottom: 4px;
}
.recent-task {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
  padding-top: 3px;
  border-top: 1px solid var(--ra-border-light);
  font-size: 13px;
}
.task-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ra-text);
}
.task-time {
  font-size: 12px;
  color: var(--ra-text-secondary);
  white-space: nowrap;
}

@media (max-width: 1180px) {
  .stat-row { grid-template-columns: repeat(4, minmax(0, 1fr)); }
}
@media (max-width: 800px) {
  .dashboard-view { padding: 22px 18px 36px; }
  .stat-row { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .panel-row { grid-template-columns: 1fr; }
}
</style>
