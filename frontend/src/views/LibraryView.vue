<template>
  <div class="library" :class="{ 'is-resizing': resizing }" @mousemove="onResize" @mouseup="stopResize" @mouseleave="stopResize">

    <!-- ==================== 左栏 ==================== -->
    <div class="left-panel" :class="{ collapsed: !sidebarVisible }" :style="{ width: sidebarVisible ? leftWidth + 'px' : '0px' }">
      <div class="panel-header">
        <el-button size="small" text style="padding:2px 4px;min-width:auto" @click="openNewFolderForm">
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M2 4.5A1.5 1.5 0 013.5 3h3l1.5 2h4A1.5 1.5 0 0113.5 6.5v5A1.5 1.5 0 0112 13H4a1.5 1.5 0 01-1.5-1.5z"/><path d="M8 8v3M6.5 9.5h3"/></svg>
        </el-button>
        <el-button size="small" text style="padding:2px 4px;min-width:auto" @click="openEditFoldersDialog">
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M3 12l-1 1 1 1 1-1M12 4l1-1-1-1-1 1M4 12l8-8"/><rect x="2" y="2" width="12" height="12" rx="1"/></svg>
        </el-button>
        <el-dropdown trigger="click" @command="handleFolderSort" style="display:inline-flex">
          <el-button size="small" text style="padding:2px 4px;min-width:auto">
            <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M2 3.5A1.5 1.5 0 013.5 2h3l1.5 2h4A1.5 1.5 0 0113.5 5.5v5A1.5 1.5 0 0112 12H4a1.5 1.5 0 01-1.5-1.5z"/><path d="M9 4.5l1.5 1.5L9 7.5M9 11.5l1.5-1.5L9 8.5"/></svg>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="custom-asc">默认排序</el-dropdown-item>
              <el-dropdown-item command="alpha-asc" divided>字母正序</el-dropdown-item>
              <el-dropdown-item command="alpha-desc">字母倒序</el-dropdown-item>
              <el-dropdown-item command="count-asc">文献数正序</el-dropdown-item>
              <el-dropdown-item command="count-desc">文献数倒序</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <div class="header-search">
          <el-button size="small" text style="padding:2px 4px;min-width:auto" @click="toggleFolderSearch">
            <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="6.5" cy="6.5" r="4"/><path d="M9.5 9.5L13 13"/></svg>
          </el-button>
          <el-input v-if="showFolderSearch" v-model="folderSearchKeyword" placeholder="搜文件夹…" size="small" clearable class="search-input" ref="folderSearchRef" />
        </div>
      </div>

      <!-- 新建文件夹表单 -->
      <div v-if="showNewFolderForm" class="inline-form">
        <el-tree-select v-model="newFolderParentId" :data="folders" :props="treeProps"
          check-strictly node-key="id" placeholder="父文件夹" clearable size="small" style="flex:1;min-width:0" />
        <el-input v-model="newFolderName" placeholder="文件夹名" size="small" style="flex:1;min-width:0" @keyup.enter="createFolder" />
        <div class="inline-form-actions">
          <el-button size="small" type="primary" @click="createFolder">创建</el-button>
          <el-button size="small" @click="showNewFolderForm = false">取消</el-button>
        </div>
      </div>

      <div class="folder-all" :class="{ active: currentFolder === null }" @click="clearFolderFilter">
        <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M2 4.5A1.5 1.5 0 013.5 3h3l1.5 2h4A1.5 1.5 0 0113.5 6.5v5A1.5 1.5 0 0112 13H4a1.5 1.5 0 01-1.5-1.5z"/></svg>
        <span>我的文库</span>
      </div>

      <el-tree ref="treeRef" :data="sortedFolders" :props="treeProps" node-key="id"
        highlight-current :current-node-key="selectedFolderId" @node-click="onFolderClick" class="folder-tree"
        :filter-node-method="filterFolderNode">
        <template #default="{ data }">
          <span class="tree-node-label" :class="{
            'path-0': pathKeys.get(data.id) === 0,
            'path-1': pathKeys.get(data.id) === 1,
            'path-2': pathKeys.get(data.id) === 2,
          }">
            <span class="folder-name">{{ data.name }}</span>
            <span class="folder-count">({{ data.paperCount ?? 0 }})</span>
          </span>
        </template>
      </el-tree>

      <!-- 筛选（底部） -->
      <div class="filter-section">
        <h4>筛选</h4>
        <div class="filter-group">
          <span class="filter-label">阅读状态</span>
          <el-select v-model="filterStatus" placeholder="全部" clearable size="small" class="w-full" @change="loadPapers">
            <el-option label="全部" value="" />
            <el-option label="未读" value="UNREAD" />
            <el-option label="略读" value="SKIMMED" />
            <el-option label="精读" value="CLOSE_READ" />
            <el-option label="已归档" value="ARCHIVED" />
          </el-select>
        </div>
        <div class="filter-group">
          <span class="filter-label">标签</span>
          <el-select v-model="filterTag" placeholder="全部" clearable size="small" class="w-full" @change="loadPapers">
            <el-option label="全部" :value="null" />
            <el-option v-for="t in allTags" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </div>
      </div>
    </div>

    <!-- 分割线 -->
    <div v-if="sidebarVisible" class="divider" :class="{ active: resizing === 'left' }" @mousedown="startResize($event, 'left')"><div class="divider-handle"></div></div>

    <!-- ==================== 中栏 ==================== -->
    <div class="center-panel">
      <div class="toolbar">
        <div class="toolbar-left">
          <el-button size="small" text style="padding:2px 4px;min-width:auto" @click="sidebarVisible = !sidebarVisible">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="2" y="2" width="4" height="12" rx="1"/><rect x="6" y="2" width="8" height="12" rx="1"/></svg>
          </el-button>
          <span class="toolbar-divider"></span>
          <el-button size="small" text style="padding:2px 4px;min-width:auto" @click="openImportDialog">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M2 10v2.5A1.5 1.5 0 003.5 14h9a1.5 1.5 0 001.5-1.5V10M8 2v9M5 8l3 3 3-3"/></svg>
          </el-button>
        </div>
        <div class="toolbar-right">
          <el-input v-model="paperSearchKeyword" placeholder="搜索论文…" size="small" clearable class="toolbar-search" @input="onPaperSearch" />
          <span class="toolbar-divider"></span>
          <el-button size="small" text style="padding:2px 4px;min-width:auto" @click="currentPaper = null" :disabled="!currentPaper">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="10" y="2" width="4" height="12" rx="1"/><rect x="2" y="2" width="8" height="12" rx="1"/></svg>
          </el-button>
        </div>
      </div>

      <!-- 表格 -->
      <div class="table-wrapper">
        <table class="paper-table">
          <thead>
            <tr>
              <th class="col-title" @click="toggleSort('title')">标题 <span class="sort-arrow">{{ sortLabel('title') }}</span></th>
              <th class="col-year" @click="toggleSort('year')">出版年份 <span class="sort-arrow">{{ sortLabel('year') }}</span></th>
              <th class="col-created" @click="toggleSort('created_at')">导入年份 <span class="sort-arrow">{{ sortLabel('created_at') }}</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(paper, idx) in papers" :key="paper.id"
                :class="{ 'row-active': currentPaper?.id === paper.id, 'row-stripe': idx % 2 === 1 }"
                @click="selectPaper(paper.id)">
              <td class="col-title">{{ paper.title }}</td>
              <td class="col-year">{{ paper.year }}</td>
              <td class="col-created">{{ formatDate(paper.createdAt) }}</td>
            </tr>
            <tr v-if="papers.length === 0">
              <td colspan="3" class="empty-row">暂无论文</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="pagination-bar" v-if="pagination.total > 0">
        <el-pagination v-model:current-page="pagination.page" :page-size="pagination.size"
          :total="pagination.total" layout="prev, pager, next" size="small" @current-change="loadPapers" />
      </div>
    </div>

    <!-- 分割线 -->
    <div v-if="currentPaper" class="divider" :class="{ active: resizing === 'right' }" @mousedown="startResize($event, 'right')"><div class="divider-handle"></div></div>

    <!-- ==================== 右栏 ==================== -->
    <div class="right-panel" v-if="currentPaper" :style="{ width: rightWidth + 'px' }">
      <div class="detail-title-row">
        <input v-if="editingTitle" v-model="editTitleText" class="title-input"
          @blur="saveTitle" @keyup.enter="saveTitle" ref="titleInputRef" />
        <h2 v-else class="detail-title" @click="startEditTitle">{{ currentPaper.title }}</h2>
      </div>
      <div class="detail-divider"></div>
      <div class="detail-item"><span class="label">作者</span>{{ formatAuthors(currentPaper.authors) }}</div>
      <div class="detail-item"><span class="label">年份</span>{{ currentPaper.year }}</div>
      <div class="detail-item"><span class="label">来源</span>{{ currentPaper.source }}</div>
      <div class="detail-item"><span class="label">DOI</span>{{ currentPaper.doi }}</div>
      <div class="detail-item"><span class="label">摘要</span>{{ currentPaper.abstractText || '暂无摘要' }}</div>
      <div class="detail-item"><span class="label">关键词</span>{{ currentPaper.keywords }}</div>
      <div class="detail-item">
        <span class="label">阅读状态</span>
        <el-select v-model="currentPaper.readingStatus" size="small" @change="savePaper(currentPaper)">
          <el-option label="未读" value="UNREAD" /><el-option label="略读" value="SKIMMED" />
          <el-option label="精读" value="CLOSE_READ" /><el-option label="已归档" value="ARCHIVED" />
        </el-select>
      </div>
      <div class="detail-item">
        <span class="label">标签</span>
        <el-tag v-for="t in currentPaper.tags" :key="t.id" size="small">{{ t.name }}</el-tag>
      </div>
      <div class="detail-actions">
        <el-button size="small" @click="openEditDialog">编辑</el-button>
        <el-button size="small" type="danger" @click="deletePaper">删除</el-button>
      </div>
    </div>

    <!-- ==================== 编辑文件夹弹窗 ==================== -->
    <el-dialog v-model="showEditFoldersDialog" title="编辑文件夹" width="500px">
      <p style="font-size:12px;color:#909399;margin:0 0 8px">点击选中文件夹，操作后点「确认」保存</p>
      <div style="margin-bottom:8px;display:flex;gap:4px;flex-wrap:wrap">
        <el-button size="small" @click="editMoveUp" :disabled="!editCanMoveUp">↑ 上移</el-button>
        <el-button size="small" @click="editMoveDown" :disabled="!editCanMoveDown">↓ 下移</el-button>
        <el-button size="small" @click="editMoveOut" :disabled="!editFolderId || editSelectedParentId == null">↩ 移出</el-button>
        <el-button size="small" @click="startMoveIn" :disabled="!editFolderId">↪ 移入</el-button>
        <el-button size="small" type="danger" @click="editDelete" :disabled="!editFolderId">删除</el-button>
      </div>
      <div v-if="moveInSource" style="margin-bottom:4px;padding:4px 8px;background:#ecf5ff;border-radius:3px;font-size:12px">
        将「{{ moveInSource.name }}」移入到 → 点击目标文件夹 | <el-button size="small" text @click="moveInSource=null">取消</el-button>
      </div>
      <el-tree :data="editFolders" :props="treeProps" node-key="id"
        :key="editTreeKey"
        highlight-current :current-node-key="editFolderId"
        @node-click="onEditTreeClick"
        style="max-height:300px;overflow-y:auto"
      />
      <div style="margin-top:8px;display:flex;gap:4px">
        <el-input v-model="editFolderName" placeholder="重命名" size="small" style="flex:1" />
        <el-button size="small" @click="editRename" :disabled="!editFolderId||!editFolderName.trim()">重命名</el-button>
      </div>
      <template #footer>
        <el-button @click="cancelEditFolders">取消</el-button>
        <el-button type="primary" @click="confirmEditFolders">确认</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 导入对话框 ==================== -->
    <el-dialog v-model="dialogVisible" :title="isEditing ? '编辑论文' : '导入论文'" width="560px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="标题"><el-input v-model="form.title" /></el-form-item>
        <el-form-item label="作者"><el-input v-model="form.authors" placeholder='[{"name":"xxx","role":"first"}]' /></el-form-item>
        <el-form-item label="年份"><el-input-number v-model="form.year" :min="1900" :max="2030" /></el-form-item>
        <el-form-item label="来源"><el-input v-model="form.source" /></el-form-item>
        <el-form-item label="DOI"><el-input v-model="form.doi" /></el-form-item>
        <el-form-item label="摘要"><el-input v-model="form.abstractText" type="textarea" rows="3" /></el-form-item>
        <el-form-item label="关键词"><el-input v-model="form.keywords" placeholder="逗号分隔" /></el-form-item>
        <el-form-item label="文件夹">
          <el-tree-select v-model="form.folderId" :data="folders" :props="treeProps"
            check-strictly node-key="id" placeholder="选择文件夹" clearable class="w-full" />
        </el-form-item>
        <el-form-item label="阅读状态">
          <el-select v-model="form.readingStatus" class="w-full">
            <el-option label="未读" value="UNREAD" /><el-option label="略读" value="SKIMMED" />
            <el-option label="精读" value="CLOSE_READ" /><el-option label="已归档" value="ARCHIVED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitPaper">{{ isEditing ? '保存' : '导入' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import axios from 'axios'

const treeRef = ref(null)
const pathKeys = ref(new Map())
const selectedFolderId = ref(null)
const expandedId = ref(null)
const folders = ref([])
const papers = ref([])
const currentPaper = ref(null)
const currentFolder = ref(null)
const folderSearchKeyword = ref('')
const filterStatus = ref('')
const filterTag = ref(null)
const allTags = ref([])
const paperSearchKeyword = ref('')
const showFolderSearch = ref(false)
const folderSearchRef = ref(null)
const pagination = ref({ page: 1, size: 20, total: 0 })
const showNewFolderForm = ref(false)
const newFolderName = ref('')
const newFolderParentId = ref(null)
const dialogVisible = ref(false)
const isEditing = ref(false)
const editPaperId = ref(null)
const form = ref(makeEmptyForm())
const sidebarVisible = ref(true)
const sortBy = ref('created_at')
const sortDir = ref('DESC')
const folderSortMode = ref('custom')
const folderSortDir = ref('ASC')
const leftWidth = ref(220)
const rightWidth = ref(300)
const editingTitle = ref(false)
const editTitleText = ref('')
const titleInputRef = ref(null)
const resizing = ref(null)

const DEFAULT_YEAR = 2025
const treeProps = { children: 'children', label: 'name' }

function makeEmptyForm() {
  return { title: '', authors: '', year: DEFAULT_YEAR, source: '', doi: '',
    abstractText: '', keywords: '', folderId: null, readingStatus: 'UNREAD' }
}

const sortedFolders = computed(() => {
  const arr = JSON.parse(JSON.stringify(folders.value))
  sortFolders(arr)
  return arr
})

function sortFolders(nodes) {
  if (folderSortMode.value === 'custom') {
    nodes.sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
  } else {
    nodes.sort((a, b) => {
      let v = folderSortMode.value === 'alpha'
        ? (a.name || '').localeCompare(b.name || '')
        : (a.paperCount || 0) - (b.paperCount || 0)
      return folderSortDir.value === 'ASC' ? -v : v
    })
  }
  for (const n of nodes) { if (n.children?.length) sortFolders(n.children) }
}

function formatAuthors(json) {
  try { return (typeof json==='string'?JSON.parse(json):json||[]).map(a=>(a.name||'')+(a.role==='corresponding'?'*':'')).join(', ') }
  catch { return json||'' }
}
function formatDate(d) { return d?.substring(0,7)||'' }
function sortLabel(col) { return sortBy.value!==col?'↕':sortDir.value==='ASC'?'↑':'↓' }
function toggleSort(col) { sortDir.value=sortBy.value===col?(sortDir.value==='ASC'?'DESC':'ASC'):'ASC'; sortBy.value=col; loadPapers() }
function handleFolderSort(cmd) {
  const [mode, dir] = cmd.split('-')
  folderSortMode.value = mode
  folderSortDir.value = dir === 'asc' ? 'ASC' : 'DESC'
}

async function loadFolders() { const r=await axios.get('/api/folders'); folders.value=r.data.data }
async function loadPapers() {
  const r=await axios.get('/api/papers',{params:{folder:currentFolder.value,keyword:paperSearchKeyword.value||null,tag:filterTag.value||null,status:filterStatus.value||null,sortBy:sortBy.value,sortDir:sortDir.value,page:pagination.value.page,size:pagination.value.size}})
  const d=r.data.data; papers.value=d.records; pagination.value.total=d.total; pagination.value.page=d.current
  const tagSet=new Map(); for(const p of d.records){if(p.tags)p.tags.forEach(t=>tagSet.set(t.id,t))}
  allTags.value=Array.from(tagSet.values())
}
async function selectPaper(id) { const r=await axios.get(`/api/papers/${id}`); currentPaper.value=r.data.data }

let paperSearchTimer=null
function onPaperSearch() { clearTimeout(paperSearchTimer); paperSearchTimer=setTimeout(()=>loadPapers(),300) }

/** 文件夹名过滤（客户端） */
function filterFolderNode(value, data) {
  if (!value) return true
  return (data.name || '').toLowerCase().includes(value.toLowerCase())
}
watch(folderSearchKeyword, v => treeRef.value?.filter(v))

function collectAllIds(nodes) { const ids=[]; for(const n of nodes){ids.push(n.id);if(n.children)ids.push(...collectAllIds(n.children))} return ids }
function collectFolderIds(node) { return collectAllIds([node]) }
function findPathToNode(tree,targetId,path=[]) {
  for(const n of tree){ const np=[...path,n.id]; if(n.id===targetId)return np; if(n.children?.length){const f=findPathToNode(n.children,targetId,np);if(f)return f} }
  return null
}

function onFolderClick(node) {
  currentFolder.value=collectFolderIds(node).join(','); selectedFolderId.value=node.id
  const path=findPathToNode(folders.value,node.id)||[]; const map=new Map(); path.forEach((id,i)=>map.set(id,i)); pathKeys.value=map
  if(expandedId.value===node.id){treeRef.value?.getNode(node.id)?.collapse();expandedId.value=null}
  else{if(expandedId.value)treeRef.value?.getNode(expandedId.value)?.collapse();for(const id of path)treeRef.value?.getNode(id)?.expand();expandedId.value=node.id}
  loadPapers()
}
function clearFolderFilter() {
  currentFolder.value=null;selectedFolderId.value=null;expandedId.value=null;pathKeys.value=new Map()
  collectAllIds(folders.value).forEach(id=>treeRef.value?.getNode(id)?.collapse());loadPapers()
}
// ===== 编辑文件夹弹窗（本地操作 + 确认批量提交） =====
const showEditFoldersDialog = ref(false)
const editFolderId = ref(null)
const editFolderName = ref('')
const editFolders = ref([]) // 本地副本
const moveInSource = ref(null)
const editOps = ref([]) // 待提交操作队列: [{type, id, body}]

function openEditFoldersDialog() {
  editFolderId.value = null; editFolderName.value = ''; moveInSource.value = null
  editFolders.value = JSON.parse(JSON.stringify(folders.value))
  editOps.value = []
  editTreeKey.value++
  showEditFoldersDialog.value = true
}

const editTreeKey = ref(0)
function cancelEditFolders() { showEditFoldersDialog.value = false }
function refreshEditFolders() { editTreeKey.value++ }

// 记录操作到队列
function addEditOp(type, id, body) { editOps.value.push({ type, id, body }) }

async function confirmEditFolders() {
  try {
    for (const op of editOps.value) {
      if (op.type === 'move') await axios.put(`/api/folders/${op.id}/move`, op.body)
      else if (op.type === 'rename') await axios.put(`/api/folders/${op.id}`, op.body)
      else if (op.type === 'delete') await axios.delete(`/api/folders/${op.id}`)
    }
    showEditFoldersDialog.value = false
    await loadFolders()
  } catch (e) {
    alert('操作失败：' + (e.response?.data?.message || e.message))
  }
}

const editSelectedParentId = computed(() => {
  if (!editFolderId.value) return undefined
  const f = findFolderById(editFolders.value, editFolderId.value)
  return f?.parentId ?? null
})

const editCanMoveUp = computed(() => {
  if (!editFolderId.value) return false
  const folder = findFolderById(editFolders.value, editFolderId.value)
  if (!folder) return false
  const all = flattenTree(editFolders.value)
  const siblings = all.filter(f => (f.parentId ?? null) === (folder.parentId ?? null))
  siblings.sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
  return siblings.findIndex(f => f.id === folder.id) > 0
})

const editCanMoveDown = computed(() => {
  if (!editFolderId.value) return false
  const folder = findFolderById(editFolders.value, editFolderId.value)
  if (!folder) return false
  const all = flattenTree(editFolders.value)
  const siblings = all.filter(f => (f.parentId ?? null) === (folder.parentId ?? null))
  siblings.sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
  const idx = siblings.findIndex(f => f.id === folder.id)
  return idx >= 0 && idx < siblings.length - 1
})

function onEditTreeClick(node) {
  if (moveInSource.value) {
    if (node.id === moveInSource.value.id) return
    moveFolderToLocal(node.id)
    return
  }
  editFolderId.value = node.id
}

// === 本地修改 editFolders，不调 API ===
function findFolderById(nodes, id) {
  for (const n of nodes) { if (n.id === id) return n; if (n.children) { const f = findFolderById(n.children, id); if (f) return f } }
  return null
}
function flattenTree(nodes, result = []) {
  for (const n of nodes) { result.push(n); if (n.children) flattenTree(n.children, result) }
  return result
}
// 从树中移除节点
function removeFromTree(nodes, id) {
  for (let i = 0; i < nodes.length; i++) {
    if (nodes[i].id === id) { nodes.splice(i, 1); return true }
    if (nodes[i].children && removeFromTree(nodes[i].children, id)) return true
  }
  return false
}
// 把节点加到目标父节点下
function addToTree(nodes, targetId, node) {
  if (targetId == null) { nodes.push(node); return true }
  for (const n of nodes) {
    if (n.id === targetId) { if (!n.children) n.children = []; n.children.push(node); return true }
    if (n.children && addToTree(n.children, targetId, node)) return true
  }
  return false
}

function editRename() {
  if (!editFolderId.value || !editFolderName.value.trim()) return
  const f = findFolderById(editFolders.value, editFolderId.value)
  if (f) f.name = editFolderName.value.trim()
  addEditOp('rename', editFolderId.value, { name: editFolderName.value.trim() })
  editFolderName.value = ''
  refreshEditFolders()
}

function editDelete() {
  if (!editFolderId.value) return
  addEditOp('delete', editFolderId.value, {})
  removeFromTree(editFolders.value, editFolderId.value)
  editFolderId.value = null; moveInSource.value = null
  refreshEditFolders()
}

function editMoveUp() { shiftFolderLocal(-1) }
function editMoveDown() { shiftFolderLocal(1) }

function shiftFolderLocal(delta) {
  const folder = findFolderById(editFolders.value, editFolderId.value)
  if (!folder) return
  // 找到上级节点的 children 数组
  const parentNode = folder.parentId ? findFolderById(editFolders.value, folder.parentId) : null
  const list = parentNode?.children || editFolders.value
  // 按当前 sortOrder 排序找位置
  list.sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
  const idx = list.findIndex(f => f.id === folder.id)
  const nidx = idx + delta
  if (nidx < 0 || nidx >= list.length) return
  // 在数组中交换位置
  const tmp = list[idx]; list[idx] = list[nidx]; list[nidx] = tmp
  // 更新 sortOrder
  list.forEach((f, i) => { f.sortOrder = (i + 1) * 10 })
  for (const f of list) {
    addEditOp('move', f.id, { parentId: f.parentId, sortOrder: f.sortOrder })
  }
  refreshEditFolders()
}

function editMoveOut() {
  const folder = findFolderById(editFolders.value, editFolderId.value)
  if (!folder || !folder.parentId) return
  const parent = findFolderById(editFolders.value, folder.parentId)
  const newParentId = parent?.parentId ?? null
  removeFromTree(editFolders.value, folder.id)
  folder.parentId = newParentId
  addToTree(editFolders.value, newParentId, folder)
  addEditOp('move', folder.id, { parentId: newParentId, sortOrder: 99 })
  refreshEditFolders()
}

function startMoveIn() {
  const f = findFolderById(editFolders.value, editFolderId.value)
  if (!f) return
  moveInSource.value = { id: f.id, name: f.name }
}

function moveFolderToLocal(targetId) {
  if (!moveInSource.value) return
  const f = findFolderById(editFolders.value, moveInSource.value.id)
  if (!f) return
  removeFromTree(editFolders.value, f.id)
  f.parentId = targetId
  addToTree(editFolders.value, targetId, f)
  addEditOp('move', f.id, { parentId: targetId, sortOrder: 99 })
  editFolderId.value = targetId
  moveInSource.value = null
  refreshEditFolders()
}

function openNewFolderForm() { newFolderName.value=''; newFolderParentId.value=selectedFolderId.value; showNewFolderForm.value=true }
async function createFolder() {
  if(!newFolderName.value.trim())return
  try{await axios.post('/api/folders',{name:newFolderName.value.trim(),parentId:newFolderParentId.value});newFolderName.value='';showNewFolderForm.value=false;loadFolders()}
  catch(e){alert('创建失败：'+(e.response?.data?.message||e.message))}
}

function startResize(e,side){resizing.value=side;e.preventDefault()}
function onResize(e){
  if(!resizing.value)return
  if(resizing.value==='left')leftWidth.value=Math.max(160,Math.min(400,e.clientX-6))
  else rightWidth.value=Math.max(240,Math.min(500,window.innerWidth-e.clientX-6))
}
function stopResize(){resizing.value=null}

function openImportDialog(){isEditing.value=false;editPaperId.value=null;form.value={...makeEmptyForm(),folderId:selectedFolderId.value};dialogVisible.value=true}
function openEditDialog(){isEditing.value=true;editPaperId.value=currentPaper.value.id;form.value={...currentPaper.value};dialogVisible.value=true}
async function submitPaper() {
  if(isEditing.value)await axios.put(`/api/papers/${editPaperId.value}`,form.value)
  else await axios.post('/api/papers',form.value)
  dialogVisible.value=false;loadPapers()
}
async function savePaper(p){await axios.put(`/api/papers/${p.id}`,p)}
function toggleFolderSearch(){showFolderSearch.value=!showFolderSearch.value;if(showFolderSearch.value)setTimeout(()=>folderSearchRef.value?.focus(),100)}
function startEditTitle(){editTitleText.value=currentPaper.value.title;editingTitle.value=true;setTimeout(()=>titleInputRef.value?.focus(),100)}
async function saveTitle(){editingTitle.value=false;if(editTitleText.value.trim()&&editTitleText.value!==currentPaper.value.title){currentPaper.value.title=editTitleText.value.trim();await savePaper(currentPaper.value)}}
async function deletePaper(){await axios.delete(`/api/papers/${currentPaper.value.id}`);currentPaper.value=null;loadPapers()}

onMounted(()=>{loadFolders();loadPapers()})
</script>

<style scoped>
.library { display:flex; height:calc(100vh - 100px); }
.library.is-resizing { user-select:none; }

/* ===== 三栏配色 ===== */
.left-panel { flex-shrink:0; overflow-y:auto; padding:0 10px; transition:width 0.2s; background:#f5f6f8; display:flex; flex-direction:column; }
.left-panel.collapsed { padding:0; overflow:hidden; }
.filter-section { margin-top:auto; padding:6px 0 12px; border-top:1px solid #e4e7ed; }
.filter-section h4 { margin:4px 0 6px; }
.center-panel { flex:1; display:flex; flex-direction:column; overflow:hidden; padding:0 12px; background:#fff; }
.right-panel { flex-shrink:0; overflow-y:auto; padding:8px 12px 0; transition:width 0.2s; background:#f5f6f8; }

/* 顶栏 */
.panel-header { display:flex; align-items:center; gap:4px; padding:6px 0; }
.header-search { display:flex; align-items:center; gap:2px; }
.search-input { width:130px; }

/* 树 */
.folder-all { display:flex; align-items:center; gap:4px; padding:5px 8px; cursor:pointer; font-size:13px; border-radius:4px; margin-bottom:2px; color:#303133; }
.folder-all:hover { background:#e8eaed; }
.folder-all.active { color:#1677d2; font-weight:600; background:#d9ecff; }
.folder-tree { background:transparent; }
.tree-node-label { font-size:13px; display:flex; justify-content:space-between; width:100%; }
.tree-node-label.path-0 { color:#1677d2; font-weight:600; }
.tree-node-label.path-1 { color:#0958a3; font-weight:600; }
.tree-node-label.path-2 { color:#05427a; font-weight:600; }
.folder-count { color:#909399; font-size:11px; }
.el-tree-node.is-current>.el-tree-node__content,
.el-tree-node.is-current>.el-tree-node__content:hover { background-color:#d9ecff !important; }

.inline-form { display:flex; gap:3px; margin-bottom:8px; align-items:center; }
.inline-form-actions { display:flex; gap:2px; margin-left:auto; flex-shrink:0; }
.w-full { width:100%; }

/* 分割线 */
.divider { width:1px; flex-shrink:0; cursor:col-resize; position:relative; background:#e2e4e7; transition:width 0.15s,background 0.15s; }
.divider:hover { background:#c8cacd; }
.divider.active { width:4px; background:#a0c4e8; }
.divider-handle { position:absolute; top:50%;left:50%;transform:translate(-50%,-50%);width:2px;height:28px;border-radius:2px;background:#999;opacity:0;transition:opacity 0.15s; }
.divider:hover .divider-handle { opacity:1; }
.divider.active .divider-handle { opacity:0; }

/* 工具栏 */
.toolbar { display:flex; align-items:center; justify-content:space-between; padding:4px 0 6px; gap:4px; }
.toolbar-left, .toolbar-right { display:flex; align-items:center; gap:2px; }
.toolbar-search { width:150px; }
.toolbar-divider { display:inline-block; width:1px; height:16px; background:#dcdfe6; margin:0 3px; vertical-align:middle; }

/* 表格 */
.table-wrapper { flex:1; overflow-y:auto; }
.paper-table { width:100%; border-collapse:collapse; font-size:13px; }
.paper-table th { position:sticky; top:0; background:#fafbfc; padding:6px 10px; text-align:left; border-bottom:2px solid #e4e7ed; cursor:pointer; user-select:none; white-space:nowrap; font-weight:500; color:#606266; }
.paper-table th:hover { background:#f0f2f5; }
.paper-table td { padding:5px 10px; border-bottom:1px solid #f0f1f3; }
.paper-table tr:hover td { background:#f5f6f8; cursor:pointer; }
.paper-table .row-active td { background:#ecf5ff !important; }
.paper-table .row-stripe td { background:#fafbfc; }
.paper-table .row-active.row-stripe td { background:#ecf5ff !important; }
.col-year { width:85px; }
.col-created { width:95px; }
.sort-arrow { font-size:11px; color:#909399; }
.empty-row { text-align:center; color:#c0c4cc; padding:40px 10px !important; cursor:default !important; }

.pagination-bar { display:flex; justify-content:center; padding:8px 0; }

/* 右栏详情 */
.detail-title-row { margin-bottom:10px; }
.detail-title { font-size:18px; font-weight:600; margin:0; cursor:text; line-height:1.4; }
.detail-title:hover { background:#e8eaed; border-radius:3px; }
.title-input { font-size:18px; font-weight:600; width:100%; border:1px solid #409eff; border-radius:3px; padding:2px 6px; outline:none; }
.detail-divider { height:1px; background:#e4e7ed; margin:10px 4px 14px; }
.detail-item { margin-bottom:12px; font-size:13px; line-height:1.6; }
.detail-item .label { font-size:12px; color:#909399; display:block; margin-bottom:2px; }
.detail-actions { margin-top:20px; display:flex; gap:8px; }
</style>

<!-- 非 scoped：强制覆盖 Element Plus 组件内部样式 -->
<style>
.library .panel-header .el-button { padding: 2px 4px !important; min-width: auto !important; }
.library .panel-header .el-button + .el-button { margin-left: 0 !important; }
.library .panel-header .el-dropdown { display: inline-flex !important; }
.library .panel-header .el-tooltip { display: inline-flex !important; }
.library .toolbar .el-button { padding: 2px 4px !important; min-width: auto !important; }
.library .toolbar .el-button + .el-button { margin-left: 0 !important; }
</style>
