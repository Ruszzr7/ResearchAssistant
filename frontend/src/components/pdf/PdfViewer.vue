<template>
  <div class="pdf-viewer" @mouseup="onMouseUp">
    <div class="pdf-toolbar">
      <div class="pdf-toolbar-left">
        <span class="pdf-title">{{ paper?.title }}</span>
        <slot name="toolbar-extra" />
      </div>
      <div class="pdf-toolbar-center">
        <el-radio-group v-model="currentTool" size="small">
          <el-radio-button label="select">选择</el-radio-button>
          <el-radio-button label="highlight">高亮</el-radio-button>
          <el-radio-button label="underline">下划线</el-radio-button>
          <el-radio-button label="note">便签</el-radio-button>
          <el-radio-button label="freehand">圈注</el-radio-button>
        </el-radio-group>

        <el-color-picker v-model="currentColor" size="small" :predefine="predefineColors" show-alpha />

        <el-button size="small" type="primary" :loading="saving" @click="saveAnnotations">保存批注</el-button>
        <el-button size="small" :loading="aiGenerating" @click="generateAiAnnotationsLocal">AI 批注</el-button>
        <el-button size="small" :type="showNotePanel ? 'info' : 'default'" @click="showNotePanel = !showNotePanel">笔记</el-button>
        <el-button size="small" :disabled="!selectedAnnotation" @click="deleteSelected">删除</el-button>
      </div>
      <div class="pdf-toolbar-right">
        <el-button size="small" text @click="$emit('close')">关闭</el-button>
      </div>
    </div>

    <div class="viewer-body">
      <div ref="containerRef" class="pdf-pages" @scroll="onScroll">
        <div
          v-for="page in renderedPages"
          :key="page.pageNum"
          :data-page="page.pageNum"
          class="pdf-page"
          :style="pageWrapStyle(page)"
          @contextmenu.prevent="onContextMenu"
        >
          <canvas :ref="el => setCanvasRef(el, page.pageNum)" />
          <div
            :ref="el => setTextLayerRef(el, page.pageNum)"
            class="text-layer"
            :style="layerStyle(page)"
          />
          <svg
            :ref="el => setOverlayRef(el, page.pageNum)"
            class="annotation-overlay"
            :style="layerStyle(page)"
            @pointerdown="onOverlayPointerDown"
            @pointermove="onOverlayPointerMove"
            @pointerup="onOverlayPointerUp"
            @click="onOverlayClick"
          >
          <g v-for="ann in pageAnnotations(page.pageNum)" :key="ann.localId"
            @pointerdown.stop="selectAnnotation(ann)"
            :class="{ selected: selectedAnnotation?.localId === ann.localId }"
          >
            <g v-if="ann.type === 'HIGHLIGHT'">
              <polygon
                v-for="(q, i) in ann.coordinates?.quads"
                :key="i"
                :points="quadPoints(q, page)"
                :fill="ann.color || '#ffeb3b'"
                fill-opacity="0.4"
              />
            </g>
            <g v-if="ann.type === 'UNDERLINE'">
              <line
                v-for="(q, i) in ann.coordinates?.quads"
                :key="i"
                :x1="quadLine(q, page).x1"
                :y1="quadLine(q, page).y1"
                :x2="quadLine(q, page).x2"
                :y2="quadLine(q, page).y2"
                :stroke="ann.color || '#ff9800'"
                stroke-width="2"
              />
            </g>
            <g v-if="ann.type === 'FREEHAND'">
              <polyline
                :points="freehandPoints(ann.coordinates, page)"
                :stroke="ann.color || '#f44336'"
                fill="none"
                stroke-width="2"
              />
            </g>
            <g v-if="ann.type === 'NOTE'">
              <rect
                :x="notePoint(ann.coordinates, page).x - 10"
                :y="notePoint(ann.coordinates, page).y - 20"
                width="20"
                height="20"
                rx="4"
                :fill="ann.color || '#ffeb3b'"
              />
              <text
                :x="notePoint(ann.coordinates, page).x"
                :y="notePoint(ann.coordinates, page).y - 6"
                text-anchor="middle"
                font-size="12"
              >📝</text>
            </g>
          </g>
        </svg>
      </div>
    </div>

    <NoteLinkPanel
      v-if="showNotePanel"
      :notes="notes"
      :selected-id="selectedNote?.id"
      @new-note="openNoteEditor()"
      @edit="openNoteEditor($event)"
      @delete="deleteNoteLocal"
      @select="jumpToNote"
    />
    </div>

    <!-- 便签编辑弹窗 -->
    <el-dialog v-model="noteDialogVisible" title="批注内容" width="400px" @closed="noteEditTarget = null">
      <el-input
        v-model="noteEditText"
        type="textarea"
        :rows="4"
        placeholder="输入批注..."
      />
      <template #footer>
        <el-button @click="noteDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmNote">确定</el-button>
      </template>
    </el-dialog>
    <!-- 笔记编辑弹窗 -->
    <NoteEditor
      v-model="noteEditorVisible"
      :paper-id="props.paper.id"
      :initial="noteEditInitial"
      @saved="onNoteSaved"
    />

    <!-- 右键菜单 -->
    <div
      v-if="contextMenu.visible"
      class="context-menu"
      :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
      @click.stop
    >
      <div class="context-menu-item" @click="createNoteFromSelection">📝 新建笔记</div>
      <div class="context-menu-item" @click="contextMenu.visible = false">取消</div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import * as pdfjsLib from 'pdfjs-dist'
