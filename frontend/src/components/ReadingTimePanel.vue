<template>
  <div class="reading-time-panel">
    <span class="reading-time">已读 {{ formatDuration(readSeconds) }}</span>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { getReadingProgress, addReadingTime } from '@/api/readingProgress.js'

const props = defineProps({
  paper: { type: Object, required: true }
})

const emit = defineEmits(['updated'])

const readSeconds = ref(Number(props.paper.readSeconds) || 0)
let timer = null
let pendingSeconds = 0
let lastSyncSeconds = 0

onMounted(() => {
  loadReadTime()
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
  readSeconds.value = Number(props.paper.readSeconds) || 0
  pendingSeconds = 0
  lastSyncSeconds = 0
  loadReadTime()
})

async function loadReadTime() {
  try {
    const res = await getReadingProgress(props.paper.id)
    const data = res.data || res
    if (data?.readSeconds != null) readSeconds.value = Number(data.readSeconds) || 0
  } catch (e) {
    // 读时长只是辅助信息，加载失败不影响 PDF 阅读。
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

function startTimer() {
  if (timer) return
  timer = setInterval(() => {
    if (document.visibilityState === 'hidden') return
    pendingSeconds++
    readSeconds.value++
    if (pendingSeconds - lastSyncSeconds >= 30) syncTime()
  }, 1000)
}

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function onVisibilityChange() {
  if (document.visibilityState === 'hidden') syncTime()
}

function flushTime() {
  if (pendingSeconds > lastSyncSeconds) syncTime()
}

async function syncTime() {
  const delta = pendingSeconds - lastSyncSeconds
  if (delta <= 0 || !props.paper?.id) return
  try {
    await addReadingTime(props.paper.id, delta)
    lastSyncSeconds = pendingSeconds
    emit('updated', readSeconds.value)
  } catch (e) {
    // 保留未同步秒数，下次继续尝试。
  }
}
</script>

<style scoped>
.reading-time-panel {
  display: flex;
  align-items: center;
  font-size: 13px;
  color: #606266;
}
.reading-time {
  color: #409eff;
  font-weight: 500;
  white-space: nowrap;
}
</style>
