<template>
  <div class="pdf-viewer">
    <div
      class="pdf-toolbar"
      :style="{ marginRight: workbenchPanelVisible ? `${assistantPanelWidth + 8}px` : '0px' }"
    >
      <div class="pdf-toolbar-left">
        <span class="pdf-title" :title="paper?.title">{{ paper?.title }}</span>
        <slot name="toolbar-extra" />
      </div>
      <div class="pdf-toolbar-center">
        <div class="zoom-controls" aria-label="PDF 缩放">
          <el-button size="small" :disabled="zoomPercent <= zoomOptions[0]" @click="changeZoom(-1)">−</el-button>
          <div class="zoom-menu" @click.stop>
            <el-popover
              v-model:visible="zoomMenuVisible"
              trigger="click"
              placement="bottom"
              :width="76"
              :teleported="true"
              popper-class="pdf-zoom-popper"
            >
              <template #reference>
                <button type="button" class="zoom-value" aria-label="选择页面大小" aria-haspopup="menu" @mousedown.prevent>
                  {{ zoomPercent }}%
                </button>
              </template>
              <div class="zoom-option-list" role="menu" aria-label="页面大小">
                <button
                  v-for="option in zoomOptions"
                  :key="option"
                  type="button"
                  role="menuitem"
                  :class="{ active: option === zoomPercent }"
                  @click="selectZoomOption(option)"
                >{{ option }}%</button>
              </div>
            </el-popover>
          </div>
          <el-button size="small" :disabled="zoomPercent >= zoomOptions[zoomOptions.length - 1]" @click="changeZoom(1)">+</el-button>
        </div>
        <span class="toolbar-separator" aria-hidden="true" />

        <label class="page-navigation" title="输入页码后按 Enter 跳转">
          <span class="sr-only">跳转页码</span>
          <input
            v-model.number="currentPage"
            type="number"
            min="1"
            :max="renderedPages.length || 1"
            inputmode="numeric"
            aria-label="跳转页码"
            @keydown.enter.prevent="goToPage()"
            @blur="goToPage()"
          />
          <span aria-label="总页数">/ {{ renderedPages.length || 0 }}</span>
        </label>
        <span class="toolbar-separator" aria-hidden="true" />

        <el-button-group size="small" aria-label="文字标记">
          <el-button @mousedown.prevent @click="applyTextAnnotation('HIGHLIGHT')">高亮</el-button>
          <el-button @mousedown.prevent @click="applyTextAnnotation('UNDERLINE')">下划线</el-button>
        </el-button-group>
        <el-button size="small" @mousedown.prevent @click="openSelectionNote">笔记</el-button>
        <el-button-group size="small" aria-label="批注工具">
          <el-button @mousedown.prevent @click="openSelectionComment">批注</el-button>
          <el-button :type="commentPanelVisible ? 'primary' : 'default'" @click="toggleCommentPanel">批注列表</el-button>
        </el-button-group>
        <div class="annotation-color-menu" @click.stop>
          <el-popover
            v-model:visible="colorMenuVisible"
            trigger="click"
            placement="bottom"
            :width="44"
            :teleported="true"
            popper-class="pdf-annotation-color-popper"
          >
            <template #reference>
              <button
                type="button"
                class="annotation-color-trigger"
                :title="`标记颜色：${colorName(currentColor)}`"
                :aria-label="`标记颜色：${colorName(currentColor)}`"
                :style="{ '--annotation-color': currentColor }"
                @mousedown.prevent
              ><span aria-hidden="true" /></button>
            </template>
            <div class="annotation-color-list" role="menu" aria-label="选择标记颜色">
              <button
                v-for="color in annotationColors"
                :key="color"
                type="button"
                class="annotation-color-option"
                :class="{ active: currentColor === color }"
                :style="{ backgroundColor: color }"
                :title="colorName(color)"
                :aria-label="colorName(color)"
                @click="selectAnnotationColor(color)"
              />
            </div>
          </el-popover>
        </div>
      </div>
      <div class="pdf-toolbar-right">
        <el-button size="small" text :type="searchPanelVisible ? 'primary' : 'default'" @click="toggleSearchPanel">搜索</el-button>
        <el-button size="small" text :type="workbenchPanelVisible ? 'primary' : 'default'" @click="workbenchPanelVisible = !workbenchPanelVisible">论文助手</el-button>
        <el-button size="small" text @click="$emit('close')">关闭</el-button>
      </div>
    </div>

    <div ref="viewerBodyRef" class="viewer-body" :class="{ 'is-workbench-resizing': workbenchResizing }">
      <aside v-if="searchPanelVisible" class="pdf-search-panel" aria-label="PDF 搜索">
        <header class="side-panel-header">
          <strong>搜索文档</strong>
          <button type="button" aria-label="关闭搜索" title="关闭搜索" @click="closeSearchPanel">×</button>
        </header>
        <div class="pdf-search-box">
          <input
            ref="searchInputRef"
            v-model="searchQuery"
            type="search"
            autocomplete="off"
            placeholder="搜索 PDF 内容"
            aria-label="搜索 PDF 内容"
            @input="schedulePdfSearch"
            @keydown.enter.prevent="activateNextSearchResult($event.shiftKey ? -1 : 1)"
            @keydown.esc.prevent="closeSearchPanel"
          />
          <button v-if="searchQuery" type="button" aria-label="清除搜索" title="清除搜索" @click="clearPdfSearch">×</button>
        </div>
        <div class="pdf-search-summary">
          <span v-if="searchStatusText">{{ searchStatusText }}</span>
          <span class="pdf-search-navigation">
            <button type="button" :disabled="!searchResults.length" aria-label="上一个结果" @click="activateNextSearchResult(-1)">↑</button>
            <button type="button" :disabled="!searchResults.length" aria-label="下一个结果" @click="activateNextSearchResult(1)">↓</button>
          </span>
        </div>
        <div class="pdf-search-results" role="listbox" aria-label="搜索结果">
          <button
            v-for="(result, index) in searchResults"
            :key="result.id"
            type="button"
            role="option"
            class="pdf-search-result"
            :class="{ active: index === activeSearchResultIndex }"
            :aria-selected="index === activeSearchResultIndex"
            @click="activateSearchResult(index)"
          >
            <span>第 {{ result.page }} 页</span>
            <p>{{ result.context }}</p>
          </button>
        </div>
      </aside>

      <div ref="containerRef" class="pdf-pages" @scroll="onScroll">
        <div v-if="pdfLoadError" class="pdf-load-recovery" role="alert">
          <b>PDF 暂时无法显示</b>
          <span>{{ pdfLoadError }}</span>
          <el-button size="small" type="primary" @click="loadDocument">重新加载 PDF</el-button>
        </div>
        <div class="virtual-spacer" :style="{ height: topSpacerHeight + 'px' }" aria-hidden="true"></div>
        <div
          v-for="page in visiblePages"
          :key="page.pageNum"
          :data-page="page.pageNum"
          class="pdf-page"
          :style="pageWrapStyle(page)"
          @contextmenu.prevent="onContextMenu"
        >
          <div v-if="!page.canvasReady" class="pdf-page-loading" aria-hidden="true">
            正在渲染第 {{ page.pageNum }} 页…
          </div>
          <canvas :ref="el => setCanvasRef(el, page.pageNum)" />
          <div
            :ref="el => setTextLayerRef(el, page.pageNum)"
            class="text-layer"
            :style="layerStyle(page)"
            @pointerdown.capture="beginTextSelection($event, page.pageNum)"
            @pointermove.capture="updateTextSelection($event, page.pageNum)"
            @pointerup.capture="finishTextSelection($event, page.pageNum)"
            @pointercancel="cancelTextSelection($event)"
          />
          <svg
            :ref="el => setOverlayRef(el, page.pageNum)"
            class="annotation-overlay"
            :style="layerStyle(page)"
            :class="{
              'formula-mode': currentTool === 'formula'
            }"
            @pointerdown="beginFormulaRegionSelection($event, page)"
            @pointermove="onOverlayPointerMove"
            @pointerup="onOverlayPointerUp"
            @pointercancel="onOverlayPointerUp"
          >
          <g class="pdf-search-overlay">
            <template v-for="match in searchRectsForPage(page.pageNum)" :key="match.id">
              <rect
                v-for="(rect, rectIndex) in match.rects"
                :key="`${match.id}-${rectIndex}`"
                :x="rect.x * page.width"
                :y="rect.y * page.height"
                :width="rect.width * page.width"
                :height="rect.height * page.height"
                :class="{ current: match.active }"
              />
            </template>
          </g>
          <g v-if="selectionGroupForPage(page.pageNum)" class="text-selection-preview">
            <polygon
              v-for="(q, i) in selectionGroupForPage(page.pageNum).quads"
              :key="`selection-${i}`"
              :points="quadPoints(q, page, viewportCoordinates)"
              fill="#409eff"
              fill-opacity="0.32"
            />
          </g>
          <g v-if="formulaRegionRectForPage(page)" class="formula-region-preview">
            <rect
              :x="formulaRegionRectForPage(page).x"
              :y="formulaRegionRectForPage(page).y"
              :width="formulaRegionRectForPage(page).width"
              :height="formulaRegionRectForPage(page).height"
              rx="3"
            />
          </g>
          <g v-if="evidenceFocusForPage(page.pageNum).length" class="evidence-focus-preview">
            <polygon
              v-for="(quad, focusIndex) in evidenceFocusForPage(page.pageNum)"
              :key="focusIndex"
              :points="quadPoints(quad, page, viewportCoordinates)"
              fill="#ff9800"
              fill-opacity="0.18"
              stroke="#ff9800"
              stroke-width="2"
              stroke-dasharray="6 3"
            />
          </g>
          <g v-for="ann in pageAnnotations(page.pageNum)" :key="ann.localId"
            @pointerdown.stop="beginAnnotationPointerDown($event, ann)"
            @click.stop="onAnnotationClick(ann)"
            :class="{
              selected: selectedAnnotation?.localId === ann.localId,
              'marker-annotation': isMarkerAnnotation(ann),
              'text-annotation': isResizableAnnotation(ann),
              completed: isCommentAnnotation(ann) && ann.completed
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
              v-if="selectedAnnotation?.localId === ann.localId && isResizableAnnotation(ann)"
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
            <g
              v-if="selectedAnnotation?.localId === ann.localId && isResizableAnnotation(ann)"
              class="annotation-delete-control"
              :transform="annotationDeleteTransform(ann, page)"
              role="button"
              aria-label="删除标注"
              @pointerdown.stop
              @click.stop="deleteAnnotationImmediately(ann)"
            >
              <circle r="9" />
              <text y="4" text-anchor="middle">×</text>
            </g>
            <g v-if="ann.type === 'FREEHAND'">
              <polyline
                :points="freehandPoints(ann.coordinates, page)"
                :stroke="ann.color || '#f44336'"
                fill="none"
                stroke-width="2"
              />
            </g>
            <g v-if="isMarkerAnnotation(ann)">
              <polygon
                v-for="(q, i) in isSelectionNote(ann) ? ann.coordinates?.anchorQuads : []"
                :key="`anchor-${i}`"
                class="note-anchor-outline"
                :points="quadPoints(q, page, ann.coordinates)"
                :stroke="annotationDisplayColor(ann)"
              />
              <line
                v-if="markerAnchorPoint(ann, page)"
                class="note-leader"
                :x1="markerAnchorPoint(ann, page).x"
                :y1="markerAnchorPoint(ann, page).y"
                :x2="notePoint(ann.coordinates, page).x"
                :y2="notePoint(ann.coordinates, page).y - 10"
                :stroke="annotationDisplayColor(ann)"
              />
              <rect
                v-if="isSelectionNote(ann)"
                class="note-marker"
                :x="notePoint(ann.coordinates, page).x - 10"
                :y="notePoint(ann.coordinates, page).y - 20"
                width="20"
                height="20"
                rx="4"
                :fill="annotationDisplayColor(ann)"
              ><title>{{ ann.note || '笔记' }}</title></rect>
              <text
                v-if="isSelectionNote(ann)"
                class="note-marker-label"
                :x="notePoint(ann.coordinates, page).x"
                :y="notePoint(ann.coordinates, page).y - 6"
                text-anchor="middle"
              >N</text>
              <path
                v-if="isCommentAnnotation(ann)"
                class="comment-marker"
                :d="commentMarkerPath(notePoint(ann.coordinates, page))"
                :fill="annotationDisplayColor(ann)"
              ><title>{{ ann.note || '批注' }}</title></path>
              <g v-if="isCommentAnnotation(ann)" class="comment-marker-dots" aria-hidden="true">
                <circle :cx="notePoint(ann.coordinates, page).x - 4" :cy="notePoint(ann.coordinates, page).y - 12" r="1.2" />
                <circle :cx="notePoint(ann.coordinates, page).x" :cy="notePoint(ann.coordinates, page).y - 12" r="1.2" />
                <circle :cx="notePoint(ann.coordinates, page).x + 4" :cy="notePoint(ann.coordinates, page).y - 12" r="1.2" />
              </g>
            </g>
          </g>
          </svg>
          <div
            v-if="notePreview?.page === page.pageNum"
            class="note-content-popover"
            :style="notePreviewStyle(notePreview, page)"
            @click.stop
          >
            <div class="note-content-popover__text">{{ notePreview.note || '（空内容）' }}</div>
            <div class="note-content-popover__actions">
              <span v-if="isCommentAnnotation(notePreview) && notePreview.completed" class="completed-label">已完成</span>
              <el-button link type="primary" size="small" @click="openAnnotationEditor(notePreview)">编辑</el-button>
              <el-button link type="danger" size="small" @click="deleteAnnotationImmediately(notePreview)">删除</el-button>
            </div>
          </div>
        </div>
        <div class="virtual-spacer" :style="{ height: bottomSpacerHeight + 'px' }" aria-hidden="true"></div>
      </div>

      <aside
        v-if="commentPanelVisible"
        class="pdf-comment-panel"
        :style="{ flexBasis: commentPanelWidth + 'px' }"
        aria-label="批注列表"
      >
        <header class="side-panel-header">
          <strong>批注</strong>
          <button type="button" aria-label="关闭批注列表" title="关闭批注列表" @click="closeCommentPanel">×</button>
        </header>
        <div class="pdf-comment-list">
          <article
            v-for="annotation in panelComments"
            :key="annotation.localId"
            class="pdf-comment-card"
            :class="{
              active: selectedAnnotation?.localId === annotation.localId,
              completed: annotation.completed
            }"
          >
            <button type="button" class="pdf-comment-card__body" @click="jumpToPanelComment(annotation)">
              <span>第 {{ annotation.page }} 页</span>
              <p>{{ annotation.note || '未填写内容' }}</p>
            </button>
            <div class="pdf-comment-card__actions">
              <button
                type="button"
                :disabled="annotation.completed"
                @click="completePanelComment(annotation)"
              >{{ annotation.completed ? '已完成' : '完成' }}</button>
              <button type="button" class="danger" @click="deleteAnnotationImmediately(annotation)">删除</button>
            </div>
          </article>
          <div v-if="!panelComments.length" class="side-panel-empty">暂无批注</div>
        </div>
      </aside>

      <div
        v-if="workbenchPanelVisible"
        class="workbench-divider"
        role="separator"
        aria-label="调整 PDF 与论文助手宽度"
        aria-orientation="vertical"
        :aria-valuemin="20"
        :aria-valuemax="65"
        :aria-valuenow="workbenchRatioPercent"
        tabindex="0"
        title="拖动调整宽度；双击恢复默认"
        @pointerdown="beginWorkbenchResize"
        @pointermove="continueWorkbenchResize"
        @pointerup="finishWorkbenchResize"
        @pointercancel="finishWorkbenchResize"
        @dblclick="resetWorkbenchWidth"
        @keydown="onWorkbenchDividerKeydown"
      ><span aria-hidden="true" /></div>

      <PaperWorkbenchPanel
        v-if="workbenchPanelVisible"
        :style="{ flexBasis: assistantPanelWidth + 'px' }"
        :paper="paper"
        :selection="pendingTextSelection"
        :selection-anchor="selectionAnchor"
        :selection-loading="selectionContextLoading"
        :selection-error="selectionContextError"
        :formula-region="formulaRegion"
        :formula-recognition="formulaRecognition"
        :formula-preview-data-url="formulaPreviewDataUrl"
        :formula-loading="formulaRecognitionLoading"
        :formula-confirming="formulaConfirming"
        :formula-error="formulaRecognitionError"
        :capture-mode="workbenchCaptureMode"
        :research-session-id="researchSessionId"
        @clear-selection="clearPendingTextSelection"
        @clear-formula="clearFormulaAndContinueCapture"
        @retry-formula="recognizeCurrentFormulaRegion"
        @confirm-formula="confirmCurrentFormulaRegion"
        @capture-mode-change="selectWorkbenchCaptureMode"
        @jump-evidence="jumpToEvidence"
        @execute-actions="executeAgentActions"
        @add-comparison-paper="openComparisonPaperInterface"
        @research-session-change="$emit('research-session-change', $event)"
      />

    </div>

    <!-- 选区笔记与选区批注共用保存链路，但使用不同语义和图标。 -->
    <el-dialog v-model="noteDialogVisible" :title="noteDialogTitle" width="420px" @closed="noteEditTarget = null">
      <div v-if="noteDialogHasSelectionAnchor" class="selection-comment-anchor">
        <span>关联原文</span>
        <p>{{ noteDialogAnchorText }}</p>
      </div>
      <el-input
        v-model="noteEditText"
        type="textarea"
        :rows="4"
        maxlength="4000"
        show-word-limit
        :placeholder="noteDialogIsSelectionNote ? '填写对所选文本的笔记…' : '输入批注内容…'"
      />
      <template #footer>
        <el-button @click="noteDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="noteSaving" @click="confirmNote">确定</el-button>
      </template>
    </el-dialog>

    <!-- 已保存批注的编辑弹窗 -->
    <el-dialog
      v-model="annotationEditorVisible"
      :title="annotationEditorTitle"
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
      <div v-if="isMarkerAnnotation(annotationEditorTarget)" class="annotation-editor-field">
        <span>内容</span>
        <el-input v-model="annotationEditorText" type="textarea" :rows="4" maxlength="4000" show-word-limit />
      </div>
      <template #footer>
        <el-button type="danger" @click="deleteAnnotationFromEditor">删除</el-button>
        <el-button @click="annotationEditorVisible = false">取消</el-button>
        <el-button type="primary" :loading="annotationEditorSaving" @click="saveAnnotationEditor">保存修改</el-button>
      </template>
    </el-dialog>

    <!-- 右键菜单 -->
    <div
      v-if="contextMenu.visible"
      class="context-menu"
      :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
      @click.stop
    >
      <div class="context-menu-item" @click="openSelectionNoteFromContext">📝 添加笔记</div>
      <div class="context-menu-item" @click="contextMenu.visible = false">取消</div>
    </div>
  </div>
