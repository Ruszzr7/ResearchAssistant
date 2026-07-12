<template>
  <div class="writing-page">
    <!-- ====== 左侧面板 ====== -->
    <div class="left-panel">
      <div class="panel-header">
        <el-select v-model="selectedProjectId" placeholder="选择写作项目" filterable style="flex:1"
          @change="selectProject">
          <el-option v-for="p in projects" :key="p.id" :label="p.title" :value="p.id" />
        </el-select>
        <el-button type="primary" size="small" style="margin-left:8px" @click="openProjectDialog()">新建</el-button>
      </div>

      <div v-if="!selectedProject" class="empty-hint">请先创建或选择一个写作项目</div>
      <template v-else>
        <div class="project-meta">
          <div class="meta-title">{{ selectedProject.title }}</div>
          <div v-if="selectedProject.topic" class="meta-topic">{{ selectedProject.topic }}</div>
        </div>

        <el-tabs v-model="activeTab" class="left-tabs">
          <el-tab-pane label="已选论文" name="papers">
            <div v-if="selectedPapers.length === 0" class="empty-hint-small">未添加论文</div>
            <div v-else class="paper-list">
              <div v-for="p in selectedPapers" :key="p.id" class="paper-chip">
                <span class="paper-title" :title="p.title">{{ p.title || '未命名论文' }}</span>
                <span class="paper-remove" @click="removePaper(p.id)">✕</span>
              </div>
            </div>
            <el-button size="small" style="width:100%;margin-top:8px" @click="openAddPaperDialog">+ 添加论文</el-button>
          </el-tab-pane>

          <el-tab-pane label="我的笔记" name="notes">
            <div v-if="projectNotes.length === 0" class="empty-hint-small">暂无相关笔记</div>
            <div v-else class="note-list">
              <div v-for="note in projectNotes" :key="note.id" class="note-card" @click="insertText(note.content)">
                <div class="note-title">{{ note.title || '无标题笔记' }}</div>
                <div class="note-preview">{{ note.content }}</div>
                <el-button size="small" link @click.stop="insertText(note.content)">插入</el-button>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="生成结果" name="generated">
            <div v-if="!outline && !relatedWork && !citationResult" class="empty-hint-small">暂无生成内容</div>
            <div v-if="outline" class="gen-block">
              <div class="gen-title">大纲 <el-button size="small" link @click="insertOutline">插入</el-button></div>
              <div class="outline-tree">
                <div v-for="node in outline.sections" :key="node.title" class="outline-node"
                  :style="{ paddingLeft: (node.level - 1) * 12 + 'px' }">
                  {{ node.title }}
                </div>
              </div>
            </div>
            <div v-if="relatedWork" class="gen-block">
              <div class="gen-title">Related Work <el-button size="small" link @click="insertText(relatedWork.content)">插入</el-button></div>
              <div class="gen-preview">{{ relatedWork.content }}</div>
            </div>
            <div v-if="citationResult" class="gen-block">
              <div class="gen-title">引用检查</div>
              <div v-if="citationResult.suggestions?.length" class="check-section">
                <div class="check-subtitle">推荐引用</div>
                <div v-for="(s, i) in citationResult.suggestions" :key="i" class="check-item">
                  <strong>{{ s.paperTitle }}</strong>（{{ s.position }}）— {{ s.reason }}
                  <span v-if="s.locator" class="evidence-locator">[{{ s.locator }}]</span>
                </div>
              </div>
              <div v-if="citationResult.conflicts?.length" class="check-section">
                <div class="check-subtitle warning">潜在冲突</div>
                <div v-for="(c, i) in citationResult.conflicts" :key="i" class="check-item">
                  <strong>{{ c.paperTitle }}</strong> [{{ c.type }}] — {{ c.reason }}
                </div>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>

    <!-- ====== 右侧编辑区 ====== -->
    <div class="right-panel">
      <div v-if="!selectedProject" class="empty-hint-large">请从左侧选择或创建一个写作项目</div>
      <template v-else>
        <div class="editor-toolbar">
          <el-input v-model="topicInput" placeholder="输入研究选题，例如：多模态大模型在医疗影像分割中的应用" size="small"
            style="width:300px;margin-right:8px" />
          <el-button size="small" type="primary" :loading="outlineLoading" @click="doGenerateOutline">生成大纲</el-button>
          <el-button size="small" type="primary" :loading="relatedWorkLoading" @click="doGenerateRelatedWork"
            :disabled="selectedPapers.length === 0">生成 Related Work</el-button>
          <el-button size="small" :loading="citationLoading" @click="doCheckCitations"
            :disabled="!editorContent.trim()">检查引用/冲突</el-button>
          <el-button size="small" @click="saveDraft" :loading="saving">保存草稿</el-button>
          <el-radio-group v-model="editorMode" size="small" style="margin-left:auto">
            <el-radio-button label="edit">编辑</el-radio-button>
            <el-radio-button label="preview">预览</el-radio-button>
          </el-radio-group>
        </div>

        <div class="editor-status">
          <span v-if="outlineLoading" class="stage-text">{{ outlineStatus }}</span>
          <span v-if="relatedWorkLoading" class="stage-text">{{ relatedWorkStatus }}</span>
          <span v-if="citationLoading" class="stage-text">{{ citationStatus }}</span>
          <span v-if="lastSavedAt" class="saved-text">已保存于 {{ lastSavedAt }}</span>
        </div>

        <div class="editor-body">
          <el-input v-if="editorMode === 'edit'" ref="editorRef" v-model="editorContent" type="textarea"
            :rows="28" placeholder="在此开始写作……" resize="none" />
          <div v-else class="preview" v-html="previewHtml"></div>
        </div>
      </template>
    </div>

    <!-- 新建/编辑项目弹窗 -->
    <el-dialog v-model="projectDialogVisible" title="新建写作项目" width="420px">
      <el-form label-width="70px">
        <el-form-item label="标题">
          <el-input v-model="projectForm.title" placeholder="例如：毕业论文" />
        </el-form-item>
        <el-form-item label="选题">
          <el-input v-model="projectForm.topic" type="textarea" :rows="2" placeholder="研究选题" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="projectDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveProject">创建</el-button>
      </template>
    </el-dialog>

    <!-- 添加论文弹窗 -->
    <el-dialog v-model="addPaperDialogVisible" title="添加论文到项目" width="520px">
      <el-select v-model="paperToAdd" filterable placeholder="从文库选择论文" style="width:100%">
        <el-option v-for="p in availablePapers" :key="p.id" :label="p.title" :value="p.id" />
      </el-select>
      <template #footer>
        <el-button @click="addPaperDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmAddPaper">添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useGlobalTask } from '@/composables/useGlobalTask.js'
