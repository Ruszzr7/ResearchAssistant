<template>
  <div class="pdf-viewer" @mouseup="captureTextSelection">
    <div class="pdf-toolbar">
      <div class="pdf-toolbar-left">
        <span class="pdf-title">{{ paper?.title }}</span>
        <slot name="toolbar-extra" />
      </div>
      <div class="pdf-toolbar-center">
        <div class="pdf-tool-group" aria-label="PDF 批注工具">
          <el-button-group size="small">
            <el-button :type="currentTool === 'select' ? 'primary' : 'default'" @click="setTool('select')">选择</el-button>
            <el-button @mousedown.prevent @click="applyTextAnnotation('HIGHLIGHT')">高亮</el-button>
            <el-button @mousedown.prevent @click="applyTextAnnotation('UNDERLINE')">下划线</el-button>
            <el-button :type="currentTool === 'note' ? 'primary' : 'default'" @mousedown.prevent @click="activateNote">便签</el-button>
          </el-button-group>
          <span class="selection-hint">{{ selectionHint }}</span>
        </div>

        <div class="zoom-controls" aria-label="PDF 缩放">
          <el-button size="small" :disabled="zoomPercent <= zoomOptions[0]" @click="changeZoom(-1)">−</el-button>
          <div class="zoom-menu" @click.stop>
            <el-button size="small" class="zoom-menu-button" @click="zoomMenuVisible = !zoomMenuVisible">{{ zoomPercent }}%⌄</el-button>
            <div v-if="zoomMenuVisible" class="zoom-option-list" role="menu" aria-label="缩放比例">
              <button v-for="zoom in zoomOptions" :key="zoom" type="button" :class="{ active: zoom === zoomPercent }" @click="selectZoom(zoom)">{{ zoom }}%</button>
            </div>
          </div>
          <el-button size="small" :disabled="zoomPercent >= zoomOptions[zoomOptions.length - 1]" @click="changeZoom(1)">+</el-button>
        </div>

        <div class="annotation-color-palette" aria-label="批注颜色">
          <button
            v-for="color in annotationColors"
            :key="color"
            type="button"
            class="annotation-color"
            :class="{ active: currentColor === color }"
            :style="{ backgroundColor: color }"
            :title="colorName(color)"
            :aria-label="colorName(color)"
            @mousedown.prevent
            @click.stop="currentColor = color"
          />
        </div>
        <el-color-picker v-model="currentColor" size="small" :predefine="predefineColors" show-alpha />

        <el-button size="small" :loading="aiGenerating" @click="generateAiAnnotationsLocal">AI 批注</el-button>
        <el-button size="small" :type="showNotePanel ? 'info' : 'default'" @click="showNotePanel = !showNotePanel">笔记</el-button>
        <el-button size="small" :type="currentTool === 'edit' ? 'info' : 'default'" @click="toggleAnnotationEditMode">
          {{ currentTool === 'edit' ? '完成编辑' : '编辑批注' }}
        </el-button>
        <el-button size="small" :disabled="!selectedAnnotation" @click="openAnnotationEditor(selectedAnnotation)">编辑</el-button>
        <el-button size="small" :disabled="!selectedAnnotation" @click="deleteSelected">删除批注</el-button>
      </div>
      <div class="pdf-toolbar-right">
        <el-button size="small" text @click="$emit('close')">关闭</el-button>
      </div>
    </div>

    <div class="viewer-body">
      <div ref="containerRef" class="pdf-pages" @scroll="onScroll">
        <div class="virtual-spacer" :style="{ height: topSpacerHeight + 'px' }" aria-hidden="true"></div>
        <div
          v-for="page in visiblePages"
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
            :class="{
              'note-mode': currentTool === 'note',
              'editing-annotations': currentTool === 'edit'
            }"
            @pointermove="onOverlayPointerMove"
            @pointerup="onOverlayPointerUp"
            @pointercancel="onOverlayPointerUp"
            @click="onOverlayClick"
          >
          <g v-for="ann in pageAnnotations(page.pageNum)" :key="ann.localId"
            @pointerdown.stop="beginAnnotationPointerDown($event, ann)"
            @click.stop="onAnnotationClick(ann)"
            :class="{
              selected: selectedAnnotation?.localId === ann.localId,
              'note-annotation': ann.type === 'NOTE'
            }"
          >
            <g v-if="ann.type === 'HIGHLIGHT'">
              <polygon
                v-for="(q, i) in ann.coordinates?.quads"
                :key="i"
                :points="quadPoints(q, page, ann.coordinates)"
                :fill="ann.color || '#ffeb3b'"
                fill-opacity="0.4"
              />
            </g>
            <g v-if="ann.type === 'UNDERLINE'">
              <line
                v-for="(q, i) in ann.coordinates?.quads"
                :key="i"
                :x1="quadLine(q, page, ann.coordinates).x1"
                :y1="quadLine(q, page, ann.coordinates).y1"
                :x2="quadLine(q, page, ann.coordinates).x2"
                :y2="quadLine(q, page, ann.coordinates).y2"
                :stroke="ann.color || '#ff9800'"
                stroke-width="2"
              />
            </g>
            <g
              v-if="currentTool === 'edit' && selectedAnnotation?.localId === ann.localId && isResizableAnnotation(ann)"
              class="annotation-resize-handles"
            >
              <rect
                class="annotation-resize-handle"
                :x="annotationResizeHandle(ann, page, 'start').x - 5"
                :y="annotationResizeHandle(ann, page, 'start').y - 5"
                width="10"
                height="10"
                rx="2"
                :stroke="ann.color || '#ff9800'"
                @pointerdown.stop="beginAnnotationResize($event, ann, page, 'start')"
                @click.stop
              />
              <rect
                class="annotation-resize-handle"
                :x="annotationResizeHandle(ann, page, 'end').x - 5"
                :y="annotationResizeHandle(ann, page, 'end').y - 5"
                width="10"
                height="10"
                rx="2"
                :stroke="ann.color || '#ff9800'"
                @pointerdown.stop="beginAnnotationResize($event, ann, page, 'end')"
                @click.stop
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
              <polygon
                v-for="(q, i) in ann.coordinates?.anchorQuads"
                :key="`anchor-${i}`"
                class="note-anchor-outline"
                :points="quadPoints(q, page, ann.coordinates)"
                :stroke="ann.color || '#ffeb3b'"
              />
              <line
                v-if="noteAnchorPoint(ann.coordinates, page)"
                class="note-leader"
                :x1="noteAnchorPoint(ann.coordinates, page).x"
                :y1="noteAnchorPoint(ann.coordinates, page).y"
                :x2="notePoint(ann.coordinates, page).x"
                :y2="notePoint(ann.coordinates, page).y - 10"
                :stroke="ann.color || '#ffeb3b'"
              />
              <rect
                class="note-marker"
                :x="notePoint(ann.coordinates, page).x - 10"
                :y="notePoint(ann.coordinates, page).y - 20"
                width="20"
                height="20"
                rx="4"
                :fill="ann.color || '#ffeb3b'"
              ><title>{{ ann.note || '便签' }}</title></rect>
              <text
                :x="notePoint(ann.coordinates, page).x"
                :y="notePoint(ann.coordinates, page).y - 6"
                text-anchor="middle"
                font-size="12"
              >📝</text>
            </g>
          </g>
          </svg>
          <div
            v-if="notePreview?.page === page.pageNum"
            class="note-content-popover"
            :style="notePreviewStyle(notePreview, page)"
            @click.stop
          >
            <div class="note-content-popover__text">{{ notePreview.note || '（空便签）' }}</div>
            <div class="note-content-popover__actions">
              <el-button link type="primary" size="small" @click="openAnnotationEditor(notePreview)">编辑</el-button>
              <el-button link type="danger" size="small" @click="deleteAnnotationImmediately(notePreview)">删除</el-button>
            </div>
          </div>
        </div>
        <div class="virtual-spacer" :style="{ height: bottomSpacerHeight + 'px' }" aria-hidden="true"></div>
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
        <el-button type="primary" :loading="noteSaving" @click="confirmNote">确定</el-button>
      </template>
    </el-dialog>

    <!-- 已保存批注的编辑弹窗 -->
    <el-dialog
      v-model="annotationEditorVisible"
      :title="annotationEditorTarget?.type === 'NOTE' ? '编辑便签' : '编辑批注'"
      width="420px"
      @closed="annotationEditorTarget = null"
    >
      <div class="annotation-editor-field">
        <span>颜色</span>
        <div class="annotation-color-palette">
          <button
            v-for="color in annotationColors"
            :key="`editor-${color}`"
            type="button"
            class="annotation-color"
            :class="{ active: annotationEditorColor === color }"
            :style="{ backgroundColor: color }"
            :title="colorName(color)"
            @click="annotationEditorColor = color"
          />
        </div>
      </div>
      <div v-if="annotationEditorTarget?.type === 'NOTE'" class="annotation-editor-field">
        <span>内容</span>
        <el-input v-model="annotationEditorText" type="textarea" :rows="4" maxlength="4000" show-word-limit />
      </div>
      <template #footer>
        <el-button type="danger" @click="deleteAnnotationFromEditor">删除</el-button>
        <el-button @click="annotationEditorVisible = false">取消</el-button>
        <el-button type="primary" :loading="annotationEditorSaving" @click="saveAnnotationEditor">保存修改</el-button>
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
import { ref, shallowRef, computed, onMounted, onUnmounted, nextTick } from 'vue'
import * as pdfjsLib from 'pdfjs-dist'
import pdfjsWorkerUrl from 'pdfjs-dist/build/pdf.worker.mjs?url'
import { listAnnotations, createAnnotation, updateAnnotation, deleteAnnotation, generateAiAnnotations } from '@/api/annotation'
import { listNotesByPaper, deleteNote, unlinkNote } from '@/api/notes'
import NoteLinkPanel from '@/components/notes/NoteLinkPanel.vue'
import NoteEditor from '@/components/notes/NoteEditor.vue'
import { resizeTextAnnotationQuads } from '@/utils/pdfAnnotation.js'
import { ElMessage } from 'element-plus'

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfjsWorkerUrl

const props = defineProps({
  paper: { type: Object, required: true }
})

const emit = defineEmits(['close'])

const containerRef = ref(null)
const canvasRefs = ref({})
const textLayerRefs = ref({})
const overlayRefs = ref({})
// PDF.js 的文档对象包含私有字段，不能被 Vue 深层代理，否则调用 getPage/render
// 时会报 "Cannot read from private field"。
const pdfDoc = shallowRef(null)
const renderedPages = ref([])
const visiblePageStart = ref(1)
const visiblePageEnd = ref(1)
const baseEstimatedPageHeight = 900
const zoomPercent = ref(100)
const renderedZoomPercent = ref(100)
const zoomOptions = [50, 75, 100, 125, 150, 200, 300]
const zoomMenuVisible = ref(false)
const estimatedPageHeight = computed(() => baseEstimatedPageHeight * zoomPercent.value / 100)
const annotations = ref([])
const selectedAnnotation = ref(null)
const currentTool = ref('select')
const currentColor = ref('#ffeb3b')
const aiGenerating = ref(false)
const pendingTextSelection = ref(null)

const noteDialogVisible = ref(false)
const noteEditText = ref('')
const noteEditTarget = ref(null)
const noteSaving = ref(false)
const notePreview = ref(null)
const annotationEditorVisible = ref(false)
const annotationEditorTarget = ref(null)
const annotationEditorText = ref('')
const annotationEditorColor = ref('#ffeb3b')
const annotationEditorSaving = ref(false)

