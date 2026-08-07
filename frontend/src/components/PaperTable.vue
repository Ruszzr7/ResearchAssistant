<template>
  <div ref="tableShellRef" class="paper-table-shell">
  <vxe-grid
    class="paper-table-vxe"
    :data="papers"
    :loading="loading"
    :columns="columns"
    border
    :sort-config="sortConfig"
    :column-config="{ resizable: false }"
    :row-config="{ isCurrent: false, isHover: true, keyField: 'id' }"
    :checkbox-config="checkboxConfig"
    :pager-config="pagerConfig"
    :empty-text="emptyText"
    :row-class-name="rowClassName"
    :stripe="true"
    @sort-change="onSortChange"
    @page-change="onPageChange"
    @checkbox-change="onCheckboxChange"
    @checkbox-all="onCheckboxAll"
    @cell-dblclick="onCellDblclick"
  >
    <template #title_default="{ row }">
      <div class="scroll-cell" :title="row.title">{{ row.title }}</div>
    </template>

    <template #category_default="{ row }">
      <span>{{ categoryLabel(row) }}</span>
    </template>

    <template #tags_default="{ row }">
      <div class="tags-cell" :class="{ empty: !(row.tags||[]).length }" @click.stop="$emit('tag-click', row)">
        <el-tag v-for="t in (row.tags||[]).slice(0,2)" :key="t.id" size="small" style="margin-right:4px">{{ t.name }}</el-tag>
        <span v-if="(row.tags||[]).length > 2" style="font-size:11px;color:#909399">+{{ row.tags.length - 2 }}</span>
        <span v-if="!(row.tags||[]).length" style="color:#c0c4cc;font-size:12px">点击添加标签</span>
      </div>
    </template>

    <template #source_default="{ row }">
      <div class="scroll-cell" :title="row.source || ''">{{ row.source || '--' }}</div>
    </template>

    <template #status_default="{ row }">
      <el-dropdown trigger="click" @command="s => $emit('status-change', row, s)" @click.stop
