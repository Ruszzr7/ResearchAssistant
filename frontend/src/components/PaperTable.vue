<template>
  <vxe-grid
    class="paper-table-vxe"
    :data="papers"
    :loading="loading"
    :columns="columns"
    :sort-config="sortConfig"
    :column-config="{ resizable: true }"
    :row-config="{ isCurrent: true, isHover: true, keyField: 'id' }"
    :checkbox-config="checkboxConfig"
    :pager-config="pagerConfig"
    :empty-text="emptyText"
    :row-class-name="rowClassName"
    :stripe="true"
    @sort-change="onSortChange"
    @page-change="onPageChange"
    @checkbox-change="onCheckboxChange"
    @checkbox-all="onCheckboxAll"
  >
    <template #title_default="{ row }">
      <span class="cell-title" :title="row.title">{{ row.title }}</span>
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
</template>

<script setup>
import { computed } from 'vue'

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
  'action'
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

const emptyText = '暂无论文 — 点击左上角「+ 导入」添加第一篇论文'

const columns = [
  { type: 'checkbox', width: 40, fixed: 'left', align: 'center' },
  { field: 'title', title: '标题', minWidth: 160, sortable: true, headerAlign: 'center', align: 'left', slots: { default: 'title_default' } },
  { field: 'category', title: '类目', width: 80, headerAlign: 'center', align: 'left', slots: { default: 'category_default' } },
  { field: 'tags', title: '标签', width: 130, headerAlign: 'center', align: 'left', slots: { default: 'tags_default' } },
  { field: 'readingStatus', title: '状态', width: 80, headerAlign: 'center', align: 'left', slots: { default: 'status_default' } },
  { field: 'source', title: '期刊/会议', width: 140, sortable: true, headerAlign: 'center', align: 'left', showOverflow: true },
  { field: 'year', title: '出版年份', width: 90, sortable: true, headerAlign: 'center', align: 'left' },
  { field: 'createdAt', title: '导入年份', width: 100, sortable: true, headerAlign: 'center', align: 'left', slots: { default: 'createdAt_default' } },
  { field: 'actions', title: '操作', width: 150, fixed: 'right', headerAlign: 'center', align: 'left', slots: { default: 'actions_default' } }
]

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
  const set = new Set(props.selectedIds)
  if (checked) set.add(row.id)
  else set.delete(row.id)
  emit('selection-change', [...set])
}

function onCheckboxAll({ checked, records }) {
  const set = new Set(props.selectedIds)
  for (const row of records) {
    if (checked) set.add(row.id)
    else set.delete(row.id)
  }
  emit('selection-change', [...set])
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
  min-height: 0;
}
.cell-title {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tags-cell {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px;
  min-height: 24px;
  cursor: pointer;
}
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
  gap: 2px;
}
.action-more {
  padding: 0 4px !important;
  min-width: auto !important;
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

/* 斑马纹：白 / 浅灰交替 */
:deep(.vxe-body--row.row--stripe .vxe-body--column) { background-color: #fafbfc; }
html.dark :deep(.vxe-body--row.row--stripe .vxe-body--column) { background-color: var(--ra-hover-bg); }

/* 当前查看行高亮 */
:deep(.vxe-body--row.row-active .vxe-body--column) { background-color: var(--ra-active-bg) !important; }

html.dark .paper-table-vxe .vxe-body--column,
html.dark .paper-table-vxe .vxe-header--column { border-color: var(--ra-border); }
html.dark .paper-table-vxe .vxe-table--body-wrapper { background-color: var(--ra-panel-bg); }
</style>

<style>
/* 非 scoped：vxe-table 渲染的行不在组件作用域内，需要全局选择器 */
.paper-table-vxe .vxe-body--row.row-pinned .vxe-body--column:first-child { border-left: 3px solid #0958a3 !important; }
html.dark .paper-table-vxe .vxe-body--row.row-pinned .vxe-body--column:first-child { border-left-color: #79bbff !important; }
</style>