import pdfjsWorkerUrl from 'pdfjs-dist/build/pdf.worker.mjs?url'
import { listAnnotations, createAnnotation, updateAnnotation, deleteAnnotation, generateAiAnnotations } from '@/api/annotation'
import { listNotesByPaper, deleteNote, unlinkNote } from '@/api/notes'
import NoteLinkPanel from '@/components/notes/NoteLinkPanel.vue'
import NoteEditor from '@/components/notes/NoteEditor.vue'
import { ElMessage } from 'element-plus'

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfjsWorkerUrl

const props = defineProps({
  paper: { type: Object, required: true }
})

const emit = defineEmits(['close', 'pageChanged'])

const containerRef = ref(null)
const canvasRefs = ref({})
const textLayerRefs = ref({})
const overlayRefs = ref({})
const pdfDoc = ref(null)
const renderedPages = ref([])
const annotations = ref([])
const selectedAnnotation = ref(null)
const currentTool = ref('select')
const currentColor = ref('#ffeb3b')
const saving = ref(false)
const aiGenerating = ref(false)

const noteDialogVisible = ref(false)
const noteEditText = ref('')
const noteEditTarget = ref(null)

const notes = ref([])
const showNotePanel = ref(false)
const noteEditorVisible = ref(false)
const noteEditInitial = ref(null)
const selectedNote = ref(null)
const contextMenu = ref({ visible: false, x: 0, y: 0 })

const predefineColors = ['#ffeb3b', '#ff9800', '#f44336', '#4caf50', '#2196f3', '#9c27b0']

let nextLocalId = 1
let freehandPointsTemp = []
let isDrawing = false

const pageAnnotations = computed(() => (pageNum) => annotations.value.filter(a => a.page === pageNum))

onMounted(() => loadDocument())
onUnmounted(() => {
  pdfDoc.value?.destroy()
})

async function loadDocument() {
  try {
    const url = `/api/papers/${props.paper.id}/pdf`
    const loading = pdfjsLib.getDocument(url)
    pdfDoc.value = await loading.promise
    const count = pdfDoc.value.numPages
    renderedPages.value = Array.from({ length: count }, (_, i) => ({
      pageNum: i + 1,
      viewport: null,
      width: 0,
      height: 0
    }))
    await nextTick()
    await renderVisiblePages()
    await Promise.all([loadAnnotations(), loadNotes()])
  } catch (e) {
    ElMessage.error('PDF 加载失败：' + (e.message || e))
  }
}

async function renderVisiblePages() {
  if (!containerRef.value || !pdfDoc.value) return
  const container = containerRef.value
  const top = container.scrollTop
  const bottom = top + container.clientHeight
  for (const page of renderedPages.value) {
    if (page.rendered) continue
    const el = container.querySelector(`[data-page="${page.pageNum}"]`)
    if (!el) continue
    const rect = el.getBoundingClientRect()
    const elTop = rect.top - container.getBoundingClientRect().top + top
    const elBottom = elTop + rect.height
    if (elBottom > top - 200 && elTop < bottom + 200) {
      await renderPage(page)
    }
  }
}

