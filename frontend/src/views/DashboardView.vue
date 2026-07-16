<template>
  <div class="dashboard-view">
    <div class="dashboard-header">
      <div>
        <h2 class="dashboard-title">Research Assistant</h2>
        <p class="dashboard-subtitle">科研工作台 · 一眼掌握论文、计划与任务</p>
      </div>
      <el-button type="primary" @click="$router.push('/library')">
        进入文库
      </el-button>
    </div>

    <el-skeleton v-if="loading" :rows="8" animated />

    <template v-else-if="data">
      <!-- 论文统计 -->
      <el-row :gutter="16" class="stat-row">
        <el-col :xs="12" :sm="8" :md="4" v-for="s in paperStatList" :key="s.key">
          <el-card shadow="hover" class="stat-card" :body-style="{ padding: '16px' }">
            <div class="stat-label">{{ s.label }}</div>
            <el-statistic :value="s.value" />
          </el-card>
        </el-col>
      </el-row>

      <el-row :gutter="16" class="panel-row">
        <!-- 阅读计划 -->
        <el-col :xs="24" :md="12" :lg="8">
          <el-card shadow="hover" class="panel-card">
            <template #header>
              <div class="panel-header">
                <span>阅读计划</span>
                <el-button text size="small" @click="$router.push('/reading-plans')">查看</el-button>
              </div>
            </template>
            <div class="plan-stats">
              <div class="plan-stat warn">
                <div class="plan-number">{{ data.readingPlanStats.overdue }}</div>
                <div class="plan-desc">已逾期</div>
              </div>
              <div class="plan-stat info">
                <div class="plan-number">{{ data.readingPlanStats.dueSoon }}</div>
                <div class="plan-desc">3 天内到期</div>
              </div>
              <div class="plan-stat primary">
                <div class="plan-number">{{ data.readingPlanStats.thisWeek }}</div>
                <div class="plan-desc">本周待读</div>
              </div>
            </div>
            <div class="progress-wrap">
              <div class="progress-label">
                阅读进度
                <span>{{ readProgress }}%</span>
              </div>
              <el-progress :percentage="readProgress" :stroke-width="10" :show-text="false" />
            </div>
          </el-card>
        </el-col>

        <!-- 文件夹堆积 -->
        <el-col :xs="24" :md="12" :lg="8">
          <el-card shadow="hover" class="panel-card">
            <template #header>
              <div class="panel-header">
                <span>文件夹堆积 Top {{ data.folderBacklog.length }}</span>
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
        </el-col>

        <!-- 任务状态 -->
        <el-col :xs="24" :md="12" :lg="8">
          <el-card shadow="hover" class="panel-card">
            <template #header>
              <div class="panel-header">
                <span>任务中心</span>
                <el-button text size="small" @click="$router.push('/tasks')">查看</el-button>
              </div>
            </template>
            <div class="task-stats">
              <div class="task-chip pending">待处理 {{ data.taskStats.pending }}</div>
              <div class="task-chip processing">进行中 {{ data.taskStats.processing }}</div>
              <div class="task-chip processing">重试等待 {{ data.taskStats.retryWait || 0 }}</div>
              <div class="task-chip completed">已完成 {{ data.taskStats.completed }}</div>
              <div class="task-chip failed">失败 {{ data.taskStats.failed }}</div>
              <div class="task-chip cancelled">取消 {{ data.taskStats.cancelled }}</div>
              <div class="task-chip pending">待确认 {{ data.taskStats.pendingUser || 0 }}</div>
              <div class="task-chip cancelled">过期 {{ data.taskStats.expired || 0 }}</div>
              <div class="task-chip failed">死信 {{ data.taskStats.deadLetter || 0 }}</div>
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
        </el-col>
      </el-row>

      <el-row :gutter="16" class="panel-row">
        <!-- 最近笔记 -->
        <el-col :xs="24" :md="12">
          <el-card shadow="hover" class="panel-card">
            <template #header>
              <div class="panel-header">
                <span>最近笔记</span>
              </div>
            </template>
            <div v-for="n in data.recentNotes" :key="n.id" class="recent-row">
              <span class="recent-title-text" :title="n.title">{{ n.title || '无标题笔记' }}</span>
              <span class="recent-time">{{ formatTime(n.createdAt) }}</span>
            </div>
            <el-empty v-if="!data.recentNotes.length" description="暂无笔记" :image-size="60" />
          </el-card>
        </el-col>

        <!-- 最近批注 -->
        <el-col :xs="24" :md="12">
          <el-card shadow="hover" class="panel-card">
            <template #header>
              <div class="panel-header">
                <span>最近批注</span>
              </div>
            </template>
            <div v-for="a in data.recentAnnotations" :key="a.id" class="recent-row">
              <span class="recent-title-text" :title="a.paperTitle">
                {{ a.paperTitle || '未命名论文' }}
                <span class="annotation-page">P{{ a.page }}</span>
              </span>
              <span class="recent-time">{{ formatTime(a.createdAt) }}</span>
            </div>
            <el-empty v-if="!data.recentAnnotations.length" description="暂无批注" :image-size="60" />
          </el-card>
        </el-col>
      </el-row>
      <ResearchInsightsPanel />
      <PdfWorkbenchMetricsPanel />
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getDashboard } from '@/api/dashboard'
import ResearchInsightsPanel from '@/components/dashboard/ResearchInsightsPanel.vue'
import PdfWorkbenchMetricsPanel from '@/components/dashboard/PdfWorkbenchMetricsPanel.vue'