>
        <el-tag :type="statusType(row.readingStatus)" size="small" class="status-tag" effect="light" style="cursor:pointer">{{ statusLabel(row.readingStatus) }}</el-tag>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-for="s in statusOptions" :key="s.value" :command="s.value">
              <span class="status-dot" :class="'status-' + s.value.toLowerCase()"></span>{{ s.label }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </template>

    <template #createdAt_default="{ row }">
      <span>{{ formatDate(row.createdAt) }}</span>
    </template>

    <template #actions_default="{ row }">
      <div class="action-btns" @click.stop
>
        <el-button size="small" text type="primary" @click="$emit('analyze', row.id)">论文分析</el-button>
        <el-button size="small" text @click="$emit('info', row.id)">信息</el-button>
        <el-dropdown trigger="click" @command="cmd => $emit('action', cmd, row)"
>
          <el-button size="small" text class="action-more">···</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="edit">编辑</el-dropdown-item>
              <el-dropdown-item command="top">{{ row.pinned ? '取消置顶' : '置顶' }}</el-dropdown-item>
              <el-dropdown-item command="delete" style="color:#f56c6c">删除</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </template>
  </vxe-grid>
  </div>
</template>

<script setup>
import { VxeGrid } from 'vxe-table'
import 'vxe-table/lib/style.css'

import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { mergePageSelection } from '@/utils/selection.js'

const props = defineProps({
  papers: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  total: { type: Number, default: 0 },
  page: { type: Number, default: 1 },
  size: { type: Number, default: 20 },
  selectedIds: { type: Array, default: () => [] },
  currentPaperId: { type: [Number, String], default: null },
  sortBy: { type: String, default: 'created_at' },
  sortDir: { type: String, default: 'DESC' }
})

const emit = defineEmits([
  'page-change',
  'sort-change',
  'selection-change',
  'tag-click',
  'status-change',
  'analyze',
  'info',
  'action',
  'open-pdf'
])

const statusOptions = [
  { label: '未读', value: 'UNREAD', type: 'info' },
  { label: '正读', value: 'READING', type: 'danger' },
  { label: '已读', value: 'READ', type: 'success' }
]

const sortConfig = computed(() => ({
  trigger: 'default',
  defaultSort: {
    field: props.sortBy === 'created_at' ? 'createdAt' : props.sortBy,
    order: props.sortDir === 'ASC' ? 'asc' : 'desc'
  }
}))

const checkboxConfig = computed(() => ({
  checkRowKeys: props.selectedIds,
  highlight: true,
  range: true
}))

const pagerConfig = computed(() => ({
  enabled: props.total > 0,
  currentPage: props.page,
  pageSize: props.size,
  total: props.total,
  layouts: ['PrevPage', 'JumpNumber', 'NextPage', 'FullJump', 'Total']
}))

const emptyText = '暂无论文 — 点击左上角「导入」添加第一篇论文'

const columns = ref([
  { type: 'checkbox', width: 40, fixed: 'left', align: 'center', resizable: false },
  { field: 'title', title: '标题', width: 260, sortable: true, headerAlign: 'left', align: 'left', showOverflow: false, slots: { default: 'title_default' } },
  { field: 'category', title: '类目', width: 80, headerAlign: 'left', align: 'left', slots: { default: 'category_default' } },
  { field: 'tags', title: '标签', width: 130, headerAlign: 'left', align: 'left', slots: { default: 'tags_default' } },
  { field: 'readingStatus', title: '状态', width: 80, headerAlign: 'left', align: 'left', slots: { default: 'status_default' } },
  { field: 'source', title: '期刊/会议', width: 180, sortable: true, headerAlign: 'left', align: 'left', showOverflow: false, slots: { default: 'source_default' } },
  { field: 'year', title: '出版年份', width: 90, sortable: true, headerAlign: 'left', align: 'left' },
  { field: 'createdAt', title: '导入年份', width: 100, sortable: true, headerAlign: 'left', align: 'left', resizable: false, slots: { default: 'createdAt_default' } },
  { field: 'actions', title: '操作', width: 150, minWidth: 150, maxWidth: 150, fixed: 'right', headerAlign: 'center', align: 'center', resizable: false, slots: { default: 'actions_default' } }
])

const tableShellRef = ref(null)
let tableResizeObserver = null

function applyColumnWidths(totalWidth) {
  const total = Math.max(0, Math.floor(totalWidth || 0))
  if (!total) return

  const fixedWidth = 40 + 150
  const preferred = { title: 250, category: 76, tags: 118, readingStatus: 82, source: 175, year: 108, createdAt: 108 }
  const minimum = { title: 68, category: 48, tags: 62, readingStatus: 58, source: 72, year: 92, createdAt: 88 }
  const fields = Object.keys(preferred)
  const available = Math.max(0, total - fixedWidth)
  const preferredSum = fields.reduce((sum, field) => sum + preferred[field], 0)
  const minimumSum = fields.reduce((sum, field) => sum + minimum[field], 0)
  const widths = {}

  if (available >= preferredSum) {
    fields.forEach(field => { widths[field] = preferred[field] })
    const extra = available - preferredSum
    const titleExtra = Math.ceil(extra * 0.6)
    widths.title += titleExtra
    widths.source += extra - titleExtra
  } else if (available >= minimumSum) {
    fields.forEach(field => { widths[field] = minimum[field] })
    let extra = available - minimumSum
    fields.forEach(field => {
      const share = preferred[field] - minimum[field]
      const addition = Math.min(share, Math.floor(extra * share / (preferredSum - minimumSum)))
      widths[field] += addition
    })
    const assigned = fields.reduce((sum, field) => sum + widths[field], 0)
    widths.source += available - assigned
  } else {
    const scale = available / minimumSum
    fields.forEach(field => { widths[field] = Math.max(1, Math.floor(minimum[field] * scale)) })
    const assigned = fields.reduce((sum, field) => sum + widths[field], 0)
    widths.source += available - assigned
  }

  columns.value = columns.value.map(column => {
    const width = widths[column.field]
    return width == null ? column : { ...column, width, minWidth: width }
  })
}

onMounted(() => {
  if (typeof ResizeObserver === 'undefined' || !tableShellRef.value) return
  tableResizeObserver = new ResizeObserver(entries => {
    const width = entries[0]?.contentRect?.width
    if (width) applyColumnWidths(width)
  })
  tableResizeObserver.observe(tableShellRef.value)
})

onBeforeUnmount(() => tableResizeObserver?.disconnect())

function rowClassName({ row }) {
  const classes = []
  if (row.id === props.currentPaperId) classes.push('row-active')
  if (row.pinned) classes.push('row-pinned')
  return classes.join(' ')
}

function onSortChange({ sortList }) {
  if (!sortList || !sortList.length) return
  const { field, order } = sortList[0]
  const sortBy = field === 'createdAt' ? 'created_at' : field
  const sortDir = order === 'asc' ? 'ASC' : 'DESC'
  emit('sort-change', sortBy, sortDir)
}

function onPageChange({ currentPage }) {
  emit('page-change', currentPage)
}

function onCheckboxChange({ row, checked }) {
  emit('selection-change', mergePageSelection(props.selectedIds, [row], checked))
}

function onCheckboxAll({ checked, records }) {
  emit('selection-change', mergePageSelection(props.selectedIds, records, checked))
}

function onCellDblclick({ row }) {
  if (row.pdfPath) {
    emit('open-pdf', row)
  }
}

function formatDate(d) {
  return d?.substring(0, 7) || ''
}

function statusLabel(v) {
  return statusOptions.find(o => o.value === v)?.label || v || '--'
}

function statusType(v) {
  return statusOptions.find(o => o.value === v)?.type || 'info'
}

function categoryLabel(paper) {
  if (paper.arxivId) return '预印本'
  if (paper.source) {
    const s = paper.source.toLowerCase()
    if (s.includes('journal') || s.includes('letters') || s.includes('transactions') || s.includes('magazine')) return '期刊'
    if (s.includes('conference') || s.includes('proceedings') || s.includes('symposium') || s.includes('workshop')) return '会议'
    return '期刊/会议'
  }
  return '--'
}
</script>

<style scoped>
.paper-table-vxe {
  flex: 1;
  width: 100%;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
}
.paper-table-shell {
  flex: 1;
  width: 100%;
  min-width: 0;
  min-height: 0;
  display: flex;
  overflow: hidden;
}
.scroll-cell {
  width: 100%;
  max-width: 100%;
  overflow-x: auto;
  overflow-y: hidden;
  white-space: nowrap;
  scrollbar-width: thin;
}
.scroll-cell::-webkit-scrollbar {
  height: 4px;
}
.scroll-cell::-webkit-scrollbar-thumb {
  background: var(--ra-border);
  border-radius: 2px;
}
.tags-cell {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  gap: 2px;
  min-height: 24px;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  cursor: pointer;
}
.tags-cell :deep(.el-tag) { flex-shrink: 0; }
.tags-cell.empty {
  color: var(--ra-text-tertiary);
}
.status-tag {
  cursor: pointer;
}
.action-btns {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  white-space: nowrap;
}
.action-btns .el-button {
  padding: 0 4px !important;
  min-width: auto !important;
  margin-left: 0 !important;
}
.status-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-right: 6px;
}
.status-unread { background: var(--ra-text-tertiary); }
.status-reading { background: #f56c6c; }
.status-read { background: #67c23a; }

/* 斑马纹：浅灰交替，暗色模式下用不同深度的黑灰 */
:deep(.vxe-body--row.row--stripe .vxe-body--column) { background-color: #f0f2f5 !important; }

/* 当前查看行高亮 */
:deep(.vxe-body--row.row-active .vxe-body--column) { background-color: #ecf5ff !important; }

html.dark .paper-table-vxe .vxe-body--column,
html.dark .paper-table-vxe .vxe-header--column { border-color: var(--ra-border); }
html.dark .paper-table-vxe .vxe-table--body-wrapper { background-color: var(--ra-panel-bg); }
</style>

<style>
/* 非 scoped：vxe-table 渲染的行不在组件作用域内，需要全局选择器 */
.paper-table-vxe .vxe-body--row.row-pinned .vxe-body--column:first-child { border-left: 3px solid #0958a3 !important; }
html.dark .paper-table-vxe .vxe-body--row.row-pinned .vxe-body--column:first-child { border-left-color: #79bbff !important; }

/* 暗色模式：非斑马行用面板底色，斑马行用略深的颜色，高亮行用中性灰（避免深蓝） */
html.dark .paper-table-vxe .vxe-body--row .vxe-body--column { background-color: #232428 !important; }
html.dark .paper-table-vxe .vxe-body--row.row--stripe .vxe-body--column { background-color: #1e1f23 !important; }
html.dark .paper-table-vxe .vxe-body--row.row-active .vxe-body--column { background-color: #2f3136 !important; }
html.dark .paper-table-vxe .vxe-body--row.row--hover:not(.row-active) .vxe-body--column { background-color: #2a2c31 !important; }

/* 列总宽度由组件计算填满表格，标题和来源内容在单元格内独立横向滚动。 */
.paper-table-vxe .vxe-table--header-wrapper,
.paper-table-vxe .vxe-table--body-wrapper {
  overflow-x: hidden !important;
}

/* 表头文字和排序图标始终同一行，单元格内容左右保留少量留白。 */
.paper-table-vxe .vxe-header--column .vxe-cell {
  display: flex !important;
  align-items: center;
  gap: 3px;
  min-width: 0;
  padding-left: 8px !important;
  padding-right: 8px !important;
  white-space: nowrap !important;
  word-break: keep-all;
  min-height: 42px;
  line-height: 1;
}
.paper-table-vxe .vxe-header--column .vxe-cell--title {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap !important;
  text-overflow: ellipsis;
  line-height: 16px;
}
.paper-table-vxe .vxe-header--column .vxe-cell--sort {
  display:inline-flex;
  align-items:center;
  flex: 0 0 auto;
  white-space: nowrap;
}
.paper-table-vxe .vxe-pager .vxe-pager--jump .vxe-input {
  width: 34px !important;
  min-width: 34px !important;
}
.paper-table-vxe .vxe-pager .vxe-pager--jump .vxe-input--inner {
  padding: 0 3px;
  text-align: center;
}
.paper-table-vxe .vxe-body--column .vxe-cell {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  word-break: keep-all;
  padding-left: 8px !important;
  padding-right: 8px !important;
}
</style>