</template>

<script setup>
import { ref, shallowRef, computed, onMounted, onUnmounted, onActivated, onDeactivated, nextTick } from 'vue'
import * as pdfjsLib from 'pdfjs-dist'
import pdfjsWorkerUrl from 'pdfjs-dist/build/pdf.worker.mjs?url'
import {
  listAnnotations,
  createAnnotation,
  createAgentAnnotation,
  updateAnnotation,
  deleteAnnotation,
} from '@/api/annotation'
import PaperWorkbenchPanel from '@/components/pdf/PaperWorkbenchPanel.vue'
import {
  annotationDisplayColor,
  buildSelectionCommentDraft,
  buildSelectionNoteDraft,
  isMarkerAnnotation,
  isCommentAnnotation,
  isSelectionNote,
  resizeTextAnnotationQuads,
} from '@/utils/pdfAnnotation.js'
import {
  boundingBoxToViewportQuad,
  cloneSelectionTextAnchor,
  selectionToAnchorPayload,
} from '@/utils/pdfSelectionAnchor.js'
import {
  createFormulaRegionPreview,
  formulaRegionSvgRect,
  normalizedFormulaRegion,
} from '@/utils/formulaRegionSelection.js'
import {
  confirmFormulaRegion,
  recognizeFormulaRegion,
  resolveSelectionAnchor,
} from '@/api/workbench.js'
import {
  DEFAULT_WORKBENCH_RATIO,
  DEFAULT_COMMENT_PANEL_WIDTH,
  completePdfPaneWidth,
  normalizeWorkbenchRatio,
  ratioFromDividerPosition,
  readWorkbenchRatio,
  splitWorkbenchAllocation,
  workbenchWidthForContainer,
  writeWorkbenchRatio,
} from '@/utils/pdfWorkspaceLayout.js'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createPdfInteractionEngine } from '@/services/pdfiumInteractionEngine.js'
import { segmentPdfSelection } from '@/utils/pdfContentSegments.js'
import { normalizePdfSelectionText } from '@/utils/pdfSelectionText.js'
import { isTextSelectionDrag } from '@/utils/pdfTextSelection.js'
import {
  invalidatePageRenderSurface,
  pageRenderSurfaceIsUsable,
} from '@/utils/pdfRenderLifecycle.js'

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfjsWorkerUrl

const props = defineProps({
  paper: { type: Object, required: true },
  initialEvidence: { type: Object, default: null },
  researchSessionId: { type: Number, default: null },
  initialPage: { type: Number, default: 1 },
})

const emit = defineEmits([
  'close', 'open-paper-evidence', 'research-session-change', 'page-change',
])

const containerRef = ref(null)
const viewerBodyRef = ref(null)
const searchInputRef = ref(null)
const canvasRefs = ref({})
const textLayerRefs = ref({})
const overlayRefs = ref({})
// PDF.js 的文档对象包含私有字段，不能被 Vue 深层代理，否则调用 getPage/render
// 时会报 "Cannot read from private field"。
const pdfDoc = shallowRef(null)
let pdfInteractionEngine = null
const pdfInteractionReady = ref(false)
const pdfLoadError = ref('')
const renderedPages = ref([])
const visiblePageStart = ref(1)
const visiblePageEnd = ref(1)
const currentPage = ref(1)
const baseEstimatedPageHeight = 900
const zoomPercent = ref(100)
const renderedZoomPercent = ref(100)
const zoomOptions = [50, 100, 125, 150, 200]
const zoomMenuVisible = ref(false)
const estimatedPageHeight = computed(() => baseEstimatedPageHeight * zoomPercent.value / 100)
const annotations = ref([])
const selectedAnnotation = ref(null)
const currentTool = ref('select')
const currentColor = ref('#f44336')
const colorMenuVisible = ref(false)
const searchPanelVisible = ref(false)
const searchQuery = ref('')
const searchResults = ref([])
const activeSearchResultIndex = ref(-1)
const searchIndexLoading = ref(false)
const searchStatusText = computed(() => {
  if (!searchQuery.value.trim()) return ''
  if (searchIndexLoading.value) return '正在搜索…'
  if (!searchResults.value.length) return '未找到匹配内容'
  return `${Math.max(1, activeSearchResultIndex.value + 1)} / ${searchResults.value.length}`
})
const commentPanelVisible = ref(false)
const pendingTextSelection = ref(null)
const selectionAnchor = ref(null)
const selectionContextLoading = ref(false)
const selectionContextError = ref('')
const formulaRegion = ref(null)
const formulaRecognition = ref(null)
const formulaPreviewDataUrl = ref('')
const formulaRecognitionLoading = ref(false)
const formulaConfirming = ref(false)
const formulaRecognitionError = ref('')
const workbenchCaptureMode = computed(() => (
  currentTool.value === 'formula' || formulaRegion.value ? 'formula' : 'text'
))
const evidenceFocus = ref(null)
const workbenchPanelVisible = ref(true)
const workbenchWidthRatio = ref(readWorkbenchRatio())
const viewerBodyWidth = ref(0)
const pdfPageWidthAt100 = ref(0)
const pdfViewportReserveWidth = ref(20)
const workbenchResizing = ref(false)
const minimumCompletePdfWidth = computed(() => completePdfPaneWidth(
  pdfPageWidthAt100.value,
  pdfViewportReserveWidth.value,
))
const workbenchWidth = computed(() => workbenchWidthForContainer(
  viewerBodyWidth.value,
  workbenchWidthRatio.value,
  minimumCompletePdfWidth.value,
))
const splitRightPanels = computed(() => splitWorkbenchAllocation(
  workbenchWidth.value,
  commentPanelVisible.value && workbenchPanelVisible.value,
))
const commentPanelWidth = computed(() => {
  if (!commentPanelVisible.value) return 0
  return workbenchPanelVisible.value
    ? splitRightPanels.value.commentWidth
    : DEFAULT_COMMENT_PANEL_WIDTH
})
const assistantPanelWidth = computed(() => splitRightPanels.value.assistantWidth)
const workbenchRatioPercent = computed(() => viewerBodyWidth.value > 0
  ? Math.round(workbenchWidth.value / viewerBodyWidth.value * 100)
  : Math.round(workbenchWidthRatio.value * 100))