const notes = ref([])
const showNotePanel = ref(false)
const noteEditorVisible = ref(false)
const noteEditInitial = ref(null)
const selectedNote = ref(null)
const contextMenu = ref({ visible: false, x: 0, y: 0 })

const annotationColors = ['#f44336', '#ffeb3b', '#2196f3', '#4caf50', '#000000']
const predefineColors = [...annotationColors, '#ff9800', '#9c27b0']

let nextLocalId = 1
let draggingNote = null
let resizingAnnotation = null
let suppressAnnotationClickId = null

const pageAnnotations = computed(() => (pageNum) => annotations.value.filter(a => a.page === pageNum))
const visiblePages = computed(() => renderedPages.value.slice(
  Math.max(0, visiblePageStart.value - 1), visiblePageEnd.value
))
const selectionHint = computed(() => {
  const text = pendingTextSelection.value?.text || ''
  if (text) return `已选中“${text.slice(0, 18)}${text.length > 18 ? '…' : ''}”，可添加高亮、下划线或关联便签`
  if (currentTool.value === 'note') return '点击页面放置便签；先选中文本再点“便签”可建立关联'
  if (currentTool.value === 'edit') return '点击批注后可编辑或删除；拖动高亮/下划线两端可调整范围'
  return '先拖动选择文本，再点高亮、下划线或便签'
})

function pageHeight(page) {
  return page?.height || estimatedPageHeight.value
}

function pageOffset(pageNum) {
  let offset = 0
  const end = Math.max(0, Math.min(pageNum - 1, renderedPages.value.length))
  for (let i = 0; i < end; i++) {
    offset += pageHeight(renderedPages.value[i])
    if (i < renderedPages.value.length - 1) offset += 16
  }
  return offset
}

const totalPageHeight = computed(() => pageOffset(renderedPages.value.length + 1))
const topSpacerHeight = computed(() => pageOffset(visiblePageStart.value))
const bottomSpacerHeight = computed(() => Math.max(
  0,
  totalPageHeight.value - pageOffset(visiblePageEnd.value + 1)
))

onMounted(() => {
  window.addEventListener('click', onWindowClick)
  loadDocument()
})
onUnmounted(() => {
  pdfDoc.value?.destroy()
  clearTimeout(scrollTimer)
  window.removeEventListener('click', onWindowClick)
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
    visiblePageStart.value = 1
    visiblePageEnd.value = Math.min(count, 3)
    await nextTick()
    updateVisiblePageRange()
    await renderVisiblePages()
    await Promise.all([loadAnnotations(), loadNotes()])
  } catch (e) {
    ElMessage.error('PDF 加载失败：' + (e.message || e))
  }
}

async function renderVisiblePages() {
  if (!containerRef.value || !pdfDoc.value) return
  updateVisiblePageRange()
  await nextTick()
  for (const page of visiblePages.value) {
    if (page.rendered) continue
    await renderPage(page)
  }
}

function updateVisiblePageRange() {
  const container = containerRef.value
  const count = renderedPages.value.length
  if (!container || !count) return
  const top = Math.max(0, container.scrollTop - estimatedPageHeight.value * 2)
  const bottom = container.scrollTop + container.clientHeight + estimatedPageHeight.value * 2
  let cursor = 0
  let first = 1
  let last = count
  for (let i = 0; i < count; i++) {
    const next = cursor + pageHeight(renderedPages.value[i])
    if (next >= top) {
      first = i + 1
      break
    }
    cursor = next + 16
  }
  cursor = 0
  for (let i = 0; i < count; i++) {
    const next = cursor + pageHeight(renderedPages.value[i])
    if (cursor <= bottom) last = i + 1
    cursor = next + 16
    if (cursor > bottom) break
  }
  visiblePageStart.value = Math.max(1, first)
  visiblePageEnd.value = Math.min(count, Math.max(visiblePageStart.value, last))
}

async function renderPage(pageState) {
  const canvas = canvasRefs.value[pageState.pageNum]
  const textLayer = textLayerRefs.value[pageState.pageNum]
  if (!canvas || !textLayer) return
  const page = await pdfDoc.value.getPage(pageState.pageNum)
  const dpr = window.devicePixelRatio || 1
  const baseViewport = page.getViewport({ scale: 1.5 * zoomPercent.value / 100 })
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

  textLayer.replaceChildren()
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
    width: page.width ? page.width + 'px' : 'min(100%, 900px)',
    height: page.height ? page.height + 'px' : estimatedPageHeight.value + 'px',
    marginBottom: page.pageNum < renderedPages.value.length ? '16px' : '0'
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
  updateVisiblePageRange()
  clearTimeout(scrollTimer)
  scrollTimer = setTimeout(renderVisiblePages, 100)
}

