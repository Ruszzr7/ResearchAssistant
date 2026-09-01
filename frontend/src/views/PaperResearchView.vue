<template>
  <div class="paper-research-view">
    <PdfViewer
      v-if="paper?.pdfPath"
      ref="viewerRef"
      :key="paper.id"
      :paper="paper"
      :initial-page="initialPage"
      :initial-evidence="pendingEvidence"
      :research-session-id="activeResearchSessionId"
      :assistant-entry-key="assistantEntryKey"
      @page-change="onPageChange"
      @close="returnToLibrary"
      @open-paper-evidence="openPaperEvidence"
      @research-session-change="onResearchSessionChange"
    >
      <template #toolbar-extra>
        <ReadingTimePanel :paper="paper" @updated="onReadingTimeUpdated" />
      </template>
    </PdfViewer>

    <div v-else-if="loading" class="research-state" v-loading="true" />
    <el-result
      v-else-if="error"
      icon="error"
      title="无法打开论文助手"
      :sub-title="error"
      class="research-state"
    >
      <template #extra><el-button type="primary" @click="returnToLibrary">返回文库</el-button></template>
    </el-result>
    <el-empty v-else class="research-state" description="请先从文库选择一篇带 PDF 的论文">
      <el-button type="primary" @click="returnToLibrary">打开文库</el-button>
    </el-empty>
  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, nextTick, onActivated, onDeactivated, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getPaper } from '@/api/paper.js'
import { updateReadingProgress } from '@/api/readingProgress.js'
import { updateResearchSession } from '@/api/researchArchive.js'
import ReadingTimePanel from '@/components/ReadingTimePanel.vue'
import {
  positivePageNumber,
  positivePaperId,
  researchRouteLocation,
} from '@/router/workbenchRoute.js'
import {
  readLastResearchLocation,
  writeLastResearchLocation,
} from '@/utils/researchSessionState.js'

defineOptions({ name: 'PaperResearchView' })

const PdfViewer = defineAsyncComponent(() => import('@/components/pdf/PdfViewer.vue'))
const route = useRoute()
const router = useRouter()
const viewerRef = ref(null)
const paper = ref(null)
const loading = ref(false)
const error = ref('')
const initialPage = ref(1)
const currentPage = ref(1)
const pendingEvidence = ref(null)
const assistantEntryKey = ref(0)
let loadSequence = 0
let routePageTimer = null
let progressTimer = null
let sessionStateTimer = null
let lastPersistedPage = null

const isResearchRoute = computed(() => route.name === 'research')
const routePaperId = computed(() => positivePaperId(route.params.paperId))
const activeResearchSessionId = ref(null)

watch([isResearchRoute, routePaperId], ([active, id], previous = []) => {
  if (active && !previous[0]) {
    activeResearchSessionId.value = positivePaperId(route.query.session)
    assistantEntryKey.value += 1
  }
  if (active) void loadRoutePaper(id)
}, { immediate: true })
watch(() => route.query.page, page => {
  if (!isResearchRoute.value) return
  const requested = positivePageNumber(page)
  if (!requested || requested === currentPage.value || !paper.value) return
  currentPage.value = requested
  void nextTick(() => viewerRef.value?.goToPage?.(requested))
})
watch(() => route.query.session, session => {
  activeResearchSessionId.value = positivePaperId(session)
})

onActivated(() => {
  if (isResearchRoute.value && !routePaperId.value) void restoreLastResearchRoute()
})
onDeactivated(flushResearchState)
onUnmounted(flushResearchState)

async function restoreLastResearchRoute() {
  if (!isResearchRoute.value) return
  const last = readLastResearchLocation()
  if (last) await router.replace(last)
}