async function renderPage(pageState) {
  const canvas = canvasRefs.value[pageState.pageNum]
  const textLayer = textLayerRefs.value[pageState.pageNum]
  if (!canvas || !textLayer) return
  const page = await pdfDoc.value.getPage(pageState.pageNum)
  const dpr = window.devicePixelRatio || 1
  const baseViewport = page.getViewport({ scale: 1.5 })
  const viewport = baseViewport
  pageState.viewport = viewport
  pageState.width = viewport.width
  pageState.height = viewport.height

  canvas.width = Math.floor(viewport.width * dpr)
  canvas.height = Math.floor(viewport.height * dpr)
  canvas.style.width = viewport.width + 'px'
  canvas.style.height = viewport.height + 'px'
  const ctx = canvas.getContext('2d')
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)

  await page.render({ canvasContext: ctx, viewport }).promise

  textLayer.style.width = viewport.width + 'px'
  textLayer.style.height = viewport.height + 'px'
  try {
    const textContent = await page.getTextContent()
    const tl = new pdfjsLib.TextLayer({
      textContentSource: textContent,
      container: textLayer,
      viewport
    })
    await tl.render()
  } catch (e) {
    // 某些 PDF 没有文本层，忽略
  }
  pageState.rendered = true
}

function pageWrapStyle(page) {
  return {
    width: page.width ? page.width + 'px' : '100%',
    height: page.height ? page.height + 'px' : '800px'
  }
}

function layerStyle(page) {
  return {
    width: page.width ? page.width + 'px' : '100%',
    height: page.height ? page.height + 'px' : '100%'
  }
}

function setCanvasRef(el, pageNum) { if (el) canvasRefs.value[pageNum] = el }
function setTextLayerRef(el, pageNum) { if (el) textLayerRefs.value[pageNum] = el }
function setOverlayRef(el, pageNum) { if (el) overlayRefs.value[pageNum] = el }

let scrollTimer = null
function onScroll() {
  clearTimeout(scrollTimer)
  scrollTimer = setTimeout(renderVisiblePages, 100)
}

async function loadAnnotations() {
  try {
    const data = await listAnnotations(props.paper.id)
    annotations.value = data.map(a => ({ ...a, localId: nextLocalId++ }))
  } catch (e) {
    ElMessage.error('批注加载失败：' + (e.response?.data?.message || e.message))
  }
}

async function loadNotes() {
  try {
    notes.value = await listNotesByPaper(props.paper.id)
  } catch (e) {
    // 笔记加载失败不阻塞阅读
  }
}

function onMouseUp() {
  if (currentTool.value !== 'highlight' && currentTool.value !== 'underline') return
  const selection = window.getSelection()
  if (!selection || selection.isCollapsed) return
  const range = selection.getRangeAt(0)
  const pageEl = findPageElement(range.commonAncestorContainer)
  if (!pageEl) return
  const pageNum = Number(pageEl.dataset.page)
  const pageState = renderedPages.value.find(p => p.pageNum === pageNum)
  if (!pageState?.viewport) return

  const pageRect = pageEl.getBoundingClientRect()
  const rects = Array.from(range.getClientRects())
  const quads = rects.map(r => rectToQuad(r, pageRect, pageState.viewport))

  annotations.value.push({
    localId: nextLocalId++,
    paperId: props.paper.id,
    type: currentTool.value.toUpperCase(),
    page: pageNum,
    color: currentColor.value,
    note: '',
    coordinates: {
      pageWidth: pageState.viewport.width,
      pageHeight: pageState.viewport.height,
      rotation: pageState.viewport.rotation,
      scale: pageState.viewport.scale,
      quads
    },
    isNew: true
  })
  selection.removeAllRanges()
}

function findPageElement(node) {
  let el = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement
  while (el) {
    if (el.classList?.contains('pdf-page')) return el
    el = el.parentElement
  }
  return null
}