async function changeZoom(direction) {
  const current = Number(zoomPercent.value)
  const next = direction > 0
    ? zoomOptions.find(option => option > current)
    : [...zoomOptions].reverse().find(option => option < current)
  if (next == null || next === current) return
  zoomPercent.value = next
  await renderAtCurrentZoom()
}

async function setZoom(value) {
  const next = Number(value)
  if (!zoomOptions.includes(next) || next === zoomPercent.value) return
  zoomPercent.value = next
  await renderAtCurrentZoom()
}

async function selectZoom(value) {
  zoomMenuVisible.value = false
  await setZoom(value)
}

async function renderAtCurrentZoom() {
  if (!pdfDoc.value || renderedZoomPercent.value === zoomPercent.value) return
  const container = containerRef.value
  const ratio = zoomPercent.value / renderedZoomPercent.value
  const previousTop = container?.scrollTop || 0
  clearPendingTextSelection()

  for (const page of renderedPages.value) {
    page.rendered = false
    page.viewport = null
    page.width = 0
    page.height = 0
  }
  await nextTick()
  if (container) container.scrollTop = previousTop * ratio
  updateVisiblePageRange()
  await renderVisiblePages()
  renderedZoomPercent.value = zoomPercent.value
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

function captureTextSelection() {
  if (currentTool.value !== 'select') return
  // 浏览器在 mouseup 后才最终提交 Selection，放到下一帧读取可避免拿到旧范围。
  requestAnimationFrame(() => {
    const selection = window.getSelection()
    if (!selection || selection.isCollapsed) return
    const range = selection.getRangeAt(0)
    const groups = selectionGeometry(range)
    const text = selection.toString().replace(/\s+/g, ' ').trim()
    if (groups.length && text) {
      pendingTextSelection.value = { groups, text }
    }
  })
}

async function applyTextAnnotation(type) {
  const selection = pendingTextSelection.value
  if (!selection?.groups?.length) {
    ElMessage.warning('请先用“选择”拖动选中文本，再点击批注按钮')
    setTool('select')
    return
  }
  let savedCount = 0
  try {
    for (const group of selection.groups) {
      const annotation = {
        localId: nextLocalId++,
        paperId: props.paper.id,
        type,
        page: group.pageNum,
        color: currentColor.value,
        note: '',
        coordinates: {
          coordinateSpace: 'viewport',
          pageWidth: group.pageState.viewport.width,
          pageHeight: group.pageState.viewport.height,
          rotation: group.pageState.viewport.rotation,
          scale: group.pageState.viewport.scale,
          quads: group.quads
        }
      }
      await persistNewAnnotation(annotation)
      savedCount += 1
    }
    ElMessage.success(type === 'HIGHLIGHT'
      ? `已自动保存 ${savedCount} 条高亮`
      : `已自动保存 ${savedCount} 条下划线`)
  } catch (e) {
    ElMessage.error(`批注保存失败${savedCount ? `（已保存 ${savedCount} 条）` : ''}：${requestErrorMessage(e)}`)
  } finally {
    clearPendingTextSelection()
  }
}

function clearPendingTextSelection() {
  pendingTextSelection.value = null
  window.getSelection()?.removeAllRanges()
}

function setTool(tool) {
  currentTool.value = tool
  selectedAnnotation.value = null
  notePreview.value = null
  if (tool !== 'select') clearPendingTextSelection()
}

function toggleAnnotationEditMode() {
  setTool(currentTool.value === 'edit' ? 'select' : 'edit')
}

function activateNote() {
  const selection = pendingTextSelection.value
  if (selection?.groups?.length) {
    openAnchoredNote(selection)
    return
  }
  setTool(currentTool.value === 'note' ? 'select' : 'note')
}

function selectionGeometry(range) {
  const grouped = new Map()
  for (const rect of Array.from(range.getClientRects())) {
    if (rect.width <= 0 || rect.height <= 0) continue
    const pageEl = pageElementAt(rect.left + rect.width / 2, rect.top + rect.height / 2)
    if (!pageEl) continue
    const pageNum = Number(pageEl.dataset.page)
    const pageState = renderedPages.value.find(p => p.pageNum === pageNum)
    if (!pageState?.viewport) continue
    const pageRect = pageEl.getBoundingClientRect()
    const left = Math.max(rect.left, pageRect.left)
    const right = Math.min(rect.right, pageRect.right)
    const top = Math.max(rect.top, pageRect.top)
    const bottom = Math.min(rect.bottom, pageRect.bottom)
    if (right <= left || bottom <= top) continue
    if (!grouped.has(pageNum)) grouped.set(pageNum, { pageNum, pageState, quads: [] })
    grouped.get(pageNum).quads.push(rectToViewportQuad({ left, right, top, bottom }, pageRect))
  }
  return [...grouped.values()]
}

function pageElementAt(x, y) {
  const pages = containerRef.value?.querySelectorAll('.pdf-page') || []
  return [...pages].find(page => {
    const rect = page.getBoundingClientRect()
    return x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom
  }) || null
}

function findPageElement(node) {
  let el = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement
  while (el) {
    if (el.classList?.contains('pdf-page')) return el
    el = el.parentElement
  }
  return null
}

function rectToViewportQuad(rect, pageRect) {
  const x1 = (rect.left - pageRect.left) / pageRect.width
  const x2 = (rect.right - pageRect.left) / pageRect.width
  const y1 = (rect.bottom - pageRect.top) / pageRect.height
  const y3 = (rect.top - pageRect.top) / pageRect.height
  return { x1, y1, x2, y2: y1, x3: x2, y3, x4: x1, y4: y3 }
}

function pointQuad(x, y) {
  return { x1: x, y1: y, x2: x, y2: y, x3: x, y3: y, x4: x, y4: y }
}

function notePositionNearAnchor(quads) {
  const points = (quads || []).flatMap(q => [
    [q.x1, q.y1], [q.x2, q.y2], [q.x3, q.y3], [q.x4, q.y4]
  ])
  if (!points.length) return { x: 0.9, y: 0.1 }
  const xs = points.map(([x]) => x)
  const ys = points.map(([, y]) => y)
  return {
    x: clamp(Math.max(...xs) + 0.045, 0.04, 0.96),
    y: clamp((Math.min(...ys) + Math.max(...ys)) / 2, 0.04, 0.96)
  }
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value))
}