let viewerBodyResizeObserver = null
let viewerEventsAttached = false
let viewerActive = true
let activationSequence = 0
let documentLoadSequence = 0
let pdfLoadingTask = null

const noteDialogVisible = ref(false)
const noteEditText = ref('')
const noteEditTarget = ref(null)
const noteSaving = ref(false)
const notePreview = ref(null)
const noteDialogIsSelectionNote = computed(() => isSelectionNote(noteEditTarget.value))
const noteDialogTitle = computed(() => noteDialogIsSelectionNote.value ? '添加笔记' : '添加批注')
const noteDialogAnchorText = computed(() => noteEditTarget.value?.coordinates?.anchorText || '')
const noteDialogHasSelectionAnchor = computed(() => Boolean(noteDialogAnchorText.value))
const annotationEditorVisible = ref(false)
const annotationEditorTarget = ref(null)
const annotationEditorText = ref('')
const annotationEditorColor = ref('#ffeb3b')
const annotationEditorSaving = ref(false)
const annotationEditorTitle = computed(() => (
  isSelectionNote(annotationEditorTarget.value) ? '编辑笔记' : '编辑批注'
))

const contextMenu = ref({ visible: false, x: 0, y: 0 })

const annotationColors = ['#f44336', '#ffeb3b', '#2196f3', '#4caf50', '#000000']
const viewportCoordinates = Object.freeze({ coordinateSpace: 'viewport' })

let nextLocalId = 1
let draggingNote = null
let resizingAnnotation = null
let suppressAnnotationClickId = null
let layoutSelectionDrag = null
let formulaRegionDrag = null
let selectionContextRequestId = 0
let searchDebounceTimer = null
let searchRequestId = 0
let evidenceFocusTimer = null

const pageAnnotations = computed(() => (pageNum) => annotations.value.filter(annotation => (
  annotation.page === pageNum && (!isCommentAnnotation(annotation) || commentPanelVisible.value)
)))
const panelComments = computed(() => annotations.value.filter(isCommentAnnotation))
const selectionGroupForPage = computed(() => (pageNum) => (
  pendingTextSelection.value?.groups?.find(group => group.pageNum === pageNum) || null
))
const evidenceFocusForPage = computed(() => (pageNum) => (
  evidenceFocus.value?.page === pageNum
    ? evidenceFocus.value.boxes.map(boundingBoxToViewportQuad).filter(Boolean)
    : []
))
function formulaRegionRectForPage(page) {
  if (!formulaRegion.value?.bbox || formulaRegion.value.page !== page?.pageNum) return null
  return formulaRegionSvgRect(formulaRegion.value.bbox, page.width, page.height)
}
const visiblePages = computed(() => renderedPages.value.slice(
  Math.max(0, visiblePageStart.value - 1), visiblePageEnd.value
))
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

function attachViewerEvents() {
  if (viewerEventsAttached) return
  viewerEventsAttached = true
  document.documentElement.classList.add('pdf-viewer-open')
  document.body.classList.add('pdf-viewer-open')
  window.addEventListener('click', onWindowClick)
  window.addEventListener('pointerup', finishTextSelectionFromWindow, true)
  window.addEventListener('pointercancel', cancelTextSelection, true)
}

function detachViewerEvents() {
  if (!viewerEventsAttached) return
  viewerEventsAttached = false
  document.documentElement.classList.remove('pdf-viewer-open')
  document.body.classList.remove('pdf-viewer-open')
  document.documentElement.classList.remove('pdf-workbench-resizing')
  window.removeEventListener('click', onWindowClick)
  window.removeEventListener('pointerup', finishTextSelectionFromWindow, true)
  window.removeEventListener('pointercancel', cancelTextSelection, true)
  workbenchResizing.value = false
  formulaRegionDrag = null
}

function updateViewerBodyWidth() {
  viewerBodyWidth.value = viewerBodyRef.value?.getBoundingClientRect?.().width || 0
  const container = containerRef.value
  if (container) {
    pdfViewportReserveWidth.value = Math.max(20, container.offsetWidth - container.clientWidth + 4)
  }
}

onMounted(() => {
  viewerActive = true
  attachViewerEvents()
  if (typeof ResizeObserver !== 'undefined') {
    viewerBodyResizeObserver = new ResizeObserver(updateViewerBodyWidth)
    if (viewerBodyRef.value) viewerBodyResizeObserver.observe(viewerBodyRef.value)
  }
  updateViewerBodyWidth()
  loadDocument()
})
onActivated(() => {
  viewerActive = true
  const sequence = ++activationSequence
  attachViewerEvents()
  void resumeViewerAfterActivation(sequence)
})
onDeactivated(() => {
  viewerActive = false
  activationSequence += 1
  renderQueueRequested = false
  if (renderFrame != null) {
    window.cancelAnimationFrame(renderFrame)
    renderFrame = null
  }
  cancelAllPageRenders()
  detachViewerEvents()
})
onUnmounted(() => {
  viewerActive = false
  activationSequence += 1
  documentLoadSequence += 1
  renderQueueRequested = false
  if (renderFrame != null) window.cancelAnimationFrame(renderFrame)
  if (searchDebounceTimer != null) window.clearTimeout(searchDebounceTimer)
  cancelAllPageRenders()
  void pdfLoadingTask?.destroy?.()
  pdfLoadingTask = null
  pdfDoc.value?.destroy()
  void pdfInteractionEngine?.close?.()
  pdfInteractionEngine = null
  pdfInteractionReady.value = false
  if (evidenceFocusTimer != null) window.clearTimeout(evidenceFocusTimer)
  viewerBodyResizeObserver?.disconnect()
  viewerBodyResizeObserver = null
  detachViewerEvents()
})

async function resumeViewerAfterActivation(sequence) {
  for (let attempt = 0; attempt < 4; attempt += 1) {
    await nextTick()
    if (!viewerActive || sequence !== activationSequence) return
    const container = containerRef.value
    if (container?.clientWidth > 0 && container?.clientHeight > 0) break
    await new Promise(resolve => window.requestAnimationFrame(resolve))
  }
  if (!viewerActive || sequence !== activationSequence) return
  updateViewerBodyWidth()
  if (!pdfDoc.value) {
    if (pdfLoadError.value) void loadDocument()
    return
  }
  updateVisiblePageRange()
  cancelAllPageRenders()
  const dpr = window.devicePixelRatio || 1
  for (const page of visiblePages.value) {
    const canvas = canvasRefs.value[page.pageNum]
    const textLayer = textLayerRefs.value[page.pageNum]
    // Repaint every visible surface after KeepAlive activation. Canvas bitmap
    // storage may be discarded while its DOM dimensions remain unchanged.
    if (pageRenderSurfaceIsUsable(page, canvas, textLayer, dpr) || page.rendered) {
      invalidatePageRenderSurface(page)
    }
  }
  await nextTick()
  if (viewerActive && sequence === activationSequence) await renderVisiblePages()
}

function beginWorkbenchResize(event) {
  if (event.button !== 0) return
  event.preventDefault()
  workbenchResizing.value = true
  document.documentElement.classList.add('pdf-workbench-resizing')
  try { event.currentTarget?.setPointerCapture?.(event.pointerId) } catch { /* synthetic/legacy pointer */ }
  updateWorkbenchWidthFromPointer(event)
}

function continueWorkbenchResize(event) {
  if (!workbenchResizing.value) return
  updateWorkbenchWidthFromPointer(event)
}

function finishWorkbenchResize(event) {
  if (!workbenchResizing.value) return
  updateWorkbenchWidthFromPointer(event)
  workbenchResizing.value = false
  document.documentElement.classList.remove('pdf-workbench-resizing')
  try { event.currentTarget?.releasePointerCapture?.(event.pointerId) } catch { /* capture already released */ }
  workbenchWidthRatio.value = writeWorkbenchRatio(workbenchWidthRatio.value)
}

function updateWorkbenchWidthFromPointer(event) {
  const rect = viewerBodyRef.value?.getBoundingClientRect?.()
  if (!rect?.width) return
  workbenchWidthRatio.value = ratioFromDividerPosition(event.clientX, rect)
}

function resetWorkbenchWidth() {
  workbenchWidthRatio.value = writeWorkbenchRatio(DEFAULT_WORKBENCH_RATIO)
}

function onWorkbenchDividerKeydown(event) {
  let next = workbenchWidthRatio.value
  if (event.key === 'ArrowLeft') next += event.shiftKey ? 0.05 : 0.02
  else if (event.key === 'ArrowRight') next -= event.shiftKey ? 0.05 : 0.02
  else if (event.key === 'Home') next = DEFAULT_WORKBENCH_RATIO
  else return
  event.preventDefault()
  workbenchWidthRatio.value = writeWorkbenchRatio(normalizeWorkbenchRatio(next))
}

function toggleSearchPanel() {
  if (searchPanelVisible.value) {
    closeSearchPanel()
    return
  }
  searchPanelVisible.value = true
  void nextTick(() => {
    searchInputRef.value?.focus?.()
    if (searchQuery.value.trim()) schedulePdfSearch()
  })
}

function closeSearchPanel() {
  searchPanelVisible.value = false
  searchRequestId += 1
  searchResults.value = []
  activeSearchResultIndex.value = -1
}

function clearPdfSearch() {
  searchQuery.value = ''
  searchRequestId += 1
  searchResults.value = []
  activeSearchResultIndex.value = -1
  searchInputRef.value?.focus?.()
}

function schedulePdfSearch() {
  if (searchDebounceTimer != null) window.clearTimeout(searchDebounceTimer)
  searchDebounceTimer = window.setTimeout(() => {
    searchDebounceTimer = null
    void performPdfSearch()
  }, 160)
}

async function performPdfSearch() {
  const query = searchQuery.value.trim()
  const requestId = ++searchRequestId
  if (!query) {
    searchResults.value = []
    activeSearchResultIndex.value = -1
    return
  }
  if (!pdfInteractionReady.value || !pdfInteractionEngine) {
    searchResults.value = []
    activeSearchResultIndex.value = -1
    return
  }
  searchIndexLoading.value = true
  let matches = []
  try {
    matches = await pdfInteractionEngine.search(query)
  } catch (error) {
    if (requestId === searchRequestId) ElMessage.error('PDF 搜索失败：' + (error.message || error))
    return
  } finally {
    if (requestId === searchRequestId) searchIndexLoading.value = false
  }
  if (requestId !== searchRequestId || !searchPanelVisible.value) return
  searchResults.value = matches.map((match, index) => ({
    ...match,
    id: `pdfium-${match.pageIndex}-${match.charStart}-${index}`,
    page: match.pageIndex + 1,
    context: formatPdfiumSearchContext(match.context),
  }))
  activeSearchResultIndex.value = searchResults.value.length ? 0 : -1
  if (searchResults.value.length) await activateSearchResult(0)
}

function formatPdfiumSearchContext(context) {
  if (!context) return ''
  const prefix = context.truncatedLeft ? '…' : ''
  const suffix = context.truncatedRight ? '…' : ''
  return `${prefix}${context.before || ''}${context.match || ''}${context.after || ''}${suffix}`
    .replace(/\s+/g, ' ')
    .trim()
}

function activateNextSearchResult(direction) {
  const count = searchResults.value.length
  if (!count) return
  const current = activeSearchResultIndex.value < 0 ? 0 : activeSearchResultIndex.value
  void activateSearchResult((current + direction + count) % count)
}

async function activateSearchResult(index) {
  const result = searchResults.value[index]
  if (!result) return
  activeSearchResultIndex.value = index
  await goToPage(result.page)
  await nextTick()
  scrollActiveSearchMatchIntoView(result)
}