import { simpleMarkdownToHtml } from '@/utils/markdown.js'
import api from '@/api'
import {
  listWritingProjects,
  getWritingProject,
  createWritingProject,
  updateWritingProject,
  deleteWritingProject,
  addProjectPaper,
  removeProjectPaper,
  listProjectNotes,
  generateOutline,
  generateRelatedWork,
  checkCitations,
} from '@/api/writing'

const projects = ref([])
const selectedProjectId = ref(null)
const selectedProject = ref(null)
const selectedPapers = ref([])
const allPapers = ref([])
const projectNotes = ref([])
const activeTab = ref('papers')
const editorContent = ref('')
const editorMode = ref('edit')
const topicInput = ref('')
const lastSavedAt = ref('')
const saving = ref(false)

const outline = ref(null)
const relatedWork = ref(null)
const citationResult = ref(null)

const projectDialogVisible = ref(false)
const projectForm = ref({ title: '', topic: '' })
const addPaperDialogVisible = ref(false)
const paperToAdd = ref(null)
const editorRef = ref(null)

const { isLoading: outlineLoading, statusText: outlineStatus, run: runOutline } = useGlobalTask('writing-outline')
const { isLoading: relatedWorkLoading, statusText: relatedWorkStatus, run: runRelatedWork } = useGlobalTask('writing-related-work')
const { isLoading: citationLoading, statusText: citationStatus, run: runCitation } = useGlobalTask('writing-citation-check')

const previewHtml = computed(() => simpleMarkdownToHtml(editorContent.value))
const availablePapers = computed(() => {
  const selectedIds = new Set(selectedPapers.value.map(p => p.id))
  return allPapers.value.filter(p => !selectedIds.has(p.id))
})

async function loadProjects() {
  const res = await listWritingProjects()
  projects.value = res.data || []
}