const loading = ref(true)
const data = ref(null)

const paperStatList = computed(() => {
  const ps = data.value?.paperStats || {}
  return [
    { key: 'total', label: '论文总数', value: ps.total || 0 },
    { key: 'unread', label: '未读', value: ps.unread || 0 },
    { key: 'reading', label: '正读', value: ps.reading || 0 },
    { key: 'read', label: '已读', value: ps.read || 0 },
    { key: 'pinned', label: '置顶', value: ps.pinned || 0 },
    { key: 'thisMonth', label: '本月新增', value: ps.thisMonth || 0 },
  ]
})

const readProgress = computed(() => {
  const ps = data.value?.paperStats
  if (!ps || !ps.total) return 0
  return Math.round((ps.read / ps.total) * 100)
})

const folderBacklogWithPercent = computed(() => {
  const list = data.value?.folderBacklog || []
  const max = Math.max(...list.map(f => f.paperCount), 1)
  return list.map(f => ({ ...f, percent: Math.round((f.paperCount / max) * 100) }))
})

async function load() {
  loading.value = true
  try {
    data.value = await getDashboard()
  } catch (e) {
    ElMessage.error('加载看板失败')
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
  const map = {
    PENDING: '待处理',
    PROCESSING: '进行中',
    RETRY_WAIT: '等待重试',
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '取消',
    PENDING_USER: '待确认',
    EXPIRED: '已过期',
    DEAD_LETTER: '超过重试上限',
  }
  return map[status] || status
}

function taskTagType(status) {
  switch (status) {
    case 'COMPLETED': return 'success'
    case 'FAILED': return 'danger'
    case 'PROCESSING': return 'warning'
    case 'RETRY_WAIT': return 'warning'
    case 'CANCELLED':
    case 'EXPIRED': return 'info'
    case 'DEAD_LETTER': return 'danger'
    default: return ''
  }
}

onMounted(load)
</script>

<style scoped>
.dashboard-view {
  padding: 20px;
  max-width: 1400px;
  margin: 0 auto;
}
.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.dashboard-title {
  margin: 0;
  font-size: 22px;
  color: var(--ra-text);
}
.dashboard-subtitle {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--ra-text-secondary);
}
.stat-row {
  margin-bottom: 16px;
}
.stat-card {
  margin-bottom: 16px;
  background: var(--ra-header-bg);
  border: 1px solid var(--ra-border);
}
.stat-label {
  font-size: 12px;
  color: var(--ra-text-secondary);
  margin-bottom: 8px;
}
.panel-row {
  margin-bottom: 16px;
}
.panel-card {
  background: var(--ra-header-bg);
  border: 1px solid var(--ra-border);
  margin-bottom: 16px;
  min-height: 220px;
}
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  color: var(--ra-text);
}
.plan-stats {
  display: flex;
  justify-content: space-around;
  text-align: center;
  margin-bottom: 20px;
}
.plan-stat .plan-number {
  font-size: 28px;
  font-weight: 700;
  line-height: 1;
}
.plan-stat .plan-desc {
  font-size: 12px;
  color: var(--ra-text-secondary);
  margin-top: 6px;
}
.plan-stat.warn .plan-number { color: #f56c6c; }
.plan-stat.info .plan-number { color: #e6a23c; }
.plan-stat.primary .plan-number { color: #409eff; }
.progress-wrap {
  margin-top: 12px;
}
.progress-label {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--ra-text-secondary);
  margin-bottom: 6px;
}
.folder-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
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
  border-radius: 12px;
  background: var(--ra-bg);
  color: var(--ra-text);
  border: 1px solid var(--ra-border);
}
.task-chip.pending { color: #909399; }
.task-chip.processing { color: #e6a23c; }
.task-chip.completed { color: #67c23a; }
.task-chip.failed { color: #f56c6c; }
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
.recent-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid var(--ra-border);
  font-size: 13px;
}
.recent-row:last-child {
  border-bottom: none;
}
.recent-title-text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ra-text);
}
.annotation-page {
  font-size: 11px;
  color: var(--ra-text-secondary);
  margin-left: 6px;
}
.recent-time {
  font-size: 12px;
  color: var(--ra-text-secondary);
  white-space: nowrap;
  margin-left: 12px;
}
</style>
