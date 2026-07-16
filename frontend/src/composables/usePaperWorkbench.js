import { onUnmounted, ref } from 'vue'
import { getTask } from '@/api/tasks.js'
import {
  executeWorkbenchRun,
  getWorkbenchRun,
  listPaperWorkbenchRuns,
  planWorkbenchRun,
} from '@/api/workbench.js'
import { poll } from '@/utils/task.js'
import { traceIsActive } from '@/utils/workbenchRun.js'

const FAILED_TASK_STATUSES = ['FAILED', 'CANCELLED', 'EXPIRED', 'DEAD_LETTER']

export function usePaperWorkbench() {
  const trace = ref(null)
  const recentRuns = ref([])
  const running = ref(false)
  const loadingHistory = ref(false)
  const stageText = ref('')
  const error = ref('')
  let controller = null

  function stopWatching() {
    controller?.abort()
    controller = null
  }

  async function refresh(runId, signal) {
    const next = await getWorkbenchRun(runId, signal ? { signal } : undefined)
    trace.value = next
    return next
  }

  async function watchRun(runId, taskId) {
    stopWatching()
    controller = new AbortController()
    const signal = controller.signal
    running.value = true
    error.value = ''
    try {
      await poll({
        fetch: async () => {
          const taskEnvelope = await getTask(taskId, { signal })
          const task = taskEnvelope?.data
          await refresh(runId, signal)
          return task
        },
        isCompleted: task => task?.status === 'COMPLETED',
        isFailed: task => FAILED_TASK_STATUSES.includes(task?.status),
        getError: task => task?.error || (task?.status === 'CANCELLED' ? '任务已取消' : '论文助手执行失败'),
        onData: task => { stageText.value = task?.stageText || '' },
        signal,
        maxAttempts: 360,
        interval: 1000,
        timeoutMessage: '论文助手仍在后台运行，可稍后从最近运行中恢复',
      })
      await refresh(runId, signal)
      stageText.value = '完成'
      return trace.value
    } catch (reason) {
      if (reason?.message !== 'aborted') {
        try { await refresh(runId) } catch { /* keep the last trace */ }
        error.value = trace.value?.errorMessage || reason?.message || '论文助手执行失败'
      }
      throw reason
    } finally {
      if (controller?.signal === signal) controller = null
      running.value = false
    }
  }

  async function run(request) {
    stopWatching()
    trace.value = null
    stageText.value = '正在生成受限计划…'
    error.value = ''
    running.value = true
    try {
      const planned = await planWorkbenchRun(request)
      trace.value = planned
      const submission = await executeWorkbenchRun(planned.runId)
      trace.value = { ...planned, taskId: submission.taskId, status: submission.status }
      return await watchRun(planned.runId, submission.taskId)
    } catch (reason) {
      if (reason?.message !== 'aborted') error.value = reason?.response?.data?.message || reason?.message || '论文助手启动失败'
      running.value = false
      throw reason
    }
  }

  async function loadRecent(paperId) {
    loadingHistory.value = true
    try {
      recentRuns.value = await listPaperWorkbenchRuns(paperId, 5)
      if (!trace.value && recentRuns.value.length) trace.value = recentRuns.value[0]
      const active = recentRuns.value.find(item => traceIsActive(item) && item.taskId)
      if (active) {
        trace.value = active
        void watchRun(active.runId, active.taskId).catch(() => {})
      }
      return recentRuns.value
    } finally {
      loadingHistory.value = false
    }
  }

  function selectRun(run) {
    stopWatching()
    if (!run) {
      trace.value = null
      stageText.value = ''
      error.value = ''
      return
    }
    trace.value = run
    stageText.value = run.status === 'COMPLETED' ? '完成' : ''
    error.value = run.errorMessage || ''
    if (traceIsActive(run) && run.taskId) void watchRun(run.runId, run.taskId).catch(() => {})
  }

  onUnmounted(stopWatching)

  return {
    trace,
    recentRuns,
    running,
    loadingHistory,
    stageText,
    error,
    run,
    loadRecent,
    selectRun,
    stopWatching,
  }
}