function rectToQuad(rect, pageRect, viewport) {
  const x1 = viewport.convertToPdfPoint(rect.left - pageRect.left, rect.bottom - pageRect.top)[0] / viewport.width
  const y1 = viewport.convertToPdfPoint(rect.left - pageRect.left, rect.bottom - pageRect.top)[1] / viewport.height
  const x2 = viewport.convertToPdfPoint(rect.right - pageRect.left, rect.bottom - pageRect.top)[0] / viewport.width
  const y2 = y1
  const x3 = viewport.convertToPdfPoint(rect.right - pageRect.left, rect.top - pageRect.top)[0] / viewport.width
  const y3 = viewport.convertToPdfPoint(rect.right - pageRect.left, rect.top - pageRect.top)[1] / viewport.height
  const x4 = x1
  const y4 = y3
  return { x1, y1, x2, y2, x3, y3, x4, y4 }
}

function onOverlayClick(e) {
  if (currentTool.value !== 'note') return
  const pageEl = findPageElement(e.target)
  if (!pageEl) return
  const pageNum = Number(pageEl.dataset.page)
  const pageState = renderedPages.value.find(p => p.pageNum === pageNum)
  if (!pageState?.viewport) return
  const rect = pageEl.getBoundingClientRect()
  const x = e.clientX - rect.left
  const y = e.clientY - rect.top
  const [pdfX, pdfY] = pageState.viewport.convertToPdfPoint(x, y)

  noteEditTarget.value = {
    localId: nextLocalId++,
    paperId: props.paper.id,
    type: 'NOTE',
    page: pageNum,
    color: currentColor.value,
    note: '',
    coordinates: {
      pageWidth: pageState.viewport.width,
      pageHeight: pageState.viewport.height,
      rotation: pageState.viewport.rotation,
      scale: pageState.viewport.scale,
      quads: [{
        x1: pdfX / pageState.viewport.width,
        y1: pdfY / pageState.viewport.height,
        x2: pdfX / pageState.viewport.width,
        y2: pdfY / pageState.viewport.height,
        x3: pdfX / pageState.viewport.width,
        y3: pdfY / pageState.viewport.height,
        x4: pdfX / pageState.viewport.width,
        y4: pdfY / pageState.viewport.height
      }]
    },
    isNew: true
  }
  noteEditText.value = ''
  noteDialogVisible.value = true
}

function confirmNote() {
  if (noteEditTarget.value) {
    noteEditTarget.value.note = noteEditText.value
    annotations.value.push(noteEditTarget.value)
  }
  noteDialogVisible.value = false
}

function onOverlayPointerDown(e) {
  if (currentTool.value !== 'freehand') return
  isDrawing = true
  freehandPointsTemp = []
  const pageEl = findPageElement(e.target)
  if (!pageEl) return
  const pageNum = Number(pageEl.dataset.page)
  freehandPointsTemp.pageNum = pageNum
  const rect = pageEl.getBoundingClientRect()
  const x = e.clientX - rect.left
  const y = e.clientY - rect.top
  freehandPointsTemp.push({ x, y })
  e.target.setPointerCapture(e.pointerId)
}

function onOverlayPointerMove(e) {
  if (!isDrawing || currentTool.value !== 'freehand') return
  const pageEl = findPageElement(e.target)
  if (!pageEl) return
  const rect = pageEl.getBoundingClientRect()
  freehandPointsTemp.push({ x: e.clientX - rect.left, y: e.clientY - rect.top })
}

function onOverlayPointerUp(e) {
  if (!isDrawing || currentTool.value !== 'freehand') return
  isDrawing = false
  const pageEl = findPageElement(e.target)
  if (!pageEl || freehandPointsTemp.length < 2) return
  const pageNum = Number(pageEl.dataset.page)
  const pageState = renderedPages.value.find(p => p.pageNum === pageNum)
  if (!pageState?.viewport) return

  const points = freehandPointsTemp.map(p => {
    const [pdfX, pdfY] = pageState.viewport.convertToPdfPoint(p.x, p.y)
    return { x: pdfX / pageState.viewport.width, y: pdfY / pageState.viewport.height }
  })

  annotations.value.push({
    localId: nextLocalId++,
    paperId: props.paper.id,
    type: 'FREEHAND',
    page: pageNum,
    color: currentColor.value,
    note: '',
    coordinates: {
      pageWidth: pageState.viewport.width,
      pageHeight: pageState.viewport.height,
      rotation: pageState.viewport.rotation,
      scale: pageState.viewport.scale,
      points
    },
    isNew: true
  })
  freehandPointsTemp = []
}

