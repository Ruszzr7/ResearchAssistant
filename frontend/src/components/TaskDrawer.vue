<template>
  <el-badge :value="activeCount || ''" :hidden="!activeCount" class="task-drawer-trigger">
    <el-button text title="后台任务" aria-label="打开后台任务" @click="openDrawer">
      <el-icon :size="18"><List /></el-icon>
    </el-button>
  </el-badge>

  <el-drawer v-model="visible" title="后台任务" size="420px" append-to-body>
    <div class="task-drawer__toolbar">
      <span>{{ activeCount ? `${activeCount} 项进行中` : '暂无进行中任务' }}</span>
      <el-button link type="primary" :loading="loading" @click="load">刷新</el-button>
    </div>
    <div v-if="tasks.length" class="task-drawer__list">
      <article v-for="task in tasks" :key="task.taskId" class="task-drawer__item">
        <div>
          <b>{{ task.title || '后台任务' }}</b>
          <small>{{ task.stageText || statusLabel(task.status) }}</small>
        </div>
        <el-tag size="small" :type="statusType(task.status)">{{ statusLabel(task.status) }}</el-tag>
      </article>
    </div>
    <el-empty v-else-if="!loading" description="暂无后台任务" :image-size="72" />
    <template #footer>
      <el-button type="primary" plain @click="openTaskCenter">查看全部任务</el-button>
    </template>
  </el-drawer>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { List } from '@element-plus/icons-vue'
import { listTasks } from '@/api/tasks.js'

const router = useRouter()
const visible = ref(false)
const loading = ref(false)
const tasks = ref([])
let timer = null

const activeStatuses = new Set(['PENDING', 'PROCESSING', 'RETRY_WAIT', 'PENDING_USER'])
const activeCount = computed(() => tasks.value.filter(task => activeStatuses.has(task.status)).length)

onMounted(() => {
  void load()
  timer = window.setInterval(load, 15000)
})
onBeforeUnmount(() => window.clearInterval(timer))

async function load() {
  if (loading.value) return
  loading.value = true
  try {
    const response = await listTasks(12)
    tasks.value = response?.data || []
  } catch { /* The task badge must never interrupt the active page. */ }
  finally { loading.value = false }
}

function openDrawer() {
  visible.value = true
  void load()
}

function openTaskCenter() {
  visible.value = false
  router.push('/tasks')
}

function statusLabel(status) {
  return {
    PENDING: '排队中', PROCESSING: '运行中', RETRY_WAIT: '等待重试',
    PENDING_USER: '待确认', COMPLETED: '已完成', FAILED: '失败',
    CANCELLED: '已取消', EXPIRED: '已过期', DEAD_LETTER: '重试耗尽',
  }[status] || status
}

function statusType(status) {
  if (status === 'COMPLETED') return 'success'
  if (['FAILED', 'DEAD_LETTER'].includes(status)) return 'danger'
  if (['RETRY_WAIT', 'PENDING_USER'].includes(status)) return 'warning'
  return 'info'
}
</script>

<style scoped>
.task-drawer-trigger :deep(.el-badge__content) { transform: translate(30%, -25%) scale(.85); }
.task-drawer__toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; color: var(--ra-text-tertiary); font-size: 12px; }
.task-drawer__list { display: flex; flex-direction: column; gap: 8px; }
.task-drawer__item { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px; border: 1px solid var(--ra-border); border-radius: 7px; }
.task-drawer__item > div { display: flex; flex-direction: column; min-width: 0; gap: 4px; }
.task-drawer__item b { overflow: hidden; color: var(--ra-text); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.task-drawer__item small { overflow: hidden; color: var(--ra-text-tertiary); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
</style>