async function loadRoutePaper(id) {
  if (!id) {
    paper.value = null
    error.value = ''
    await restoreLastResearchRoute()
    return
  }
  const sequence = ++loadSequence
  loading.value = true
  error.value = ''
  try {
    const response = await getPaper(id)
    if (sequence !== loadSequence) return
    const loaded = response?.data || response
    if (!loaded) throw new Error('论文不存在')
    if (!loaded.pdfPath) throw new Error('该论文没有可打开的 PDF')
    paper.value = loaded
    activeResearchSessionId.value = positivePaperId(route.query.session)
    lastPersistedPage = positivePageNumber(loaded.currentPage)
    initialPage.value = positivePageNumber(route.query.page) || lastPersistedPage || 1
    currentPage.value = initialPage.value
    await ensureCanonicalRoute()
    rememberLocation()
  } catch (reason) {
    if (sequence !== loadSequence) return
    paper.value = null
    error.value = reason?.response?.data?.message || reason?.message || '论文加载失败'
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

async function ensureCanonicalRoute() {
  if (!paper.value) return
  const query = {
    ...route.query,
    page: String(initialPage.value),
  }
  delete query.mode
  delete query.paperIds
  if (route.path !== `/research/${paper.value.id}`
      || String(route.query.page || '') !== query.page
      || route.query.mode
      || route.query.paperIds) {
    await router.replace(researchRouteLocation(paper.value.id, query))
  }
}

function onPageChange(page) {
  const normalized = positivePageNumber(page)
  if (!normalized || !paper.value) return
  currentPage.value = normalized
  rememberLocation()
  clearTimeout(routePageTimer)
  routePageTimer = setTimeout(() => {
    if (!paper.value || String(route.query.page || '') === String(currentPage.value)) return
    void router.replace(researchRouteLocation(paper.value.id, {
      ...route.query,
      page: String(currentPage.value),
    }))
  }, 250)
  clearTimeout(progressTimer)
  progressTimer = setTimeout(() => { void persistPage() }, 800)
  scheduleResearchSessionPersist()
}

async function persistPage() {
  if (!paper.value || !currentPage.value || currentPage.value === lastPersistedPage) return
  const pageToSave = currentPage.value
  try {
    await updateReadingProgress(paper.value.id, pageToSave)
    lastPersistedPage = pageToSave
    if (paper.value) paper.value.currentPage = pageToSave
  } catch { /* URL remains the authoritative recovery state for this tab. */ }
}

function flushResearchState() {
  clearTimeout(routePageTimer)
  clearTimeout(progressTimer)
  clearTimeout(sessionStateTimer)
  routePageTimer = null
  progressTimer = null
  sessionStateTimer = null
  rememberLocation()
  void persistPage()
  void persistResearchSessionState()
}

function rememberLocation() {
  if (!paper.value) return
  writeLastResearchLocation({
    paperId: paper.value.id,
    page: currentPage.value,
    session: activeResearchSessionId.value,
  })
}

function onResearchSessionChange(sessionId) {
  const normalized = positivePaperId(sessionId)
  if (!paper.value) return
  activeResearchSessionId.value = normalized
  const query = { ...route.query }
  if (normalized) query.session = String(normalized)
  else delete query.session
  void router.replace(researchRouteLocation(paper.value.id, query)).then(() => {
    rememberLocation()
    scheduleResearchSessionPersist()
  })
}

function scheduleResearchSessionPersist() {
  if (!activeResearchSessionId.value) return
  clearTimeout(sessionStateTimer)
  sessionStateTimer = setTimeout(() => { void persistResearchSessionState() }, 450)
}

async function persistResearchSessionState() {
  if (!activeResearchSessionId.value || !paper.value) return
  try {
    await updateResearchSession(activeResearchSessionId.value, {
      lastPage: currentPage.value || 1,
      mode: 'selection',
      paperIds: [Number(paper.value.id)],
    })
  } catch { /* Run persistence can backfill the archive even if this resume update fails. */ }
}

async function openPaperEvidence(item) {
  const targetId = positivePaperId(item?.paperId)
  if (!targetId) return
  pendingEvidence.value = item
  const query = {
    ...route.query,
    ...(positivePageNumber(item?.page) ? { page: String(item.page) } : {}),
  }
  if (targetId === routePaperId.value) {
    if (query.page) await viewerRef.value?.goToPage?.(Number(query.page))
    return
  }
  delete query.session
  await router.push(researchRouteLocation(targetId, query))
}

function onReadingTimeUpdated(seconds) {
  if (paper.value) paper.value.readSeconds = seconds
}

function returnToLibrary() {
  flushResearchState()
  router.push(safeReturnTarget(route.query.returnTo))
}

function safeReturnTarget(value) {
  const target = String(Array.isArray(value) ? value[0] : value || '')
  if (target === '/archive') return target
  return '/library'
}
</script>

<style scoped>
.paper-research-view {
  height: 100vh;
  min-height: 0;
  overflow: hidden;
  background: var(--ra-bg);
}
.research-state {
  height: 100%;
  display: grid;
  place-items: center;
}
</style>