function selectAnnotation(ann) {
  if (currentTool.value !== 'select') return
  selectedAnnotation.value = ann
}

function deleteSelected() {
  if (!selectedAnnotation.value) return
  const idx = annotations.value.findIndex(a => a.localId === selectedAnnotation.value.localId)
  if (idx >= 0) {
    annotations.value[idx].deleted = true
  }
  selectedAnnotation.value = null
}

async function saveAnnotations() {
  saving.value = true
  try {
    const newItems = annotations.value.filter(a => a.isNew && !a.deleted)
    const updatedItems = annotations.value.filter(a => a.id && !a.isNew && a.dirty && !a.deleted)
    const deletedItems = annotations.value.filter(a => a.deleted && a.id)

    for (const ann of newItems) {
      const saved = await createAnnotation(props.paper.id, toPayload(ann))
      Object.assign(ann, saved, { localId: ann.localId, isNew: false })
    }
    for (const ann of updatedItems) {
      const saved = await updateAnnotation(ann.id, toPayload(ann))
      Object.assign(ann, saved, { localId: ann.localId, dirty: false })
    }
    for (const ann of deletedItems) {
      await deleteAnnotation(ann.id)
    }
    annotations.value = annotations.value.filter(a => !a.deleted)
    ElMessage.success('批注已保存')
  } catch (e) {
    ElMessage.error('保存失败：' + (e.response?.data?.message || e.message))
  } finally {
    saving.value = false
  }
}

async function generateAiAnnotationsLocal() {
  aiGenerating.value = true
  try {
    const created = await generateAiAnnotations(props.paper.id)
    for (const a of created) {
      annotations.value.push({ ...a, localId: nextLocalId++ })
    }
    ElMessage.success(`已生成 ${created.length} 条 AI 批注`)
  } catch (e) {
    ElMessage.error('AI 批注生成失败：' + (e.response?.data?.message || e.message))
  } finally {
    aiGenerating.value = false
  }
}

function toPayload(ann) {
  return {
    type: ann.type,
    page: ann.page,
    color: ann.color,
    note: ann.note,
    coordinates: ann.coordinates
  }
}

function quadPoints(q, page) {
  if (!page.viewport) return ''
  const v = page.viewport
  const p1 = v.convertToViewportPoint(q.x1 * v.width, q.y1 * v.height)
  const p2 = v.convertToViewportPoint(q.x2 * v.width, q.y2 * v.height)
  const p3 = v.convertToViewportPoint(q.x3 * v.width, q.y3 * v.height)
  const p4 = v.convertToViewportPoint(q.x4 * v.width, q.y4 * v.height)
  return `${p1[0]},${p1[1]} ${p2[0]},${p2[1]} ${p3[0]},${p3[1]} ${p4[0]},${p4[1]}`
}

function quadLine(q, page) {
  if (!page.viewport) return { x1: 0, y1: 0, x2: 0, y2: 0 }
  const v = page.viewport
  const p1 = v.convertToViewportPoint(q.x1 * v.width, q.y1 * v.height)
  const p2 = v.convertToViewportPoint(q.x2 * v.width, q.y2 * v.height)
  return { x1: p1[0], y1: p1[1], x2: p2[0], y2: p2[1] }
}

function freehandPoints(coords, page) {
  if (!page.viewport || !coords?.points) return ''
  const v = page.viewport
  return coords.points.map(p => {
    const vp = v.convertToViewportPoint(p.x * v.width, p.y * v.height)
    return `${vp[0]},${vp[1]}`
  }).join(' ')
}

function notePoint(coords, page) {
  if (!page.viewport || !coords?.quads?.length) return { x: 0, y: 0 }
  const v = page.viewport
  const q = coords.quads[0]
  const p = v.convertToViewportPoint(q.x1 * v.width, q.y1 * v.height)
  return { x: p[0], y: p[1] }
}