function searchRectsForPage(pageNum) {
  return searchResults.value
    .map((result, index) => ({ ...result, active: index === activeSearchResultIndex.value }))
    .filter(result => result.page === pageNum && result.rects?.length)
}

function scrollActiveSearchMatchIntoView(result) {
  const container = containerRef.value
  const page = renderedPages.value[result.page - 1]
  const firstRect = result.rects?.[0]
  if (container && page && firstRect) {
    container.scrollTop = Math.max(
      0,
      pageOffset(result.page) + firstRect.y * pageHeight(page) - container.clientHeight * 0.3,
    )
    const targetLeft = firstRect.x * (page.width || 0)
    if (targetLeft < container.scrollLeft || targetLeft > container.scrollLeft + container.clientWidth) {
      container.scrollLeft = Math.max(0, targetLeft - container.clientWidth * 0.25)
    }
    return
  }
}

function selectAnnotationColor(color) {
  currentColor.value = color
  colorMenuVisible.value = false
}

function toggleCommentPanel() {
  if (commentPanelVisible.value) closeCommentPanel()
  else commentPanelVisible.value = true
}

function closeCommentPanel() {
  commentPanelVisible.value = false
  if (isCommentAnnotation(selectedAnnotation.value)) selectedAnnotation.value = null
  if (isCommentAnnotation(notePreview.value)) notePreview.value = null
}

async function jumpToPanelComment(annotation) {
  selectedAnnotation.value = annotation
  notePreview.value = annotation
  await goToPage(annotation.page)
}

async function completePanelComment(annotation) {
  if (!isCommentAnnotation(annotation) || annotation.completed) return
  annotation.completed = true
  try {
    await persistUpdatedAnnotation(annotation)
    ElMessage.success('批注已完成')
  } catch (error) {
    annotation.completed = false
    ElMessage.error('批注状态保存失败：' + requestErrorMessage(error))
  }
}

async function loadDocument() {
  const loadSequence = ++documentLoadSequence
  pdfLoadError.value = ''
  try {
    cancelAllPageRenders()
    renderQueueRequested = false
    const previousDocument = pdfDoc.value
    pdfDoc.value = null
    await previousDocument?.destroy?.()
    searchResults.value = []
    activeSearchResultIndex.value = -1
    pdfPageWidthAt100.value = 0
    const url = `/api/papers/${props.paper.id}/pdf`
    const loading = pdfjsLib.getDocument(url)
    pdfLoadingTask = loading
    await pdfInteractionEngine?.close?.()
    const interactionEngine = createPdfInteractionEngine()
    pdfInteractionEngine = interactionEngine
    pdfInteractionReady.value = false
    const [renderDocument] = await Promise.all([
      loading.promise,
      interactionEngine.open({ id: `paper-${props.paper.id}`, url }),
    ])
    if (loadSequence !== documentLoadSequence) {
      await renderDocument.destroy()
      await interactionEngine.close?.()
      return
    }
    pdfLoadingTask = null
    pdfDoc.value = renderDocument
    pdfInteractionReady.value = true
    const count = pdfDoc.value.numPages
    renderedPages.value = Array.from({ length: count }, (_, i) => ({
      pageNum: i + 1,
      viewport: null,
      width: 0,
      height: 0,
      rendered: false,
      canvasReady: false,
      renderFailed: false,
      interactionFailed: false,
      surfaceVersion: 0
    }))
    const requestedPage = Math.min(count, Math.max(1, Number(props.initialPage) || 1))
    visiblePageStart.value = Math.max(1, requestedPage - 1)
    visiblePageEnd.value = Math.min(count, requestedPage + 1)
    currentPage.value = requestedPage
    await nextTick()
    if (containerRef.value) containerRef.value.scrollTop = pageOffset(requestedPage)
    updateVisiblePageRange()
    if (viewerActive) await renderVisiblePages()
    await loadAnnotations()
    if (props.initialEvidence
      && Number(props.initialEvidence.paperId) === Number(props.paper.id)) {
      await jumpToEvidence(props.initialEvidence)
    }
  } catch (e) {
    if (loadSequence !== documentLoadSequence) return
    pdfLoadingTask = null
    pdfLoadError.value = e.message || String(e)
    ElMessage.error('PDF 加载失败：' + pdfLoadError.value)
  }
}

let renderQueuePromise = null
let renderQueueRequested = false
let renderFrame = null
const activePageRenderTasks = new Map()

async function renderVisiblePages() {
  if (!viewerActive) return false
  renderQueueRequested = true
  if (renderQueuePromise) return renderQueuePromise
  return drainRenderQueue()
}

function scheduleVisiblePageRender() {
  if (!viewerActive) return
  renderQueueRequested = true
  if (renderQueuePromise || renderFrame != null) return
  renderFrame = window.requestAnimationFrame(() => {
    renderFrame = null
    void drainRenderQueue()
  })
}

async function drainRenderQueue() {
  if (renderQueuePromise) return renderQueuePromise

  renderQueuePromise = (async () => {
    try {
      while (renderQueueRequested) {
        renderQueueRequested = false
        if (!viewerActive || !containerRef.value || !pdfDoc.value) break

        updateVisiblePageRange()
        await nextTick()
        const nextPage = pendingVisiblePagesByPriority()[0]
        if (!nextPage) continue

        await renderPage(nextPage)
        if (pendingVisiblePagesByPriority().length) renderQueueRequested = true
      }
    } finally {
      renderQueuePromise = null
      if (viewerActive && renderQueueRequested) scheduleVisiblePageRender()
    }
  })()

  return renderQueuePromise
}

function pendingVisiblePagesByPriority() {
  const container = containerRef.value
  if (!container) return []
  const viewportCenter = container.scrollTop + container.clientHeight / 2

  return visiblePages.value
    .filter(page => !page.rendered && !page.renderFailed && hasMountedRenderSurface(page.pageNum))
    .sort((left, right) => {
      const leftCurrent = left.pageNum === currentPage.value ? 0 : 1
      const rightCurrent = right.pageNum === currentPage.value ? 0 : 1
      if (leftCurrent !== rightCurrent) return leftCurrent - rightCurrent
      const leftDistance = Math.abs(pageOffset(left.pageNum) + pageHeight(left) / 2 - viewportCenter)
      const rightDistance = Math.abs(pageOffset(right.pageNum) + pageHeight(right) / 2 - viewportCenter)
      return leftDistance - rightDistance || left.pageNum - right.pageNum
    })
}

function hasMountedRenderSurface(pageNum) {
  const canvas = canvasRefs.value[pageNum]
  const textLayer = textLayerRefs.value[pageNum]
  return Boolean(canvas?.isConnected && textLayer?.isConnected)
}

function isRenderSurfaceCurrent(pageState, canvas, textLayer, surfaceVersion, documentRef) {
  return pdfDoc.value === documentRef
    && pageState.surfaceVersion === surfaceVersion
    && canvasRefs.value[pageState.pageNum] === canvas
    && textLayerRefs.value[pageState.pageNum] === textLayer
    && canvas.isConnected
    && textLayer.isConnected
}

function cancelStalePageRenders() {
  const desired = new Set(visiblePages.value.map(page => page.pageNum))
  for (const [pageNum, task] of activePageRenderTasks) {
    if (!desired.has(pageNum)) task.cancel()
  }
}

function cancelAllPageRenders() {
  for (const task of activePageRenderTasks.values()) task.cancel()
  activePageRenderTasks.clear()
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
  updateCurrentPage()
}

function updateCurrentPage() {
  const container = containerRef.value
  const count = renderedPages.value.length
  if (!container || !count) return

  // The page occupying the upper quarter of the viewport is less jumpy than
  // using a single pixel at the page boundary, while still matching what the
  // reader is actually looking at.
  const focusOffset = container.scrollTop + Math.min(container.clientHeight * 0.25, 180)
  let cursor = 0
  for (let index = 0; index < count; index += 1) {
    const pageBottom = cursor + pageHeight(renderedPages.value[index])
    if (focusOffset <= pageBottom + 16 || index === count - 1) {
      const nextPage = index + 1
      if (currentPage.value !== nextPage) {
        currentPage.value = nextPage
        emit('page-change', nextPage)
      }
      return
    }
    cursor = pageBottom + 16
  }
}

async function goToPage(requestedPage = currentPage.value) {
  const container = containerRef.value
  const count = renderedPages.value.length
  if (!container || !count) return

  const numericPage = Number(requestedPage)
  const targetPage = Math.min(count, Math.max(1, Number.isFinite(numericPage) ? Math.round(numericPage) : currentPage.value || 1))
  currentPage.value = targetPage
  emit('page-change', targetPage)

  // Materialise a small local window before changing scrollTop. The virtual
  // spacer keeps the position exact even for pages that have not been painted
  // yet, so direct jumping does not force the full document to render.
  visiblePageStart.value = Math.max(1, targetPage - 1)
  visiblePageEnd.value = Math.min(count, targetPage + 1)
  await nextTick()
  const mountedPage = container.querySelector(`[data-page="${targetPage}"]`)
  container.scrollTop = Math.max(0, mountedPage?.offsetTop ?? pageOffset(targetPage))
  updateVisiblePageRange()
  cancelStalePageRenders()
  await renderVisiblePages()
}

defineExpose({ goToPage })

async function renderPage(pageState) {
  const canvas = canvasRefs.value[pageState.pageNum]
  const textLayer = textLayerRefs.value[pageState.pageNum]
  const documentRef = pdfDoc.value
  if (!viewerActive || !canvas || !textLayer || !documentRef
      || !hasMountedRenderSurface(pageState.pageNum)) return false
  const surfaceVersion = pageState.surfaceVersion

  let page
  try {
    page = await documentRef.getPage(pageState.pageNum)
  } catch (e) {
    pageState.renderFailed = true
    return false
  }
  if (!isRenderSurfaceCurrent(pageState, canvas, textLayer, surfaceVersion, documentRef)) return false

  const dpr = window.devicePixelRatio || 1
  const baseViewport = page.getViewport({ scale: 1.5 * zoomPercent.value / 100 })
  const viewport = baseViewport
    pageState.viewport = viewport
    pageState.width = viewport.width
    pageState.height = viewport.height
    pdfPageWidthAt100.value = Math.max(
      pdfPageWidthAt100.value,
      viewport.width * 100 / zoomPercent.value,
    )

  canvas.width = Math.floor(viewport.width * dpr)
  canvas.height = Math.floor(viewport.height * dpr)
  canvas.style.width = viewport.width + 'px'
  canvas.style.height = viewport.height + 'px'
  const ctx = canvas.getContext('2d')
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)

  const renderTask = page.render({ canvasContext: ctx, viewport })
  activePageRenderTasks.set(pageState.pageNum, renderTask)
  try {
    await renderTask.promise
  } catch (e) {
    if (e?.name !== 'RenderingCancelledException') pageState.renderFailed = true
    return false
  } finally {
    if (activePageRenderTasks.get(pageState.pageNum) === renderTask) {
      activePageRenderTasks.delete(pageState.pageNum)
    }
  }
  if (!isRenderSurfaceCurrent(pageState, canvas, textLayer, surfaceVersion, documentRef)) return false
  pageState.canvasReady = true
  // Materialize PDFium glyph geometry before exposing the interaction surface,
  // so the first pointer-down does not race an uncached page extraction.
  try {
    await pdfInteractionEngine?.getPage(pageState.pageNum - 1)
  } catch (error) {
    // The visual PDF.js canvas is already valid. Keep it visible and allow a
    // later activation/retry to recover only the interaction geometry.
    pageState.interactionFailed = true
    pageState.rendered = true
    return true
  }
  if (!isRenderSurfaceCurrent(pageState, canvas, textLayer, surfaceVersion, documentRef)) return false
  pageState.interactionFailed = false

  textLayer.replaceChildren()
  textLayer.style.width = viewport.width + 'px'
  textLayer.style.height = viewport.height + 'px'
  textLayer.dataset.interactionEngine = 'pdfium'
  if (!isRenderSurfaceCurrent(pageState, canvas, textLayer, surfaceVersion, documentRef)) return false
  pageState.rendered = true
  return true
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
    height: page.height ? page.height + 'px' : '100%',
  }
}

function setCanvasRef(el, pageNum) {
  if (!el) return releasePageSurfaceRef(canvasRefs, pageNum)
  if (canvasRefs.value[pageNum] === el) return
  canvasRefs.value[pageNum] = el
  invalidatePageSurface(pageNum)
  scheduleVisiblePageRender()
}

