<template>
  <div class="reading-progress-panel" v-if="progress">
    <span v-if="loadingPageCount" class="progress-hint">正在检测页数…</span>
    <template v-else>
      <span class="progress-time" v-if="progress.readSeconds > 0">已读 {{ formatDuration(progress.readSeconds) }}</span>
      <el-progress
        :percentage="progress.progressPercent"
        :stroke-width="10"
        :show-text="true"
        style="width: 120px"
      ></el-progress>
      <span class="progress-page">
        第
        <el-input-number
          v-model="localPage"
          :min="1"
          :max="progress.pageCount || 1"
          :controls="false"
          size="small"
          style="width: 64px; margin: 0 6px"
          @change="onPageChange"
        />
        页 / 共 {{ progress.pageCount || '?' }} 页
      </span>
      <el-button
        v-if="progress.currentPage < progress.pageCount"
        size="small"
        type="primary"
        text
        @click="markFinished"
      >标记已读完</el-button>
    </template>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getReadingProgress,
  updateReadingProgress,
  addReadingTime
} from '@/api/readingProgress.js'

const props = defineProps({
  paper: { type: Object, required: true }
})

const emit = defineEmits(['updated'])

const progress = ref(null)
const localPage = ref(1)
const loadingPageCount = ref(false)

let timer = null
let pendingSeconds = 0
let lastSyncSeconds = 0

onMounted(() => {
  loadProgress()
  startTimer()
  window.addEventListener('beforeunload', flushTime)
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onUnmounted(() => {
  stopTimer()
  flushTime()
  window.removeEventListener('beforeunload', flushTime)
  document.removeEventListener('visibilitychange', onVisibilityChange)
})

watch(() => props.paper.id, () => {
  flushTime()
  loadProgress()
})

async function loadProgress() {
  try {
    loadingPageCount.value = !props.paper.pageCount
    const res = await getReadingProgress(props.paper.id)
    progress.value = res.data || res
    localPage.value = progress.value.currentPage > 0 ? progress.value.currentPage : 1
  } catch (e) {
    ElMessage.warning('阅读进度加载失败')
  } finally {
    loadingPageCount.value = false
  }
}

function formatDuration(totalSeconds) {
  const h = Math.floor(totalSeconds / 3600)
  const m = Math.floor((totalSeconds % 3600) / 60)
  const s = totalSeconds % 60
  if (h > 0) return `${h}小时${m}分`
  if (m > 0) return `${m}分${s}秒`
  return `${s}秒`
}

async function onPageChange(page) {
  if (!page || page < 1) return
  try {
    await updateReadingProgress(props.paper.id, page)
    if (progress.value) {
      progress.value.currentPage = page
      progress.value.progressPercent = progress.value.pageCount
        ? Math.min(100, Math.round((page / progress.value.pageCount) * 100))
        : 0
    }
    emit('updated')
    ElMessage.success('阅读进度已保存')
  } catch (e) {
    ElMessage.warning('进度保存失败')
  }
}

async function markFinished() {
  if (!progress.value?.pageCount) return
  await onPageChange(progress.value.pageCount)
}

function startTimer() {
  if (timer) return
  timer = setInterval(() => {
    if (document.visibilityState === 'hidden') return
    pendingSeconds++
    // 每 30 秒同步一次
    if (pendingSeconds - lastSyncSeconds >= 30) {
      syncTime()
    }
  }, 1000)
}

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function onVisibilityChange() {
  if (document.visibilityState === 'hidden') {
    syncTime()
  }
}

function flushTime() {
  if (pendingSeconds > lastSyncSeconds) {
    syncTime()
  }
}

async function syncTime() {
  const delta = pendingSeconds - lastSyncSeconds
  if (delta <= 0 || !props.paper?.id) return
  try {
    await addReadingTime(props.paper.id, delta)
    lastSyncSeconds = pendingSeconds
    if (progress.value) {
      progress.value.readSeconds = (progress.value.readSeconds || 0) + delta
    }
  } catch (e) {
    // 同步失败时保留 pendingSeconds，下次继续尝试
  }
}

// 暴露刷新方法，供父组件调用
defineExpose({ loadProgress })
</script>

<style scoped>
.reading-progress-panel {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
  color: #606266;
}
.progress-time {
  color: #409eff;
  font-weight: 500;
}
.progress-hint {
  color: #909399;
}
.progress-page {
  display: inline-flex;
  align-items: center;
}
</style>