function onOverlayClick(e) {
  if (currentTool.value !== 'note' || noteDialogVisible.value) return
  const pageEl = findPageElement(e.target)
  if (!pageEl) return
  const pageNum = Number(pageEl.dataset.page)
  const pageState = renderedPages.value.find(p => p.pageNum === pageNum)
  if (!pageState?.viewport) return
  const rect = pageEl.getBoundingClientRect()
  if (!rect.width || !rect.height) return
  const x = clamp((e.clientX - rect.left) / rect.width, 0.02, 0.98)
  const y = clamp((e.clientY - rect.top) / rect.height, 0.02, 0.98)
  noteEditTarget.value = {
    localId: nextLocalId++,
    paperId: props.paper.id,
    type: 'NOTE',
    page: pageNum,
    color: currentColor.value,
    note: '',
    coordinates: {
      coordinateSpace: 'viewport',
      pageWidth: pageState.viewport.width,
      pageHeight: pageState.viewport.height,
      rotation: pageState.viewport.rotation,
      scale: pageState.viewport.scale,
      // quads 保持兼容旧数据；notePosition 是可拖动的便签位置。
      quads: [pointQuad(x, y)],
      notePosition: { x, y }
    }
  }
  noteEditText.value = ''
  noteDialogVisible.value = true
}

function openAnchoredNote(selection) {
  const group = selection.groups[0]
  if (!group?.pageState?.viewport || !group.quads?.length) {
    ElMessage.warning('未能获取所选文字的位置，请重新选择后再试')
    return
  }
  const pageState = group.pageState
  noteEditTarget.value = {
    localId: nextLocalId++,
    paperId: props.paper.id,
    type: 'NOTE',
    page: group.pageNum,
    color: currentColor.value,
    note: '',
    coordinates: {
      coordinateSpace: 'viewport',
      pageWidth: pageState.viewport.width,
      pageHeight: pageState.viewport.height,
      rotation: pageState.viewport.rotation,
      scale: pageState.viewport.scale,
      // anchorQuads 用于绘制选区外框和指向线；quads 保留给旧版批注数据读取。
      quads: group.quads,
      anchorQuads: group.quads,
      notePosition: notePositionNearAnchor(group.quads),
      anchorText: selection.text.slice(0, 500)
    }
  }
  noteEditText.value = ''
  clearPendingTextSelection()
  noteDialogVisible.value = true
}

async function confirmNote() {
  const content = noteEditText.value.trim()
  if (!content) {
    ElMessage.warning('请先填写便签内容')
    return
  }
  const target = noteEditTarget.value
  if (!target) return
  target.note = content
  noteSaving.value = true
  try {
    await persistNewAnnotation(target)
    selectedAnnotation.value = target
    notePreview.value = target
    currentTool.value = 'select'
    noteDialogVisible.value = false
    ElMessage.success(target.coordinates?.anchorQuads ? '已自动保存关联便签' : '已自动保存便签')
  } catch (e) {
    ElMessage.error('便签保存失败：' + requestErrorMessage(e))
  } finally {
    noteSaving.value = false
  }
}

function onOverlayPointerMove(e) {
  if (draggingNote) {
    const { annotation, pageEl, startPosition } = draggingNote
    const rect = pageEl.getBoundingClientRect()
    if (!rect.width || !rect.height) return
    annotation.coordinates = annotation.coordinates || { coordinateSpace: 'viewport' }
    const nextPosition = {
      x: clamp((e.clientX - rect.left) / rect.width, 0.02, 0.98),
      y: clamp((e.clientY - rect.top) / rect.height, 0.02, 0.98)
    }
    if (Math.abs(nextPosition.x - startPosition.x) > 0.002
        || Math.abs(nextPosition.y - startPosition.y) > 0.002) {
      draggingNote.moved = true
    }
    annotation.coordinates.notePosition = nextPosition
    return
  }
  if (resizingAnnotation) {
    resizeAnnotationRange(resizingAnnotation, e)
  }
}