async function loadAllPapers() {
  const r = await api.get('/papers', { params: { size: 500 } })
  allPapers.value = r.data.records || []
}

async function selectProject(id) {
  if (!id) {
    selectedProject.value = null
    selectedPapers.value = []
    editorContent.value = ''
    topicInput.value = ''
    outline.value = null
    relatedWork.value = null
    citationResult.value = null
    return
  }
  const res = await getWritingProject(id)
  selectedProject.value = res.data
  topicInput.value = res.data.topic || ''
  editorContent.value = res.data.draftContent || ''
  outline.value = parseOutlineJson(res.data.outlineJson)
  relatedWork.value = res.data.relatedWork ? { content: res.data.relatedWork } : null
  await loadSelectedPapers(res.data.paperIds || [])
  await loadProjectNotes()
}

async function loadSelectedPapers(ids) {
  selectedPapers.value = allPapers.value.filter(p => ids.includes(p.id))
}

async function loadProjectNotes() {
  if (!selectedProject.value) return
  const res = await listProjectNotes(selectedProject.value.id)
  projectNotes.value = res.data || []
}

function openProjectDialog() {
  projectForm.value = { title: '', topic: '' }
  projectDialogVisible.value = true
}

async function saveProject() {
  if (!projectForm.value.title.trim()) {
    ElMessage.warning('请输入项目标题')
    return
  }
  const created = await createWritingProject({
    title: projectForm.value.title.trim(),
    topic: projectForm.value.topic,
  })
  projectDialogVisible.value = false
  await loadProjects()
  selectedProjectId.value = created.data.id
  await selectProject(created.data.id)
  ElMessage.success('项目已创建')
}

async function saveDraft() {
  if (!selectedProject.value) return
  saving.value = true
  try {
    await updateWritingProject(selectedProject.value.id, {
      title: selectedProject.value.title,
      topic: selectedProject.value.topic,
      draftContent: editorContent.value,
    })
    lastSavedAt.value = new Date().toLocaleTimeString()
    ElMessage.success('草稿已保存')
  } finally {
    saving.value = false
  }
}

function openAddPaperDialog() {
  paperToAdd.value = null
  addPaperDialogVisible.value = true
}

async function confirmAddPaper() {
  if (!paperToAdd.value) {
    ElMessage.warning('请选择论文')
    return
  }
  await addProjectPaper(selectedProject.value.id, paperToAdd.value)
  addPaperDialogVisible.value = false
  await refreshProject()
  ElMessage.success('已添加')
}

async function removePaper(paperId) {
  try {
    await ElMessageBox.confirm('从项目中移除该论文？', '确认', { type: 'warning' })
    await removeProjectPaper(selectedProject.value.id, paperId)
    await refreshProject()
    ElMessage.success('已移除')
  } catch (e) { /* cancel */ }
}

async function refreshProject() {
  const res = await getWritingProject(selectedProject.value.id)
  selectedProject.value = res.data
  await loadSelectedPapers(res.data.paperIds || [])
  await loadProjectNotes()
}

async function doGenerateOutline() {
  if (!topicInput.value.trim()) {
    ElMessage.warning('请输入研究选题')
    return
  }
  const result = await runOutline(async () => {
    const res = await generateOutline({ topic: topicInput.value.trim(), style: '学术', language: '中文' })
    outline.value = res.data
    activeTab.value = 'generated'
    return res.data
  })
  if (result) ElMessage.success('大纲已生成')
}

async function doGenerateRelatedWork() {
  const ids = selectedPapers.value.map(p => p.id)
  if (ids.length === 0) {
    ElMessage.warning('请先添加论文')
    return
  }
  const result = await runRelatedWork(async () => {
    const res = await generateRelatedWork({
      paperIds: ids,
      topic: topicInput.value.trim() || selectedProject.value.topic,
      style: '学术',
    })
    relatedWork.value = res.data
    activeTab.value = 'generated'
    return res.data
  })
  if (result) ElMessage.success('Related Work 已生成')
}

async function doCheckCitations() {
  if (!editorContent.value.trim()) {
    ElMessage.warning('编辑器中暂无内容')
    return
  }
  const result = await runCitation(async () => {
    const res = await checkCitations({
      paragraph: editorContent.value.trim(),
      paperIds: selectedPapers.value.map(p => p.id),
    })
    citationResult.value = res.data
    activeTab.value = 'generated'
    return res.data
  })
  if (result) ElMessage.success('引用检查完成')
}