function setTextLayerRef(el, pageNum) {
  if (!el) return releasePageSurfaceRef(textLayerRefs, pageNum)
  if (textLayerRefs.value[pageNum] === el) return
  textLayerRefs.value[pageNum] = el
  invalidatePageSurface(pageNum)
  scheduleVisiblePageRender()
}

function setOverlayRef(el, pageNum) {
  if (el) overlayRefs.value[pageNum] = el
  else releasePageSurfaceRef(overlayRefs, pageNum, false)
}

function releasePageSurfaceRef(refs, pageNum, invalidate = true) {
  const surface = refs.value[pageNum]
  if (!surface) return
  queueMicrotask(() => {
    // Function refs run on every Vue update. Only clear the map after the old
    // element is genuinely detached, not when its callback identity changes.
    if (refs.value[pageNum] !== surface || surface.isConnected) return
    delete refs.value[pageNum]
    if (invalidate) invalidatePageSurface(pageNum)
  })
}

function invalidatePageSurface(pageNum) {
  const pageState = renderedPages.value[pageNum - 1]
  if (!pageState) return
  invalidatePageRenderSurface(pageState)
  activePageRenderTasks.get(pageNum)?.cancel()
}

function onScroll() {
  updateVisiblePageRange()
  cancelStalePageRenders()
  scheduleVisiblePageRender()
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

function selectZoomOption(value) {
  zoomMenuVisible.value = false
  void setZoom(value)
}

async function renderAtCurrentZoom() {
  if (!pdfDoc.value || renderedZoomPercent.value === zoomPercent.value) return
  const container = containerRef.value
  const ratio = zoomPercent.value / renderedZoomPercent.value
  const previousTop = container?.scrollTop || 0
  clearPendingTextSelection()
  cancelAllPageRenders()
  renderQueueRequested = false

  for (const page of renderedPages.value) {
    page.surfaceVersion += 1
    page.rendered = false
    page.canvasReady = false
    page.renderFailed = false
    page.interactionFailed = false
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

async function beginTextSelection(event, pageNum) {
  if (currentTool.value !== 'select' || event.button !== 0) return

  if (formulaRegion.value && !formulaRecognition.value?.confirmed) clearFormulaRegion()
  // A plain click—on text or page whitespace—clears only the transient blue
  // selection. A new selection is created only after an intentional drag.
  clearPendingTextSelection()
  const layer = event.currentTarget
  event.preventDefault()
  const anchor = await pdfiumEndpointAtPoint(pageNum, layer, event.clientX, event.clientY)
  if (!anchor || currentTool.value !== 'select') return

  const pageState = renderedPages.value.find(page => page.pageNum === pageNum)
  if (!pageState?.viewport) return
  layoutSelectionDrag = {
    pageNum,
    pageState,
    pointerId: event.pointerId,
    layer,
    anchor: anchor.charIndex,
    focus: anchor.charIndex,
    startPoint: { x: event.clientX, y: event.clientY },
    moved: false,
    revision: 0,
  }
  layer.setPointerCapture?.(event.pointerId)
}

async function updateTextSelection(event, pageNum) {
  const drag = layoutSelectionDrag
  if (!drag || drag.pointerId !== event.pointerId || drag.pageNum !== pageNum || currentTool.value !== 'select') return

  event.preventDefault()
  if (!drag.moved) {
    drag.moved = isTextSelectionDrag(
      drag.startPoint,
      { x: event.clientX, y: event.clientY },
    )
  }
  if (!drag.moved) return
  const revision = ++drag.revision
  const focus = await pdfiumEndpointAtPoint(pageNum, drag.layer, event.clientX, event.clientY)
  if (!focus) return
  const selection = await pdfInteractionEngine.select(pageNum - 1, drag.anchor, focus.charIndex)
  if (layoutSelectionDrag !== drag || revision !== drag.revision) return
  drag.focus = focus.charIndex
  applyPdfiumSelection(drag, selection)
}

async function finishTextSelection(event, pageNum) {
  const drag = layoutSelectionDrag
  if (!drag || drag.pointerId !== event.pointerId || drag.pageNum !== pageNum) return

  await updateTextSelection(event, pageNum)
  if (layoutSelectionDrag !== drag) return
  drag.layer.releasePointerCapture?.(event.pointerId)
  layoutSelectionDrag = null
  if (!drag.moved) return
  const selection = pendingTextSelection.value
  if (selection?.groups?.length) {
    if (formulaRecognition.value?.confirmed) {
      const replace = await confirmFormulaReplacement('新选取的文字')
      if (!replace) {
        clearPendingTextSelection()
        return
      }
      clearFormulaRegion()
    }
    workbenchPanelVisible.value = true
    void resolvePendingSelectionContext(selection)
  }
}

function finishTextSelectionFromWindow(event) {
  const drag = layoutSelectionDrag
  if (!drag || drag.pointerId !== event.pointerId) return
  finishTextSelection(event, drag.pageNum)
}

function cancelTextSelection(event) {
  if (layoutSelectionDrag?.pointerId === event.pointerId) layoutSelectionDrag = null
}

async function pdfiumEndpointAtPoint(pageNum, layer, clientX, clientY) {
  if (!pdfInteractionReady.value || !pdfInteractionEngine || !layer) return null
  const rect = layer.getBoundingClientRect()
  if (!rect.width || !rect.height) return null
  const x = (clientX - rect.left) / rect.width
  const y = (clientY - rect.top) / rect.height
  if (x < 0 || x > 1 || y < 0 || y > 1) return null
  return pdfInteractionEngine.hitTest(pageNum - 1, { x, y })
}

function applyPdfiumSelection(drag, selection) {
  const quads = (selection.rects || []).map(boundingBoxToViewportQuad).filter(Boolean)
  if (!quads.length || !selection.text?.trim()) return
  const contentSegments = segmentPdfSelection(selection.runs, selection.pageSize)
  const normalizedText = normalizePdfSelectionText(selection.text)
  if (!normalizedText.readableText) return
  selectionContextRequestId += 1
  selectionAnchor.value = null
  selectionContextLoading.value = false
  selectionContextError.value = ''
  pendingTextSelection.value = {
    groups: [{ pageNum: drag.pageNum, pageState: drag.pageState, quads }],
    text: normalizedText.readableText,
    rawText: normalizedText.rawText,
    textNormalization: {
      hadVisualLineBreaks: normalizedText.hadVisualLineBreaks,
      hasExtractionIssues: normalizedText.hasExtractionIssues,
      removedCharacterCount: normalizedText.removedCharacterCount,
    },
    contentSegments,
    textAnchor: {
      version: 2,
      engine: 'PDFIUM',
      page: drag.pageNum,
      charStart: selection.charStart,
      charEnd: selection.charEnd,
      documentFingerprint: pdfDoc.value?.fingerprints?.[0] || '',
      contentSegments,
    },
  }
}

async function resolvePendingSelectionContext(selection) {
  const payload = selectionToAnchorPayload(selection)
  const requestId = ++selectionContextRequestId
  selectionAnchor.value = null
  selectionContextError.value = ''
  evidenceFocus.value = null
  if (!payload) {
    selectionContextError.value = '选区坐标无效，请重新选择'
    return
  }

  selectionContextLoading.value = true
  try {
    const anchor = await resolveSelectionAnchor(props.paper.id, payload)
    if (requestId !== selectionContextRequestId) return
    selectionAnchor.value = anchor
  } catch (error) {
    if (requestId !== selectionContextRequestId) return
    selectionContextError.value = error.response?.data?.message || error.message || '证据锚点建立失败'
  } finally {
    if (requestId === selectionContextRequestId) selectionContextLoading.value = false
  }
}

function openComparisonPaperInterface() {
  ElMessage.info('对比文献接口已预留，后续将在当前对话中添加文献')
}

async function jumpToEvidence(item) {
  const targetBox = item?.locator?.targetBbox || item?.bbox
  if (!item?.page || !targetBox) return
  if (Number(item.paperId) !== Number(props.paper.id)) {
    emit('open-paper-evidence', item)
    return
  }
  await goToPage(item.page)
  const exactBoxes = await locateEvidenceText(item)
  const focusBoxes = exactBoxes.length ? exactBoxes : [targetBox]
  evidenceFocus.value = {
    page: item.page,
    boxes: focusBoxes,
    precision: exactBoxes.length ? 'TEXT' : 'BLOCK',
  }
  await nextTick()
  scrollEvidenceIntoView(item.page, focusBoxes)
  if (!exactBoxes.length && item?.locator?.precision !== 'FORMULA_REGION') {
    ElMessage.info('已定位到来源段落；PDF 字符映射不足，无法进一步精确到句子')
  }
  if (evidenceFocusTimer != null) window.clearTimeout(evidenceFocusTimer)
  evidenceFocusTimer = window.setTimeout(() => {
    evidenceFocus.value = null
    evidenceFocusTimer = null
  }, 8000)
}

async function executeAgentActions(actions) {
  for (const action of actions || []) {
    if (action?.type !== 'HIGHLIGHT') continue
    if (action.status !== 'READY' || !action.evidence) {
      ElMessage.warning(action.message || '没有找到可精确高亮的论文原文')
      continue
    }
    if (Number(action.paperId) !== Number(props.paper.id)) {
      ElMessage.warning('高亮目标不在当前论文中，未执行')
      continue
    }
    const target = {
      ...action.evidence,
      locator: {
        ...(action.evidence.locator || {}),
        targetText: action.targetText || action.evidence.locator?.targetText || '',
      },
    }
    await goToPage(target.page)
    const exactBoxes = await locateEvidenceText(target)
    const fallbackBox = target.locator?.targetBbox || target.bbox
    const formulaFallback = target.locator?.precision === 'FORMULA_REGION' && fallbackBox
      ? [fallbackBox] : []
    const boxes = exactBoxes.length ? exactBoxes : formulaFallback
    if (!boxes.length) {
      ElMessage.warning(`已找到“${action.query}”的来源，但无法建立精确字符位置，因此未高亮`)
      continue
    }
    if (annotations.value.some(annotation => (
      annotation.aiGenerated
      && annotation.coordinates?.agentActionId === action.actionId
    ))) {
      await jumpToEvidence(target)
      ElMessage.info('该内容已经高亮')
      continue
    }
    const page = renderedPages.value[Number(target.page) - 1]
    const quads = boxes.map(boundingBoxToViewportQuad).filter(Boolean)
    if (!page?.viewport || !quads.length) {
      ElMessage.warning('PDF 页面尚未准备完成，未执行高亮')
      continue
    }
    const annotation = {
      localId: nextLocalId++,
      paperId: props.paper.id,
      type: 'HIGHLIGHT',
      page: target.page,
      color: currentColor.value,
      note: '',
      completed: false,
      aiGenerated: true,
      coordinates: {
        coordinateSpace: 'viewport',
        pageWidth: page.viewport.width,
        pageHeight: page.viewport.height,
        rotation: page.viewport.rotation,
        scale: page.viewport.scale,
        quads,
        anchorKind: 'AGENT_EVIDENCE',
        anchorText: String(action.targetText || action.query || '').slice(0, 500),
        evidenceId: action.evidenceId,
        agentActionId: action.actionId,
      },
    }
    try {
      await persistNewAgentAnnotation(annotation)
      selectedAnnotation.value = null
      evidenceFocus.value = { page: target.page, boxes, precision: exactBoxes.length ? 'TEXT' : 'FORMULA_REGION' }
      await nextTick()
      scrollEvidenceIntoView(target.page, boxes)
      ElMessage.success(`已在原文中高亮“${action.query}”`)
    } catch (error) {
      ElMessage.error(`自动高亮保存失败：${requestErrorMessage(error)}`)
    }
  }
}

async function locateEvidenceText(item) {
  if (!pdfInteractionReady.value || !pdfInteractionEngine) return []
  const targetBox = item?.locator?.targetBbox || item?.bbox
  const targetText = item?.locator?.targetText || item?.text
  if (item?.locator?.precision === 'FORMULA_REGION' && !String(targetText || '').trim()) return []
  const fragments = String(targetText || '').split(/\r?\n/)
    .map(value => value.replace(/\s+/g, ' ').trim())
    .filter(Boolean)
  const located = []
  for (const fragment of fragments.length ? fragments : [String(targetText || '')]) {
    const boxes = await locateEvidenceFragment(item.page, fragment, targetBox)
    if (!boxes.length) return []
    located.push(...boxes)
  }
  return dedupeEvidenceBoxes(located)
}

async function locateEvidenceFragment(page, text, targetBox) {
  for (const phrase of evidenceSearchPhrases(text)) {
    let matches
    try { matches = await pdfInteractionEngine.search(phrase) }
    catch { return [] }
    const pageMatches = (matches || []).filter(candidate => candidate.pageIndex + 1 === page)
    if (!pageMatches.length) continue
    const overlapping = pageMatches.filter(candidate => (
      candidate.rects?.some(rect => boxesOverlap(rect, targetBox))
    ))
    if (overlapping.length === 1) return overlapping[0].rects || []
    // A unique exact phrase is safer than a stale block bbox. This fixes citations whose layout
    // block spans both the cited sentence and the following sentence.
    if (pageMatches.length === 1) return pageMatches[0].rects || []
    const candidates = overlapping.length ? overlapping : pageMatches
    const nearest = candidates
      .map(candidate => ({ candidate, distance: evidenceMatchDistance(candidate.rects, targetBox) }))
      .sort((first, second) => first.distance - second.distance)[0]?.candidate
    if (nearest?.rects?.length && phrase.length >= 16) return nearest.rects
  }
  return []
}

function evidenceSearchPhrases(text) {
  const source = String(text || '').replace(/\s+/g, ' ').trim()
  if (!source) return []
  const candidates = [source.slice(0, 240)]
  const words = source.split(' ').filter(Boolean)
  if (words.length > 12) {
    const windows = [
      words.slice(0, 12),
      words.slice(Math.max(0, Math.floor(words.length / 2) - 6), Math.floor(words.length / 2) + 6),
      words.slice(-12),
    ]
    windows.forEach(window => candidates.push(window.join(' ')))
  }
  if (words.length > 7) {
    candidates.push(words.slice(0, 7).join(' '), words.slice(-7).join(' '))
  }
  return [...new Set(candidates.map(value => value.trim()).filter(value => value.length >= 8))]
}

function evidenceMatchDistance(rects, targetBox) {
  if (!targetBox || !rects?.length) return Number.POSITIVE_INFINITY
  const x = rects.reduce((sum, rect) => sum + Number(rect.x || 0) + Number(rect.width || 0) / 2, 0) / rects.length
  const y = rects.reduce((sum, rect) => sum + Number(rect.y || 0) + Number(rect.height || 0) / 2, 0) / rects.length
  const targetX = Number(targetBox.x || 0) + Number(targetBox.width || 0) / 2
  const targetY = Number(targetBox.y || 0) + Number(targetBox.height || 0) / 2
  return Math.hypot(x - targetX, y - targetY)
}

function dedupeEvidenceBoxes(boxes) {
  const unique = new Map()
  for (const box of boxes || []) {
    const key = [box.x, box.y, box.width, box.height]
      .map(value => Math.round(Number(value || 0) * 10000)).join(':')
    unique.set(key, box)
  }
  return [...unique.values()].sort((first, second) => first.y - second.y || first.x - second.x)
}

function scrollEvidenceIntoView(pageNum, boxes) {
  const container = containerRef.value
  const page = renderedPages.value[Number(pageNum) - 1]
  const validBoxes = (boxes || []).filter(Boolean)
  if (!container || !page || !validBoxes.length) return
  const pageElement = container.querySelector(`[data-page="${pageNum}"]`)
  const pageTop = pageElement?.offsetTop ?? pageOffset(pageNum)
  const pageLeft = pageElement?.offsetLeft ?? 0
  const top = Math.min(...validBoxes.map(box => Number(box.y || 0)))
  const left = Math.min(...validBoxes.map(box => Number(box.x || 0)))
  const right = Math.max(...validBoxes.map(box => Number(box.x || 0) + Number(box.width || 0)))
  const absoluteTop = pageTop + top * pageHeight(page)
  const topMargin = Math.min(140, container.clientHeight * 0.22)
  container.scrollTop = Math.max(0, Math.min(
    absoluteTop - topMargin,
    Math.max(0, container.scrollHeight - container.clientHeight),
  ))

  const absoluteLeft = pageLeft + left * Number(page.width || 0)
  const absoluteRight = pageLeft + right * Number(page.width || 0)
  const horizontalMargin = 36
  if (absoluteLeft < container.scrollLeft + horizontalMargin
      || absoluteRight > container.scrollLeft + container.clientWidth - horizontalMargin) {
    container.scrollLeft = Math.max(0, absoluteLeft - horizontalMargin)
  }
}

function boxesOverlap(first, second) {
  if (!first || !second) return false
  const horizontal = Math.min(first.x + first.width, second.x + second.width) - Math.max(first.x, second.x)
  const vertical = Math.min(first.y + first.height, second.y + second.height) - Math.max(first.y, second.y)
  return horizontal > 0 && vertical > 0
}

async function applyTextAnnotation(type) {
  const selection = pendingTextSelection.value
  if (!selection?.groups?.length) {
    ElMessage.warning('请先拖动选中文本，再点击标记按钮')
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
          quads: group.quads,
          anchorKind: 'SELECTION',
          anchorText: String(selection.text || '').slice(0, 500),
          textAnchor: cloneSelectionTextAnchor(selection.textAnchor),
        }
      }
      await persistNewAnnotation(annotation)
      savedCount += 1
    }
    selectedAnnotation.value = null
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
  selectionContextRequestId += 1
  layoutSelectionDrag = null
  pendingTextSelection.value = null
  selectionAnchor.value = null
  selectionContextLoading.value = false
  selectionContextError.value = ''
  evidenceFocus.value = null
  if (evidenceFocusTimer != null) {
    window.clearTimeout(evidenceFocusTimer)
    evidenceFocusTimer = null
  }
  window.getSelection()?.removeAllRanges()
}

function clearFormulaRegion() {
  formulaRegionDrag = null
  formulaRegion.value = null
  formulaRecognition.value = null
  formulaPreviewDataUrl.value = ''
  formulaRecognitionLoading.value = false
  formulaConfirming.value = false
  formulaRecognitionError.value = ''
}

function clearFormulaAndContinueCapture() {
  clearFormulaRegion()
  if (currentTool.value !== 'formula') activateFormula()
}

function fixedFormulaSnapshot() {
  if (!formulaRecognition.value?.confirmed || !formulaRegion.value) return null
  return {
    region: formulaRegion.value,
    recognition: formulaRecognition.value,
    previewDataUrl: formulaPreviewDataUrl.value,
    error: formulaRecognitionError.value,
  }
}

function restoreFormulaSnapshot(snapshot) {
  if (!snapshot) {
    clearFormulaRegion()
    return
  }
  formulaRegion.value = snapshot.region
  formulaRecognition.value = snapshot.recognition
  formulaPreviewDataUrl.value = snapshot.previewDataUrl
  formulaRecognitionError.value = snapshot.error
  formulaRecognitionLoading.value = false
  formulaConfirming.value = false
}

async function confirmFormulaReplacement(nextContentLabel) {
  try {
    await ElMessageBox.confirm(
      `${nextContentLabel}将覆盖当前已固定公式，是否继续？`,
      '替换已固定内容',
      {
        confirmButtonText: '覆盖',
        cancelButtonText: '保留原内容',
        type: 'warning',
      },
    )
    return true
  } catch {
    return false
  }
}

function beginFormulaRegionSelection(event, page) {
  if (currentTool.value !== 'formula' || event.button !== 0) return
  const overlay = event.currentTarget
  const rect = overlay.getBoundingClientRect()
  if (!rect.width || !rect.height) return
  event.preventDefault()
  event.stopPropagation()
  clearPendingTextSelection()
  const previousFormula = fixedFormulaSnapshot()
  formulaRecognition.value = null
  formulaRecognitionError.value = ''
  formulaRegionDrag = {
    pointerId: event.pointerId,
    page: page.pageNum,
    overlay,
    pageRect: rect,
    start: { x: event.clientX, y: event.clientY },
    previousFormula,
  }
  formulaRegion.value = { page: page.pageNum, bbox: null }
  overlay.setPointerCapture?.(event.pointerId)
}

function updateFormulaRegionSelection(event) {
  const drag = formulaRegionDrag
  if (!drag || drag.pointerId !== event.pointerId || currentTool.value !== 'formula') return false
  event.preventDefault()
  const bbox = normalizedFormulaRegion(
    drag.start,
    { x: event.clientX, y: event.clientY },
    drag.pageRect,
    0,
  )
  formulaRegion.value = { page: drag.page, bbox }
  return true
}

async function finishFormulaRegionSelection(event, cancelled = false) {
  const drag = formulaRegionDrag
  if (!drag || drag.pointerId !== event.pointerId) return false
  if (!cancelled) updateFormulaRegionSelection(event)
  if (drag.overlay.hasPointerCapture?.(event.pointerId)) {
    drag.overlay.releasePointerCapture(event.pointerId)
  }
  formulaRegionDrag = null
  if (cancelled) {
    restoreFormulaSnapshot(drag.previousFormula)
    return true
  }
  const bbox = normalizedFormulaRegion(
    drag.start,
    { x: event.clientX, y: event.clientY },
    drag.pageRect,
  )
  if (!bbox) {
    restoreFormulaSnapshot(drag.previousFormula)
    ElMessage.warning('公式区域太小，请拖框圈定完整公式')
    return true
  }
  formulaRegion.value = { page: drag.page, bbox }
  formulaPreviewDataUrl.value = createFormulaRegionPreview(
    canvasRefs.value[drag.page], bbox,
  )?.dataUrl || ''
  currentTool.value = 'select'
  if (drag.previousFormula) {
    const replace = await confirmFormulaReplacement('新框选的公式')
    if (!replace) {
      restoreFormulaSnapshot(drag.previousFormula)
      return true
    }
  }
  workbenchPanelVisible.value = true
  return true
}

async function recognizeCurrentFormulaRegion() {
  const region = formulaRegion.value
  if (!region?.bbox || formulaRecognitionLoading.value) return
  const refresh = Boolean(formulaRecognition.value)
  formulaRecognitionLoading.value = true
  formulaRecognitionError.value = ''
  try {
    const result = await recognizeFormulaRegion(props.paper.id, {
      page: region.page,
      bbox: region.bbox,
      refresh,
      ...(formulaPreviewDataUrl.value && formulaPreviewDataUrl.value.length <= 2_700_000
        ? { clientImageDataUrl: formulaPreviewDataUrl.value } : {}),
    })
    if (formulaRegion.value !== region) return
    formulaRecognition.value = {
      ...result,
      previewDataUrl: result.previewDataUrl || formulaPreviewDataUrl.value,
    }
  } catch (error) {
    if (formulaRegion.value === region) {
      formulaRecognitionError.value = requestErrorMessage(error) || '公式固定准备失败'
    }
  } finally {
    if (formulaRegion.value === region) formulaRecognitionLoading.value = false
  }
}

async function confirmCurrentFormulaRegion(formulas) {
  const recognition = formulaRecognition.value
  if (!recognition?.id || formulaConfirming.value) return
  formulaConfirming.value = true
  formulaRecognitionError.value = ''
  try {
    const confirmed = await confirmFormulaRegion(props.paper.id, recognition.id, formulas)
    if (formulaRecognition.value?.id !== recognition.id) return
    formulaRecognition.value = {
      ...confirmed,
      previewDataUrl: confirmed.previewDataUrl || recognition.previewDataUrl,
    }
    workbenchPanelVisible.value = true
    ElMessage.success('公式已确认')
  } catch (error) {
    if (formulaRecognition.value?.id === recognition.id) {
      formulaRecognitionError.value = requestErrorMessage(error) || '公式固定失败'
    }
  } finally {
    formulaConfirming.value = false
  }
}

function setTool(tool) {
  currentTool.value = tool
  selectedAnnotation.value = null
  notePreview.value = null
  if (tool !== 'select') clearPendingTextSelection()
  if (tool !== 'formula' && tool !== 'select' && !formulaRecognition.value?.confirmed) {
    clearFormulaRegion()
  }
}

function activateFormula() {
  if (currentTool.value === 'formula') {
    currentTool.value = 'select'
    return
  }
  clearPendingTextSelection()
  if (!formulaRecognition.value?.confirmed) clearFormulaRegion()
  currentTool.value = 'formula'
  selectedAnnotation.value = null
  notePreview.value = null
  workbenchPanelVisible.value = true
}

function selectWorkbenchCaptureMode(mode) {
  workbenchPanelVisible.value = true
  if (mode === 'formula') {
    if (currentTool.value !== 'formula') activateFormula()
    return
  }
  setTool('select')
  if (!formulaRecognition.value?.confirmed) clearFormulaRegion()
}

function openSelectionNote() {
  const selection = pendingTextSelection.value
  if (!selection?.groups?.length) {
    ElMessage.warning('请先拖动选中文本，再添加笔记')
    setTool('select')
    return
  }
  openAnchoredNote(selection)
}

function openSelectionComment() {
  const selection = pendingTextSelection.value
  if (!selection?.groups?.length) {
    ElMessage.warning('请先拖动选中文本，再添加批注')
    setTool('select')
    return
  }
  openAnchoredComment(selection)
}

function findPageElement(node) {
  let el = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement
  while (el) {
    if (el.classList?.contains('pdf-page')) return el
    el = el.parentElement
  }
  return null
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

function openAnchoredNote(selection) {
  const group = selection.groups[0]
  if (!group?.pageState?.viewport || !group.quads?.length) {
    ElMessage.warning('未能获取所选文字的位置，请重新选择后再试')
    return
  }
  noteEditTarget.value = buildSelectionNoteDraft({
    localId: nextLocalId++,
    paperId: props.paper.id,
    color: currentColor.value,
    selection,
    notePosition: notePositionNearAnchor(group.quads),
  })
  noteEditText.value = ''
  noteDialogVisible.value = true
}

function openAnchoredComment(selection) {
  const group = selection.groups[0]
  if (!group?.pageState?.viewport || !group.quads?.length) {
    ElMessage.warning('未能获取所选文字的位置，请重新选择后再试')
    return
  }
  noteEditTarget.value = buildSelectionCommentDraft({
    localId: nextLocalId++,
    paperId: props.paper.id,
    color: currentColor.value,
    selection,
    notePosition: notePositionNearAnchor(group.quads),
  })
  noteEditText.value = ''
  noteDialogVisible.value = true
}

async function confirmNote() {
  const content = noteEditText.value.trim()
  if (!content) {
    ElMessage.warning(noteDialogIsSelectionNote.value ? '请先填写笔记内容' : '请先填写批注内容')
    return
  }
  const target = noteEditTarget.value
  if (!target) return
  target.note = content
  noteSaving.value = true
  try {
    await persistNewAnnotation(target)
    selectedAnnotation.value = null
    notePreview.value = null
    currentTool.value = 'select'
    noteDialogVisible.value = false
    clearPendingTextSelection()
    ElMessage.success(isSelectionNote(target) ? '笔记已保存' : '批注已保存')
  } catch (e) {
    ElMessage.error(`${isSelectionNote(target) ? '笔记' : '批注'}保存失败：${requestErrorMessage(e)}`)
  } finally {
    noteSaving.value = false
  }
}

function onOverlayPointerMove(e) {
  if (updateFormulaRegionSelection(e)) return
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
  if (formulaRegionDrag) {
    await finishFormulaRegionSelection(e, e.type === 'pointercancel')
    return
  }
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
      drag.annotation.coordinates.notePosition = drag.originalPosition
      ElMessage.error(`${isSelectionNote(drag.annotation) ? '笔记' : '批注'}位置保存失败：${requestErrorMessage(error)}`)
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
      ElMessage.success('标记范围已更新')
    } catch (error) {
      resize.annotation.coordinates.quads = resize.originalQuads
      ElMessage.error('标记范围保存失败：' + requestErrorMessage(error))
    }
  }
}