async function onOverlayPointerUp(e) {
  if (draggingNote) {
    const drag = draggingNote
    const { captureTarget } = drag
    if (captureTarget?.hasPointerCapture?.(e.pointerId)) {
      captureTarget.releasePointerCapture(e.pointerId)
    }
    draggingNote = null
    if (!drag.moved) return
    suppressAnnotationClickId = drag.annotation.localId
    window.setTimeout(() => {
      suppressAnnotationClickId = null
    }, 250)
    try {
      await persistUpdatedAnnotation(drag.annotation)
    } catch (error) {
      ElMessage.error('便签位置保存失败：' + requestErrorMessage(error))
    }
    return
  }
  if (resizingAnnotation) {
    const resize = resizingAnnotation
    if (resize.captureTarget?.hasPointerCapture?.(e.pointerId)) {
      resize.captureTarget.releasePointerCapture(e.pointerId)
    }
    resizingAnnotation = null
    if (!resize.moved) return
    suppressAnnotationClickId = resize.annotation.localId
    window.setTimeout(() => {
      suppressAnnotationClickId = null
    }, 250)
    try {
      await persistUpdatedAnnotation(resize.annotation)
      ElMessage.success('批注范围已更新')
    } catch (error) {
      resize.annotation.coordinates.quads = resize.originalQuads
      ElMessage.error('批注范围保存失败：' + requestErrorMessage(error))
    }
  }
}

function beginAnnotationPointerDown(e, ann) {
  if (currentTool.value !== 'edit') return
  selectedAnnotation.value = ann
  if (ann.type !== 'NOTE') return
  const pageEl = findPageElement(e.currentTarget)
  if (!pageEl) return
  const startPosition = ann.coordinates?.notePosition || { x: 0.5, y: 0.5 }
  draggingNote = {
    annotation: ann,
    pageEl,
    captureTarget: e.currentTarget,
    startPosition: { ...startPosition },
    moved: false
  }
  e.currentTarget.setPointerCapture?.(e.pointerId)
}

function isResizableAnnotation(annotation) {
  return (annotation?.type === 'HIGHLIGHT' || annotation?.type === 'UNDERLINE')
    && Array.isArray(annotation.coordinates?.quads)
    && annotation.coordinates.quads.length > 0
}

function annotationResizeHandle(annotation, page, edge) {
  const quads = annotation.coordinates?.quads || []
  const quad = edge === 'start' ? quads[0] : quads[quads.length - 1]
  if (!quad || !page?.viewport) return { x: 0, y: 0 }
  const points = edge === 'start'
    ? [annotationPoint(quad.x1, quad.y1, page, annotation.coordinates), annotationPoint(quad.x4, quad.y4, page, annotation.coordinates)]
    : [annotationPoint(quad.x2, quad.y2, page, annotation.coordinates), annotationPoint(quad.x3, quad.y3, page, annotation.coordinates)]
  return {
    x: edge === 'start' ? Math.min(...points.map(([x]) => x)) : Math.max(...points.map(([x]) => x)),
    y: points.reduce((sum, [, y]) => sum + y, 0) / points.length
  }
}

function beginAnnotationResize(e, annotation, page, edge) {
  if (currentTool.value !== 'edit' || !isResizableAnnotation(annotation)) return
  const pageEl = findPageElement(e.currentTarget)
  if (!pageEl) return
  selectedAnnotation.value = annotation
  resizingAnnotation = {
    annotation,
    page,
    pageEl,
    edge,
    captureTarget: e.currentTarget,
    originalQuads: JSON.parse(JSON.stringify(annotation.coordinates.quads)),
    moved: false
  }
  e.currentTarget.setPointerCapture?.(e.pointerId)
  e.preventDefault()
}

function resizeAnnotationRange(resize, event) {
  const rect = resize.pageEl.getBoundingClientRect()
  if (!rect.width) return
  const quads = resize.annotation.coordinates?.quads
  if (!quads?.length) return
  const rawPointerX = clamp((event.clientX - rect.left) / rect.width, 0.002, 0.998)
  const result = resizeTextAnnotationQuads(quads, resize.edge, rawPointerX)
  if (!result.changed) return
  resize.annotation.coordinates.quads = result.quads
  resize.moved = true
}

function onAnnotationClick(ann) {
  if (suppressAnnotationClickId === ann.localId) return
  selectedAnnotation.value = ann
  if (currentTool.value === 'edit') return
  if (ann.type === 'NOTE') {
    notePreview.value = notePreview.value?.localId === ann.localId ? null : ann
  }
}

function openAnnotationEditor(annotation) {
  if (!annotation) return
  selectedAnnotation.value = annotation
  notePreview.value = null
  annotationEditorTarget.value = annotation
  annotationEditorText.value = annotation.note || ''
  annotationEditorColor.value = annotation.color || '#ffeb3b'
  annotationEditorVisible.value = true
}

async function saveAnnotationEditor() {
  const annotation = annotationEditorTarget.value
  if (!annotation) return
  const note = annotationEditorText.value.trim()
  if (annotation.type === 'NOTE' && !note) {
    ElMessage.warning('便签内容不能为空')
    return
  }
  const previous = { color: annotation.color, note: annotation.note }
  annotation.color = annotationEditorColor.value
  if (annotation.type === 'NOTE') annotation.note = note
  annotationEditorSaving.value = true
  try {
    await persistUpdatedAnnotation(annotation)
    annotationEditorVisible.value = false
    ElMessage.success('批注已更新')
  } catch (e) {
    annotation.color = previous.color
    annotation.note = previous.note
    ElMessage.error('批注更新失败：' + requestErrorMessage(e))
  } finally {
    annotationEditorSaving.value = false
  }
}