function insertText(text) {
  if (!text) return
  editorContent.value += '\n\n' + text
  editorMode.value = 'edit'
  nextTick(() => {
    const el = editorRef.value?.$el?.querySelector('textarea')
    if (el) el.scrollTop = el.scrollHeight
  })
}

function insertOutline() {
  if (!outline.value) return
  const lines = []
  function walk(nodes) {
    for (const n of nodes || []) {
      lines.push('#'.repeat(Math.min(n.level || 1, 4)) + ' ' + n.title)
      walk(n.children)
    }
  }
  walk(outline.value.sections)
  insertText(lines.join('\n'))
}

function parseOutlineJson(json) {
  if (!json) return null
  try {
    return JSON.parse(json)
  } catch {
    return null
  }
}

onMounted(async () => {
  await loadAllPapers()
  await loadProjects()
})
</script>

<style scoped>
.writing-page {
  display: flex;
  height: calc(100vh - 61px);
  background: var(--ra-bg);
}
.left-panel {
  width: 300px;
  flex-shrink: 0;
  border-right: 1px solid var(--ra-border);
  background: var(--ra-panel-bg);
  padding: 16px;
  overflow-y: auto;
}
.right-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 16px 24px;
  overflow: hidden;
}
.panel-header {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
}
.project-meta {
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--ra-border);
}
.meta-title {
  font-weight: 600;
  font-size: 15px;
  color: var(--ra-text);
}
.meta-topic {
  font-size: 12px;
  color: var(--ra-text-tertiary);
  margin-top: 4px;
}
.left-tabs :deep(.el-tabs__item) {
  font-size: 13px;
  padding: 0 10px;
}
.paper-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.paper-chip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 8px;
  background: var(--ra-active-bg);
  border-radius: 4px;
  font-size: 12px;
}
.paper-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}
.paper-remove {
  cursor: pointer;
  color: #f56c6c;
  margin-left: 6px;
}
.note-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.note-card {
  padding: 8px;
  border: 1px solid var(--ra-border);
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.2s;
}
.note-card:hover { background: var(--ra-hover-bg); }
.note-title {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 4px;
}
.note-preview {
  font-size: 12px;
  color: var(--ra-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-bottom: 6px;
}
.gen-block {
  margin-bottom: 14px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--ra-border-light);
}
.gen-title {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 6px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.gen-preview {
  font-size: 12px;
  color: var(--ra-text-secondary);
  line-height: 1.6;
  max-height: 120px;
  overflow-y: auto;
}
.outline-tree {
  font-size: 12px;
  color: var(--ra-text);
  line-height: 1.8;
}
.check-section { margin-top: 6px; }
.check-subtitle {
  font-size: 12px;
  font-weight: 600;
  color: #67c23a;
  margin-bottom: 4px;
}
.check-subtitle.warning { color: #f56c6c; }
.check-item {
  font-size: 12px;
  color: var(--ra-text-secondary);
  margin-bottom: 4px;
  line-height: 1.5;
}
.evidence-locator { color: var(--ra-text-tertiary); margin-left: 4px; }
.editor-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.editor-status {
  min-height: 20px;
  margin-bottom: 8px;
}
.stage-text {
  font-size: 12px;
  color: var(--ra-link);
}
.saved-text {
  font-size: 12px;
  color: #67c23a;
}
.editor-body {
  flex: 1;
  overflow: hidden;
}
.editor-body :deep(.el-textarea) {
  height: 100%;
}
.editor-body :deep(.el-textarea__inner) {
  height: 100% !important;
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  line-height: 1.7;
}
.preview {
  height: 100%;
  overflow-y: auto;
  padding: 12px;
  background: var(--ra-panel-bg);
  border: 1px solid var(--ra-border);
  border-radius: 4px;
  line-height: 1.8;
}
.empty-hint {
  text-align: center;
  color: var(--ra-text-tertiary);
  font-size: 13px;
  padding: 40px 0;
}
.empty-hint-small {
  text-align: center;
  color: var(--ra-text-tertiary);
  font-size: 12px;
  padding: 20px 0;
}
.empty-hint-large {
  text-align: center;
  color: var(--ra-text-tertiary);
  font-size: 15px;
  padding-top: 80px;
}
</style>