function beginAnnotationPointerDown(e, ann) {
  if (e.button !== 0) return
  selectedAnnotation.value = ann
  if (!isMarkerAnnotation(ann)) return
  if (!e.target?.closest?.('.note-marker, .comment-marker')) return
  const pageEl = findPageElement(e.currentTarget)
  if (!pageEl) return
  const startPosition = ann.coordinates?.notePosition || { x: 0.5, y: 0.5 }
  draggingNote = {
    annotation: ann,
    pageEl,
    captureTarget: e.currentTarget,
    startPosition: { ...startPosition },
    originalPosition: { ...startPosition },
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
  if (!isResizableAnnotation(annotation)) return
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
  // Manual geometry adjustment no longer guarantees the old character range.
  // Keep anchorText for display, but never persist a knowingly stale range.
  resize.annotation.coordinates.textAnchor = null
  resize.moved = true
}

function onAnnotationClick(ann) {
  if (suppressAnnotationClickId === ann.localId) return
  selectedAnnotation.value = ann
  if (isMarkerAnnotation(ann)) {
    notePreview.value = notePreview.value?.localId === ann.localId ? null : ann
  } else {
    notePreview.value = null
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
  if (isMarkerAnnotation(annotation) && !note) {
    ElMessage.warning(`${isSelectionNote(annotation) ? '笔记' : '批注'}内容不能为空`)
    return
  }
  const previous = { color: annotation.color, note: annotation.note }
  annotation.color = annotationEditorColor.value
  if (isMarkerAnnotation(annotation)) annotation.note = note
  annotationEditorSaving.value = true
  try {
    await persistUpdatedAnnotation(annotation)
    annotationEditorVisible.value = false
    ElMessage.success(`${isSelectionNote(annotation) ? '笔记' : '批注'}已更新`)
  } catch (e) {
    annotation.color = previous.color
    annotation.note = previous.note
    ElMessage.error(`${isSelectionNote(annotation) ? '笔记' : '批注'}更新失败：${requestErrorMessage(e)}`)
  } finally {
    annotationEditorSaving.value = false
  }
}

async function deleteAnnotationFromEditor() {
  const annotation = annotationEditorTarget.value
  if (!annotation) return
  await deleteAnnotationImmediately(annotation)
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
    ElMessage.success(`${isSelectionNote(annotation) ? '笔记' : isCommentAnnotation(annotation) ? '批注' : '标记'}已删除`)
  } catch (e) {
    ElMessage.error('删除失败：' + requestErrorMessage(e))
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

async function persistNewAgentAnnotation(annotation) {
  annotations.value.push(annotation)
  try {
    const saved = await createAgentAnnotation(props.paper.id, toPayload(annotation))
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

function toPayload(ann) {
  return {
    type: ann.type,
    page: ann.page,
    color: ann.color,
    note: ann.note,
    completed: Boolean(ann.completed),
    coordinates: ann.coordinates
  }
}

function annotationDeleteTransform(annotation, page) {
  const points = (annotation?.coordinates?.quads || []).flatMap(quad => [
    annotationPoint(quad.x1, quad.y1, page, annotation.coordinates),
    annotationPoint(quad.x2, quad.y2, page, annotation.coordinates),
    annotationPoint(quad.x3, quad.y3, page, annotation.coordinates),
    annotationPoint(quad.x4, quad.y4, page, annotation.coordinates),
  ])
  if (!points.length) return 'translate(12 12)'
  const x = clamp(Math.max(...points.map(([pointX]) => pointX)) + 11, 10, Math.max(10, page.width - 10))
  const y = clamp(Math.min(...points.map(([, pointY]) => pointY)) - 11, 10, Math.max(10, page.height - 10))
  return `translate(${x} ${y})`
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

function markerAnchorPoint(annotation, page) {
  const coords = annotation?.coordinates
  if (!page.viewport || !coords) return null
  if (isCommentAnnotation(annotation) && Number.isFinite(coords.anchorPoint?.x) && Number.isFinite(coords.anchorPoint?.y)) {
    const [x, y] = annotationPoint(coords.anchorPoint.x, coords.anchorPoint.y, page, coords)
    return { x, y }
  }
  if (!coords.anchorQuads?.length) return null
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

function commentMarkerPath(point) {
  const x = point?.x || 0
  const y = point?.y || 0
  return `M ${x - 11} ${y - 21} h 22 a 3 3 0 0 1 3 3 v 12 a 3 3 0 0 1 -3 3 h -6 l -5 5 v -5 h -9 a 3 3 0 0 1 -3 -3 v -12 a 3 3 0 0 1 3 -3 z`
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
  if (!pendingTextSelection.value?.groups?.length) return
  contextMenu.value = { visible: true, x: e.clientX, y: e.clientY }
}

function openSelectionNoteFromContext() {
  contextMenu.value.visible = false
  openSelectionNote()
}

function onWindowClick() {
  if (contextMenu.value.visible) contextMenu.value.visible = false
  if (zoomMenuVisible.value) zoomMenuVisible.value = false
  if (colorMenuVisible.value) colorMenuVisible.value = false
  if (notePreview.value) notePreview.value = null
  if (selectedAnnotation.value && !resizingAnnotation && !draggingNote) {
    selectedAnnotation.value = null
  }
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
  --pdf-toolbar-height: 48px;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--ra-bg);
}
.viewer-body {
  display: flex;
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: visible;
}
.viewer-body.is-workbench-resizing,
.viewer-body.is-workbench-resizing * {
  cursor: col-resize !important;
  user-select: none !important;
}
.workbench-divider {
  position: relative;
  z-index: 4;
  flex: 0 0 8px;
  width: 8px;
  min-height: 0;
  border: 0;
  outline: none;
  background: transparent;
  cursor: col-resize;
  touch-action: none;
  margin-top: calc(-1 * var(--pdf-toolbar-height));
}
.workbench-divider::before {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 3px;
  width: 1px;
  background: var(--ra-border-light);
  content: '';
  transition: width .12s ease, left .12s ease, background .12s ease;
}
.workbench-divider > span {
  position: absolute;
  z-index: 1;
  top: 50%;
  left: 2px;
  width: 4px;
  height: 22px;
  transform: translateY(-50%);
  border-radius: 4px;
  background: radial-gradient(circle, var(--ra-text-tertiary) 1px, transparent 1.3px) center / 4px 5px;
  opacity: .45;
}
.workbench-divider:hover::before,
.workbench-divider:focus-visible::before,
.viewer-body.is-workbench-resizing .workbench-divider::before {
  left: 2px;
  width: 3px;
  background: var(--ra-link);
}
.pdf-toolbar {
  display: grid;
  grid-template-columns: minmax(180px, 1.35fr) auto minmax(132px, .65fr);
  align-items: center;
  padding: 7px 12px;
  background: var(--ra-panel-bg);
  border-bottom: 1px solid var(--ra-border-light);
  gap: 8px;
  flex-shrink: 0;
  overflow-x: auto;
  min-height: var(--pdf-toolbar-height);
  box-sizing: border-box;
  transition: margin-right .16s ease;
}
.pdf-toolbar :deep(.el-button) { border-radius: 8px; padding-inline: 7px; }
.pdf-toolbar :deep(.el-button-group) {
  display:inline-flex;
  flex-wrap:nowrap;
  align-items:center;
}
.pdf-toolbar :deep(.el-button-group > .el-button:first-child) {
  border-top-right-radius: 0;
  border-bottom-right-radius: 0;
}
.pdf-toolbar :deep(.el-button-group > .el-button:last-child) {
  border-top-left-radius: 0;
  border-bottom-left-radius: 0;
}
.pdf-toolbar :deep(.el-button-group > .el-button:not(:first-child):not(:last-child)) {
  border-radius: 0;
}
.pdf-toolbar-left, .pdf-toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.pdf-toolbar-right {
  justify-content: flex-end;
  gap: 2px;
  white-space: nowrap;
}
.pdf-toolbar-right :deep(.el-button + .el-button) { margin-left: 0 !important; }
.pdf-toolbar-center {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: nowrap;
  justify-content: center;
  white-space: nowrap;
}
.pdf-tool-group, .zoom-controls, .page-navigation {
  display: flex;
  align-items: center;
  gap: 3px;
}
.zoom-controls :deep(.el-button) { width:24px; min-width:24px; padding:4px; }
.zoom-value {
  min-height: 26px;
  min-width: 36px;
  padding: 2px 3px;
  border: 0;
  border-radius: 4px;
  color: var(--ra-text-secondary);
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  text-align: center;
}
.zoom-value:hover,
.zoom-value:focus-visible {
  color: var(--ra-link);
  background: var(--ra-hover-bg);
  outline: none;
}
.zoom-option-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.zoom-option-list button {
  width: 100%;
  padding: 5px 6px;
  border: 0;
  border-radius: 4px;
  color: var(--ra-text);
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  text-align: center;
}
.zoom-option-list button:hover,
.zoom-option-list button.active {
  color: var(--ra-link);
  background: var(--ra-hover-bg);
}
.toolbar-separator {
  width: 1px;
  height: 20px;
  flex: 0 0 1px;
  background: var(--ra-border);
}
.page-navigation {
  min-height: 28px;
  box-sizing: border-box;
  color: var(--ra-text-secondary);
  font-size: 12px;
  white-space: nowrap;
}
.page-navigation input {
  width: 30px;
  min-width: 0;
  box-sizing: border-box;
  padding: 3px 4px;
  border: 1px solid var(--ra-border);
  border-radius: 4px;
  outline: none;
  color: var(--ra-text);
  background: var(--ra-panel-bg);
  font: inherit;
  text-align: center;
  appearance: textfield;
}
.page-navigation input:focus {
  border-color: var(--ra-link);
  box-shadow: 0 0 0 1px var(--ra-link);
}
.page-navigation input::-webkit-inner-spin-button,
.page-navigation input::-webkit-outer-spin-button {
  margin: 0;
  appearance: none;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
.annotation-color-menu {
  position: relative;
  flex: 0 0 auto;
}
.annotation-color-trigger {
  display: grid;
  width: 28px;
  height: 28px;
  padding: 0;
  place-items: center;
  border: 1px solid var(--ra-border);
  border-radius: 5px;
  background: var(--ra-panel-bg);
  cursor: pointer;
}
.annotation-color-trigger:hover,
.annotation-color-trigger:focus-visible {
  border-color: var(--ra-link);
}
.annotation-color-trigger > span {
  width: 12px;
  height: 12px;
  border: 1px solid rgba(127, 127, 127, 0.55);
  border-radius: 50%;
  background: var(--annotation-color);
}
.annotation-color-list {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 7px;
  padding: 0;
}
.annotation-color-option {
  width: 14px;
  height: 14px;
  padding: 0;
  border: 1px solid rgba(127, 127, 127, 0.55);
  border-radius: 50%;
  cursor: pointer;
  box-sizing: border-box;
}
.annotation-color-option:hover,
.annotation-color-option:focus-visible,
.annotation-color-option.active {
  outline: 2px solid var(--ra-link);
  outline-offset: 1px;
}
.pdf-title {
  font-size: 14px;
  color: var(--ra-text);
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pdf-search-panel,
.pdf-comment-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  box-sizing: border-box;
  background: var(--ra-panel-bg);
}
.pdf-search-panel {
  flex: 0 0 292px;
  width: 292px;
  border-right: 1px solid var(--ra-border);
}
.pdf-comment-panel {
  flex: 0 0 auto;
  border-left: 1px solid var(--ra-border);
}
.side-panel-header {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  min-height: 42px;
  box-sizing: border-box;
  padding: 8px 12px;
  border-bottom: 1px solid var(--ra-border);
  color: var(--ra-text);
  font-size: 13px;
}
.side-panel-header button,
.pdf-search-box button,
.pdf-search-navigation button {
  border: 0;
  color: var(--ra-text-secondary);
  background: transparent;
  cursor: pointer;
}
.side-panel-header button {
  padding: 2px 5px;
  font-size: 18px;
}
.side-panel-header button:hover,
.pdf-search-box button:hover,
.pdf-search-navigation button:hover:not(:disabled) {
  color: var(--ra-link);
}
.pdf-search-box {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  margin: 10px 10px 6px;
  border: 1px solid var(--ra-border);
  border-radius: 6px;
  background: var(--ra-bg);
}
.pdf-search-box:focus-within {
  border-color: var(--ra-link);
  box-shadow: 0 0 0 1px var(--ra-link);
}
.pdf-search-box input {
  flex: 1;
  min-width: 0;
  padding: 7px 8px;
  border: 0;
  outline: 0;
  color: var(--ra-text);
  background: transparent;
  font: inherit;
  font-size: 12px;
}
.pdf-search-box input::-webkit-search-cancel-button,
.pdf-search-box input::-webkit-search-decoration {
  appearance: none;
  display: none;
}
.pdf-search-box input::-ms-clear {
  display: none;
}
.pdf-search-box button {
  flex: 0 0 auto;
  padding: 5px 9px;
}
.pdf-search-summary {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  min-height: 28px;
  padding: 0 10px 6px;
  color: var(--ra-text-tertiary);
  font-size: 12px;
}
.pdf-search-navigation {
  display: flex;
  gap: 2px;
  margin-left: auto;
}
.pdf-search-navigation button {
  width: 24px;
  height: 24px;
  border-radius: 4px;
}
.pdf-search-navigation button:hover:not(:disabled) {
  background: var(--ra-hover-bg);
}
.pdf-search-navigation button:disabled {
  cursor: default;
  opacity: 0.4;
}
.pdf-search-results,
.pdf-comment-list {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 4px 8px 10px;
}
.pdf-search-result,
.pdf-comment-card {
  display: block;
  width: 100%;
  box-sizing: border-box;
  margin: 0 0 6px;
  padding: 8px 9px;
  border: 1px solid transparent;
  border-radius: 6px;
  color: var(--ra-text);
  background: transparent;
  text-align: left;
}
.pdf-search-result { cursor: pointer; }
.pdf-search-result:hover,
.pdf-comment-card:hover {
  background: var(--ra-hover-bg);
}
.pdf-search-result.active,
.pdf-comment-card.active {
  border-color: color-mix(in srgb, var(--ra-link) 50%, transparent);
  background: color-mix(in srgb, var(--ra-link) 10%, transparent);
}
.pdf-comment-card.completed {
  border-color: color-mix(in srgb, #4caf50 48%, transparent);
  background: color-mix(in srgb, #4caf50 10%, transparent);
}
.pdf-comment-card__body {
  display: block;
  width: 100%;
  padding: 0;
  border: 0;
  color: inherit;
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.pdf-comment-card__actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 7px;
  padding-top: 6px;
  border-top: 1px solid var(--ra-border);
}
.pdf-comment-card__actions button {
  padding: 1px 2px;
  border: 0;
  color: var(--ra-link);
  background: transparent;
  cursor: pointer;
  font-size: 11px;
}
.pdf-comment-card__actions button:disabled {
  color: #4caf50;
  cursor: default;
}
.pdf-comment-card__actions button.danger { color: var(--el-color-danger); }
.pdf-search-result span,
.pdf-comment-card span {
  color: var(--ra-text-tertiary);
  font-size: 11px;
}
.pdf-search-result p,
.pdf-comment-card p {
  display: -webkit-box;
  overflow: hidden;
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.5;
  overflow-wrap: anywhere;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}
.side-panel-empty {
  padding: 32px 12px;
  color: var(--ra-text-tertiary);
  font-size: 12px;
  text-align: center;
}
.pdf-pages {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable both-edges;
  display: flex;
  flex-direction: column;
  align-items: safe center;
  padding: 16px 0;
  gap: 0;
}
.pdf-load-recovery {
  position: sticky;
  z-index: 4;
  top: 18px;
  display: flex;
  width: min(420px, calc(100% - 36px));
  box-sizing: border-box;
  flex-direction: column;
  gap: 7px;
  margin: 18px auto;
  padding: 14px;
  border: 1px solid color-mix(in srgb, var(--el-color-danger) 35%, var(--ra-border));
  border-radius: 8px;
  background: var(--ra-panel-bg);
  box-shadow: 0 6px 20px rgb(0 0 0 / 9%);
}
.pdf-load-recovery b { font-size: 12px; }
.pdf-load-recovery span { color: var(--ra-text-tertiary); font-size: 10px; overflow-wrap: anywhere; }
.pdf-load-recovery :deep(.el-button) { align-self: flex-start; }
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
.pdf-page-loading {
  position: absolute;
  inset: 0;
  z-index: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ra-text-tertiary);
  background: linear-gradient(110deg, var(--ra-panel-bg) 30%, var(--ra-hover-bg) 48%, var(--ra-panel-bg) 66%);
  font-size: 12px;
  pointer-events: none;
}
.pdf-page canvas {
  position: relative;
  z-index: 0;
  display: block;
}
.text-layer {
  position: absolute;
  top: 0;
  left: 0;
  overflow: hidden;
  opacity: 1;
  text-align: initial;
  line-height: 1;
  -webkit-text-size-adjust: none;
  -moz-text-size-adjust: none;
  text-size-adjust: none;
  forced-color-adjust: none;
  transform-origin: 0 0;
  user-select: none;
  cursor: text;
  touch-action: none;
  z-index: 1;
}
.annotation-overlay {
  position: absolute;
  top: 0;
  left: 0;
  z-index: 2;
  pointer-events: none;
}
.annotation-overlay.formula-mode {
  pointer-events: auto;
}
.annotation-overlay.formula-mode {
  cursor: crosshair;
  touch-action: none;
}
.annotation-overlay > g {
  pointer-events: none;
}
.annotation-overlay .pdf-search-overlay rect {
  fill: #ffe45c;
  fill-opacity: 0.48;
  stroke: #d8a600;
  stroke-width: 1;
}
.annotation-overlay .pdf-search-overlay rect.current {
  fill: #ff9800;
  fill-opacity: 0.6;
  stroke: #e65100;
  stroke-width: 2;
}
.annotation-overlay > g.marker-annotation,
.annotation-overlay > g.text-annotation {
  pointer-events: all;
}
.annotation-overlay.formula-mode > g.marker-annotation,
.annotation-overlay.formula-mode > g.text-annotation {
  pointer-events: none;
}
.annotation-overlay .selected {
  filter: drop-shadow(0 0 2px var(--ra-link));
}
.annotation-overlay .evidence-focus-preview {
  pointer-events: none;
}
.annotation-overlay .formula-region-preview rect {
  fill: color-mix(in srgb, var(--ra-link) 10%, transparent);
  stroke: var(--ra-link);
  stroke-width: 2;
  stroke-dasharray: 6 3;
  vector-effect: non-scaling-stroke;
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
.annotation-overlay .note-marker,
.annotation-overlay .comment-marker {
  cursor: grab;
}
.annotation-overlay .note-marker:active,
.annotation-overlay .comment-marker:active {
  cursor: grabbing;
}
.annotation-overlay .text-annotation { cursor: pointer; }
.note-marker-label {
  fill: #fff;
  font-size: 11px;
  font-weight: 700;
  pointer-events: none;
  user-select: none;
}
.comment-marker-dots {
  fill: #fff;
  pointer-events: none;
}
.annotation-resize-handle {
  fill: var(--ra-panel-bg);
  stroke-width: 2;
  cursor: ew-resize;
  pointer-events: all;
}
.annotation-resize-handle:hover {
  fill: var(--ra-hover-bg);
}
.annotation-delete-control {
  cursor: pointer;
  pointer-events: all;
}
.annotation-delete-control circle {
  fill: var(--el-color-danger);
  stroke: var(--ra-panel-bg);
  stroke-width: 1.5;
}
.annotation-delete-control text {
  fill: #fff;
  font-size: 14px;
  font-weight: 700;
  pointer-events: none;
  user-select: none;
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
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
}
.completed-label {
  margin-right: auto;
  color: #4caf50;
  font-size: 11px;
}
.selection-comment-anchor {
  margin-bottom: 12px;
  padding: 9px 10px;
  border-left: 3px solid var(--ra-link);
  border-radius: 4px;
  background: var(--ra-hover-bg);
}
.selection-comment-anchor span {
  color: var(--ra-text-tertiary);
  font-size: 11px;
}
.selection-comment-anchor p {
  max-height: 72px;
  overflow: auto;
  margin: 5px 0 0;
  color: var(--ra-text);
  font-size: 12px;
  line-height: 1.45;
  white-space: pre-wrap;
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
:global(.pdf-zoom-popper) {
  min-width: 76px !important;
  padding: 5px !important;
  z-index: 22000 !important;
}
:global(.pdf-annotation-color-popper) {
  min-width: 44px !important;
  padding: 6px 5px !important;
  z-index: 22000 !important;
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