async function deleteAnnotationFromEditor() {
  const annotation = annotationEditorTarget.value
  if (!annotation) return
  await deleteAnnotationImmediately(annotation)
}

async function deleteSelected() {
  if (!selectedAnnotation.value) return
  await deleteAnnotationImmediately(selectedAnnotation.value)
}

async function deleteAnnotationImmediately(annotation) {
  if (!annotation) return
  try {
    if (annotation.id) {
      await deleteAnnotation(props.paper.id, annotation.id)
    }
    annotations.value = annotations.value.filter(item => item.localId !== annotation.localId)
    if (selectedAnnotation.value?.localId === annotation.localId) selectedAnnotation.value = null
    if (notePreview.value?.localId === annotation.localId) notePreview.value = null
    if (annotationEditorTarget.value?.localId === annotation.localId) {
      annotationEditorVisible.value = false
    }
    ElMessage.success('批注已删除')
  } catch (e) {
    ElMessage.error('批注删除失败：' + requestErrorMessage(e))
  }
}

async function persistNewAnnotation(annotation) {
  annotations.value.push(annotation)
  try {
    const saved = await createAnnotation(props.paper.id, toPayload(annotation))
    Object.assign(annotation, saved, { localId: annotation.localId })
    return annotation
  } catch (error) {
    annotations.value = annotations.value.filter(item => item.localId !== annotation.localId)
    throw error
  }
}

async function persistUpdatedAnnotation(annotation) {
  if (!annotation.id) return persistNewAnnotation(annotation)
  const saved = await updateAnnotation(props.paper.id, annotation.id, toPayload(annotation))
  Object.assign(annotation, saved, { localId: annotation.localId })
  return annotation
}