function onContextMenu(e) {
  const selection = window.getSelection()
  if (!selection || selection.isCollapsed) return
  contextMenu.value = { visible: true, x: e.clientX, y: e.clientY }
}

function createNoteFromSelection() {
  contextMenu.value.visible = false
  const selection = window.getSelection()
  if (!selection || selection.isCollapsed) return
  const range = selection.getRangeAt(0)
  const pageEl = findPageElement(range.commonAncestorContainer)
  if (!pageEl) return
  const pageNum = Number(pageEl.dataset.page)
  const pageState = renderedPages.value.find(p => p.pageNum === pageNum)
  const anchorText = selection.toString().slice(0, 200)
  let coordinates = null
  if (pageState?.viewport) {
    const pageRect = pageEl.getBoundingClientRect()
    const rects = Array.from(range.getClientRects())
    const quads = rects.map(r => rectToQuad(r, pageRect, pageState.viewport))
    coordinates = {
      pageWidth: pageState.viewport.width,
      pageHeight: pageState.viewport.height,
      rotation: pageState.viewport.rotation,
      scale: pageState.viewport.scale,
      quads
    }
  }
  noteEditInitial.value = {
    title: anchorText.slice(0, 30) + (anchorText.length > 30 ? '…' : ''),
    content: '',
    anchorText,
    page: pageNum,
    coordinates
  }
  noteEditorVisible.value = true
  selection.removeAllRanges()
}

function openNoteEditor(note = null) {
  noteEditInitial.value = note
  noteEditorVisible.value = true
}

async function onNoteSaved() {
  await loadNotes()
  if (!showNotePanel.value) showNotePanel.value = true
}

async function deleteNoteLocal(note) {
  try {
    await unlinkNote(props.paper.id, note.id)
    await loadNotes()
    ElMessage.success('已删除')
  } catch (e) {
    ElMessage.error('删除失败：' + (e.response?.data?.message || e.message))
  }
}

function jumpToNote(note) {
  selectedNote.value = note
  if (note.page > 0 && containerRef.value) {
    const el = containerRef.value.querySelector(`[data-page="${note.page}"]`)
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

// 点击空白处关闭右键菜单
window.addEventListener('click', () => {
  if (contextMenu.value.visible) contextMenu.value.visible = false
})
</script>

<style scoped>
.pdf-viewer {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--ra-bg);
}
.viewer-body {
  display: flex;
  flex: 1;
  overflow: hidden;
}
.pdf-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: var(--ra-panel-bg);
  border-bottom: 1px solid var(--ra-border);
  gap: 12px;
  flex-shrink: 0;
}
.pdf-toolbar-left, .pdf-toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 80px;
}
.pdf-toolbar-center {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: center;
}
.pdf-title {
  font-size: 14px;
  color: var(--ra-text);
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pdf-pages {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 16px 0;
  gap: 16px;
}
.pdf-page {
  position: relative;
  background: var(--ra-panel-bg);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  overflow: hidden;
  flex-shrink: 0;
}
.pdf-page canvas {
  display: block;
}
.text-layer {
  position: absolute;
  top: 0;
  left: 0;
  line-height: 1;
  user-select: text;
  cursor: text;
}
.text-layer ::v-deep(span) {
  color: transparent;
  position: absolute;
  white-space: pre;
  cursor: text;
  transform-origin: 0% 0%;
}
.annotation-overlay {
  position: absolute;
  top: 0;
  left: 0;
  pointer-events: auto;
  cursor: crosshair;
}
.annotation-overlay .selected {
  filter: drop-shadow(0 0 2px var(--ra-link));
}
.context-menu {
  position: fixed;
  z-index: 10000;
  background: var(--ra-panel-bg);
  border: 1px solid var(--ra-border);
  border-radius: 4px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.15);
  padding: 4px 0;
  min-width: 120px;
}
.context-menu-item {
  padding: 8px 16px;
  font-size: 13px;
  cursor: pointer;
}
.context-menu-item:hover {
  background: var(--ra-hover-bg);
}
</style>