function requestErrorMessage(error) {
  return error?.response?.data?.message || error?.message || '请求失败'
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

function quadPoints(q, page, coords) {
  if (!page.viewport) return ''
  const v = page.viewport
  const p1 = annotationPoint(q.x1, q.y1, page, coords)
  const p2 = annotationPoint(q.x2, q.y2, page, coords)
  const p3 = annotationPoint(q.x3, q.y3, page, coords)
  const p4 = annotationPoint(q.x4, q.y4, page, coords)
  return `${p1[0]},${p1[1]} ${p2[0]},${p2[1]} ${p3[0]},${p3[1]} ${p4[0]},${p4[1]}`
}

function quadLine(q, page, coords) {
  if (!page.viewport) return { x1: 0, y1: 0, x2: 0, y2: 0 }
  const p1 = annotationPoint(q.x1, q.y1, page, coords)
  const p2 = annotationPoint(q.x2, q.y2, page, coords)
  return { x1: p1[0], y1: p1[1], x2: p2[0], y2: p2[1] }
}

function annotationPoint(x, y, page, coords) {
  const v = page.viewport
  if (coords?.coordinateSpace === 'viewport') return [x * v.width, y * v.height]
  const width = coords?.pageWidth || (v.viewBox[2] - v.viewBox[0])
  const height = coords?.pageHeight || (v.viewBox[3] - v.viewBox[1])
  return v.convertToViewportPoint(x * width + v.viewBox[0], y * height + v.viewBox[1])
}

function freehandPoints(coords, page) {
  if (!page.viewport || !coords?.points) return ''
  const v = page.viewport
  return coords.points.map(p => {
    if (coords.coordinateSpace === 'viewport') return `${p.x * v.width},${p.y * v.height}`
    const width = coords.pageWidth || (v.viewBox[2] - v.viewBox[0])
    const height = coords.pageHeight || (v.viewBox[3] - v.viewBox[1])
    const vp = v.convertToViewportPoint(p.x * width + v.viewBox[0], p.y * height + v.viewBox[1])
    return `${vp[0]},${vp[1]}`
  }).join(' ')
}

function notePoint(coords, page) {
  if (!page.viewport) return { x: 0, y: 0 }
  const position = coords?.notePosition
  if (position) {
    const p = annotationPoint(position.x, position.y, page, coords)
    return { x: p[0], y: p[1] }
  }
  const q = coords?.quads?.[0]
  if (!q) return { x: 0, y: 0 }
  const p = annotationPoint(q.x1, q.y1, page, coords)
  return { x: p[0], y: p[1] }
}

function noteAnchorPoint(coords, page) {
  if (!page.viewport || !coords?.anchorQuads?.length) return null
  const points = coords.anchorQuads.flatMap(q => [
    annotationPoint(q.x1, q.y1, page, coords),
    annotationPoint(q.x2, q.y2, page, coords),
    annotationPoint(q.x3, q.y3, page, coords),
    annotationPoint(q.x4, q.y4, page, coords)
  ])
  if (!points.length) return null
  const x = Math.max(...points.map(([pointX]) => pointX))
  const ys = points.map(([, pointY]) => pointY)
  return { x, y: (Math.min(...ys) + Math.max(...ys)) / 2 }
}

function notePreviewStyle(annotation, page) {
  const point = notePoint(annotation.coordinates, page)
  const popoverWidth = 250
  const popoverHeight = 126
  const pageWidth = page.width || popoverWidth + 16
  const pageHeight = page.height || popoverHeight + 16
  return {
    left: `${clamp(point.x + 12, 8, Math.max(8, pageWidth - popoverWidth - 8))}px`,
    top: `${clamp(point.y + 8, 8, Math.max(8, pageHeight - popoverHeight - 8))}px`
  }
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
  const group = selectionGeometry(range)[0]
  if (!group) return
  const pageNum = group.pageNum
  const pageState = renderedPages.value.find(p => p.pageNum === pageNum)
  const anchorText = selection.toString().slice(0, 200)
  let coordinates = null
  if (pageState?.viewport) {
    coordinates = {
      coordinateSpace: 'viewport',
      pageWidth: pageState.viewport.width,
      pageHeight: pageState.viewport.height,
      rotation: pageState.viewport.rotation,
      scale: pageState.viewport.scale,
      quads: group.quads
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

async function jumpToNote(note) {
  selectedNote.value = note
  if (note.page > 0 && containerRef.value) {
    visiblePageStart.value = Math.max(1, note.page - 1)
    visiblePageEnd.value = Math.min(renderedPages.value.length, note.page + 1)
    await nextTick()
    const el = containerRef.value.querySelector(`[data-page="${note.page}"]`)
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

function onWindowClick() {
  if (contextMenu.value.visible) contextMenu.value.visible = false
  if (zoomMenuVisible.value) zoomMenuVisible.value = false
  if (notePreview.value) notePreview.value = null
}

function colorName(color) {
  return {
    '#f44336': '红色',
    '#ffeb3b': '黄色',
    '#2196f3': '蓝色',
    '#4caf50': '绿色',
    '#000000': '黑色'
  }[color] || color
}
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
.pdf-tool-group, .zoom-controls {
  display: flex;
  align-items: center;
  gap: 6px;
}
.selection-hint {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--ra-text-secondary);
}
.zoom-menu-button { min-width: 72px; }
.zoom-menu { position: relative; }
.zoom-option-list {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  z-index: 8;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  min-width: 78px;
  padding: 4px;
  border: 1px solid var(--ra-border);
  border-radius: 6px;
  background: var(--ra-panel-bg);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.16);
}
.zoom-option-list button {
  border: 0;
  border-radius: 4px;
  padding: 5px 8px;
  color: var(--ra-text);
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.zoom-option-list button:hover,
.zoom-option-list button.active {
  background: var(--ra-hover-bg);
  color: var(--ra-link);
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
  gap: 0;
}
.virtual-spacer {
  width: 1px;
  flex: 0 0 auto;
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
  z-index: 1;
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
  z-index: 2;
  pointer-events: none;
}
.annotation-overlay.note-mode,
.annotation-overlay.editing-annotations {
  pointer-events: auto;
}
.annotation-overlay.note-mode {
  cursor: crosshair;
  touch-action: none;
}
.annotation-overlay.editing-annotations {
  cursor: pointer;
}
.annotation-overlay > g {
  pointer-events: none;
}
.annotation-overlay > g.note-annotation {
  pointer-events: all;
}
.annotation-overlay.editing-annotations > g { pointer-events: all; }
.annotation-overlay .selected {
  filter: drop-shadow(0 0 2px var(--ra-link));
}
.annotation-overlay .note-anchor-outline {
  fill: none;
  stroke-width: 1.5;
  stroke-dasharray: 3 2;
}
.annotation-overlay .note-leader {
  stroke-width: 1.5;
  stroke-dasharray: 3 2;
}
.annotation-overlay.editing-annotations .note-marker {
  cursor: grab;
}
.annotation-overlay.editing-annotations .note-marker:active {
  cursor: grabbing;
}
.annotation-resize-handle {
  fill: var(--ra-panel-bg);
  stroke-width: 2;
  cursor: ew-resize;
}
.annotation-resize-handle:hover {
  fill: var(--ra-hover-bg);
}
.note-content-popover {
  position: absolute;
  z-index: 3;
  width: 250px;
  max-height: 126px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  box-sizing: border-box;
  padding: 10px 12px;
  border: 1px solid var(--ra-border);
  border-radius: 8px;
  background: var(--ra-panel-bg);
  box-shadow: 0 5px 16px rgba(0, 0, 0, 0.2);
}
.note-content-popover__text {
  max-height: 70px;
  overflow: auto;
  white-space: pre-wrap;
  font-size: 13px;
  line-height: 1.45;
  color: var(--ra-text);
}
.note-content-popover__actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}
.annotation-editor-field {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 14px;
}
.annotation-editor-field > span {
  flex: 0 0 32px;
  padding-top: 4px;
  color: var(--ra-text-secondary);
  font-size: 13px;
}
.annotation-editor-field :deep(.el-textarea) {
  flex: 1;
}
.annotation-color-palette {
  display: flex;
  align-items: center;
  gap: 3px;
}
.annotation-color {
  width: 16px;
  height: 16px;
  padding: 0;
  border: 1px solid rgba(127, 127, 127, 0.55);
  border-radius: 50%;
  cursor: pointer;
  box-sizing: border-box;
}
.annotation-color.active {
  outline: 2px solid var(--ra-link);
  outline-offset: 1px;
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
