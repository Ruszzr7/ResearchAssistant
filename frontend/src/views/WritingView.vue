<template>
  <div class="writing-page">
    <header class="workspace-header">
      <div class="page-title">写作工作台</div>
      <el-select
        v-model="selectedProjectId"
        class="project-select"
        placeholder="选择写作项目"
        filterable
        @change="selectProject"
      >
        <el-option v-for="project in projects" :key="project.id" :label="project.title" :value="project.id" />
      </el-select>
      <el-button type="primary" @click="openProjectDialog(false)">新建项目</el-button>
      <el-button v-if="selectedProject" @click="openProjectDialog(true)">项目设置</el-button>
    </header>

    <el-empty v-if="!selectedProject" description="请选择或新建写作项目" class="page-empty" />

    <div v-else class="workspace-body">
      <aside class="source-panel">
        <section class="project-block">
          <h2>{{ selectedProject.title }}</h2>
          <p>{{ selectedProject.topic || '尚未填写研究主题' }}</p>
        </section>

        <section class="source-section">
          <div class="section-heading">
            <span>项目论文</span>
            <el-button link type="primary" @click="openAddPaperDialog">添加</el-button>
          </div>
          <div v-if="!selectedPapers.length" class="compact-empty">暂无论文</div>
          <div v-else class="paper-list">
            <div v-for="paper in selectedPapers" :key="paper.id" class="paper-row">
              <button class="paper-link" :title="paper.title" @click="openPaperResearch(paper)">
                {{ paper.title || `论文 #${paper.id}` }}
              </button>
              <el-button link type="danger" @click="removePaper(paper.id)">移除</el-button>
            </div>
          </div>
        </section>

        <section class="source-section notes-section">
          <div class="section-heading"><span>研究笔记</span><span>{{ projectNotes.length }}</span></div>
          <div v-if="!projectNotes.length" class="compact-empty">暂无笔记</div>
          <article v-for="note in projectNotes" :key="note.id" class="note-card">
            <strong>{{ note.title || '未命名笔记' }}</strong>
            <p>{{ note.content }}</p>
            <el-button link type="primary" @click="insertText(note.content)">插入草稿</el-button>
          </article>
        </section>
      </aside>

      <main class="main-panel">
        <el-tabs v-model="activeTab" class="workspace-tabs">
          <el-tab-pane label="论点与证据" name="claims">
            <div class="claims-toolbar">
              <div class="coverage-card">
                <span>证据覆盖</span>
                <strong>{{ claimSummary.coverage }}%</strong>
                <el-progress :percentage="claimSummary.coverage" :show-text="false" />
              </div>
              <div class="metric"><strong>{{ claimSummary.total }}</strong><span>论点</span></div>
              <div class="metric success"><strong>{{ claimSummary.supported }}</strong><span>已支撑</span></div>
              <div class="metric warning"><strong>{{ claimSummary.needsEvidence }}</strong><span>待补证据</span></div>
              <div class="metric danger"><strong>{{ claimSummary.risk }}</strong><span>证据风险</span></div>
              <el-button type="primary" @click="openClaimDialog()">新建论点</el-button>
            </div>

            <el-empty v-if="!claims.length" description="暂无论点" />
            <div v-else class="claim-list">
              <article v-for="claim in claims" :key="claim.id" class="claim-card">
                <div class="claim-header">
                  <div class="claim-tags">
                    <el-tag effect="plain">{{ claim.sectionName }}</el-tag>
                    <el-tag :type="evidenceStateMeta(claim.evidenceState).type" effect="light">
                      {{ evidenceStateMeta(claim.evidenceState).label }}
                    </el-tag>
                  </div>
                  <div class="claim-actions">
                    <el-button link @click="openClaimDialog(claim)">编辑</el-button>
                    <el-button link type="danger" @click="removeClaim(claim)">删除</el-button>
                    <el-button type="primary" plain size="small" @click="openEvidenceDialog(claim)">添加证据</el-button>
                  </div>
                </div>
                <p class="claim-text">{{ claim.claimText }}</p>

                <div v-if="claim.evidence?.length" class="evidence-list">
                  <div v-for="item in claim.evidence" :key="item.id" class="evidence-card">
                    <div class="evidence-topline">
                      <div>
                        <el-tag size="small" :type="evidenceRelationMeta(item.relationType).type">
                          {{ evidenceRelationMeta(item.relationType).label }}
                        </el-tag>
                        <strong>{{ item.paperTitle }}</strong>
                      </div>
                      <div class="evidence-actions">
                        <el-button link type="primary" @click="openEvidenceSource(item)">查看原文</el-button>
                        <el-button link @click="openEvidenceDialog(claim, item)">编辑</el-button>
                        <el-button link type="danger" @click="removeEvidence(item)">删除</el-button>
                      </div>
                    </div>
                    <blockquote>{{ item.quoteText }}</blockquote>
                    <div class="evidence-meta">
                      <span v-if="item.pageNumber">第 {{ item.pageNumber }} 页</span>
                      <span v-if="item.locator">{{ item.locator }}</span>
                      <span v-if="item.researchSessionTitle">{{ item.researchSessionTitle }}</span>
                    </div>
                    <p v-if="item.note" class="evidence-note">{{ item.note }}</p>
                  </div>
                </div>
                <div v-else class="claim-empty">尚无证据</div>
              </article>
            </div>
          </el-tab-pane>

          <el-tab-pane label="草稿" name="draft">
            <div class="editor-toolbar">
              <el-button type="primary" :loading="saving" @click="saveDraft">保存</el-button>
              <span v-if="lastSavedAt" class="saved-text">{{ lastSavedAt }}</span>
              <el-radio-group v-model="editorMode" size="small">
                <el-radio-button value="edit">编辑</el-radio-button>
                <el-radio-button value="preview">预览</el-radio-button>
              </el-radio-group>
            </div>
            <div class="editor-body">
              <el-input
                v-if="editorMode === 'edit'"
                ref="editorRef"
                v-model="editorContent"
                type="textarea"
                :rows="28"
                placeholder="在此开始写作……"
                resize="none"
              />
              <div v-else class="preview" v-html="previewHtml"></div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="AI 辅助" name="assistant">
            <div class="assistant-toolbar">
              <el-input v-model="topicInput" placeholder="研究选题" />
              <el-button type="primary" :loading="outlineLoading" @click="doGenerateOutline">生成大纲</el-button>
              <el-button
                type="primary"
                :loading="relatedWorkLoading"
                :disabled="!selectedPapers.length"
                @click="doGenerateRelatedWork"
              >生成相关工作</el-button>
              <el-button
                :loading="citationLoading"
                :disabled="!editorContent.trim()"
                @click="doCheckCitations"
              >检查草稿引用</el-button>
            </div>
            <div class="assistant-status">
              <span v-if="outlineLoading">{{ outlineStatus }}</span>
              <span v-if="relatedWorkLoading">{{ relatedWorkStatus }}</span>
              <span v-if="citationLoading">{{ citationStatus }}</span>
            </div>

            <el-empty v-if="!outline && !relatedWork && !citationResult" description="暂无生成结果" />
            <div v-else class="generated-grid">
              <section v-if="outline" class="generated-card">
                <div class="generated-heading"><strong>论文大纲</strong><el-button link type="primary" @click="insertOutline">插入草稿</el-button></div>
                <div v-for="node in flatOutline" :key="`${node.level}-${node.title}`" class="outline-node" :style="{ paddingLeft: `${(node.level - 1) * 16}px` }">
                  {{ node.title }}
                </div>
              </section>
              <section v-if="relatedWork" class="generated-card">
                <div class="generated-heading"><strong>相关工作</strong><el-button link type="primary" @click="insertText(relatedWork.content)">插入草稿</el-button></div>
                <p class="generated-content">{{ relatedWork.content }}</p>
              </section>
              <section v-if="citationResult" class="generated-card citation-card">
                <div class="generated-heading"><strong>引用检查</strong></div>
                <div v-if="citationResult.suggestions?.length" class="check-section">
                  <h4>引用建议</h4>
                  <p v-for="(item, index) in citationResult.suggestions" :key="`suggestion-${index}`">
                    <strong>{{ item.paperTitle }}</strong>（{{ item.position }}） {{ item.reason }}
                    <span v-if="item.locator">{{ item.locator }}</span>
                  </p>
                </div>
                <div v-if="citationResult.conflicts?.length" class="check-section conflict">
                  <h4>潜在冲突</h4>
                  <p v-for="(item, index) in citationResult.conflicts" :key="`conflict-${index}`">
                    <strong>{{ item.paperTitle }}</strong> {{ item.reason }}
                  </p>
                </div>
                <div v-if="!citationResult.suggestions?.length && !citationResult.conflicts?.length" class="compact-empty">未发现引用建议或冲突</div>
              </section>
            </div>
          </el-tab-pane>
        </el-tabs>
      </main>
    </div>

    <el-dialog v-model="projectDialogVisible" :title="editingProject ? '项目设置' : '新建写作项目'" width="480px">
      <el-form label-width="72px">
        <el-form-item label="标题"><el-input v-model="projectForm.title" /></el-form-item>
        <el-form-item label="研究主题"><el-input v-model="projectForm.topic" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button v-if="editingProject" type="danger" plain @click="removeProject">删除项目</el-button>
        <el-button @click="projectDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveProject">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="addPaperDialogVisible" title="添加论文" width="560px">
      <el-select v-model="paperToAdd" filterable placeholder="从文库选择" style="width: 100%">
        <el-option v-for="paper in availablePapers" :key="paper.id" :label="paper.title" :value="paper.id" />
      </el-select>
      <template #footer>
        <el-button @click="addPaperDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmAddPaper">添加</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="claimDialogVisible" :title="editingClaimId ? '编辑论点' : '新建论点'" width="600px">
      <el-form label-width="72px">
        <el-form-item label="所属章节"><el-input v-model="claimForm.sectionName" placeholder="例如：方法、实验、讨论" /></el-form-item>
        <el-form-item label="核心论点"><el-input v-model="claimForm.claimText" type="textarea" :rows="5" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="claimDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveClaim">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="evidenceDialogVisible" :title="editingEvidenceId ? '编辑证据' : '添加证据'" width="680px">
      <el-form label-width="84px">
        <el-form-item label="论文">
          <el-select v-model="evidenceForm.paperId" filterable style="width: 100%" @change="evidenceForm.researchSessionId = null">
            <el-option v-for="paper in selectedPapers" :key="paper.id" :label="paper.title" :value="paper.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="关系">
          <el-radio-group v-model="evidenceForm.relationType">
            <el-radio-button value="SUPPORTS">支持</el-radio-button>
            <el-radio-button value="CONTRADICTS">反驳</el-radio-button>
            <el-radio-button value="CONTEXT">背景</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <div class="form-row">
          <el-form-item label="页码"><el-input-number v-model="evidenceForm.pageNumber" :min="1" controls-position="right" /></el-form-item>
          <el-form-item label="定位"><el-input v-model="evidenceForm.locator" placeholder="例如：Eq. (7)、Section IV-B" /></el-form-item>
        </div>
        <el-form-item label="证据原文"><el-input v-model="evidenceForm.quoteText" type="textarea" :rows="5" /></el-form-item>
        <el-form-item label="研究会话">
          <el-select v-model="evidenceForm.researchSessionId" clearable placeholder="可选" style="width: 100%">
            <el-option v-for="session in availableEvidenceSessions" :key="session.id" :label="session.title" :value="session.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="evidenceForm.note" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="evidenceDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEvidence">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '@/api'
import { useGlobalTask } from '@/composables/useGlobalTask.js'
import { listResearchSessions } from '@/api/researchArchive.js'
import { simpleMarkdownToHtml } from '@/utils/markdown.js'
import {
  addProjectPaper,
  checkCitations,
  createWritingClaim,
  createWritingEvidence,
  createWritingProject,
  deleteWritingClaim,
  deleteWritingEvidence,
  deleteWritingProject,
  generateOutline,
  generateRelatedWork,
  getWritingProject,
  listProjectNotes,
  listWritingClaims,
  listWritingProjects,
  removeProjectPaper,
  updateWritingClaim,
  updateWritingEvidence,
  updateWritingProject,
} from '@/api/writing'
import {
  buildEvidenceResearchLocation,
  evidenceRelationMeta,
  evidenceStateMeta,
  summarizeClaims,
} from '@/utils/writingEvidence.js'

const route = useRoute()
const router = useRouter()

const projects = ref([])
const selectedProjectId = ref(null)
const selectedProject = ref(null)
const allPapers = ref([])
const selectedPapers = ref([])
const projectNotes = ref([])
const researchSessions = ref([])
const claims = ref([])
const activeTab = ref('claims')

const editorContent = ref('')
const editorMode = ref('edit')
const editorRef = ref(null)
const topicInput = ref('')
const lastSavedAt = ref('')
const saving = ref(false)
const outline = ref(null)
const relatedWork = ref(null)
const citationResult = ref(null)

const projectDialogVisible = ref(false)
const editingProject = ref(false)
const projectForm = ref({ title: '', topic: '' })
const addPaperDialogVisible = ref(false)
const paperToAdd = ref(null)
const claimDialogVisible = ref(false)
const editingClaimId = ref(null)
const claimForm = ref({ sectionName: '', claimText: '' })
const evidenceDialogVisible = ref(false)
const editingEvidenceId = ref(null)
const evidenceClaimId = ref(null)
const evidenceForm = ref(emptyEvidenceForm())

const { isLoading: outlineLoading, statusText: outlineStatus, run: runOutline } = useGlobalTask('writing-outline')
const { isLoading: relatedWorkLoading, statusText: relatedWorkStatus, run: runRelatedWork } = useGlobalTask('writing-related-work')
const { isLoading: citationLoading, statusText: citationStatus, run: runCitation } = useGlobalTask('writing-citation-check')

const previewHtml = computed(() => simpleMarkdownToHtml(editorContent.value))
const claimSummary = computed(() => summarizeClaims(claims.value))
const availablePapers = computed(() => {
  const selected = new Set(selectedPapers.value.map((paper) => paper.id))
  return allPapers.value.filter((paper) => !selected.has(paper.id))
})
const availableEvidenceSessions = computed(() => researchSessions.value.filter((session) =>
  (session.papers || []).some((paper) => paper.id === evidenceForm.value.paperId)))
const flatOutline = computed(() => {
  const result = []
  const walk = (nodes) => (nodes || []).forEach((node) => {
    result.push(node)
    walk(node.children)
  })
  walk(outline.value?.sections)
  return result
})

async function loadProjects() {
  const response = await listWritingProjects()
  projects.value = response.data || []
}

async function loadAllPapers() {
  const response = await api.get('/papers', { params: { size: 500 } })
  allPapers.value = response.data.records || []
}

async function loadResearchArchive() {
  const [active, archived] = await Promise.all([
    listResearchSessions({ archived: false, limit: 200 }),
    listResearchSessions({ archived: true, limit: 200 }),
  ])
  researchSessions.value = [...active, ...archived]
}

async function selectProject(id, syncRoute = true) {
  if (!id) {
    clearProject()
    return
  }
  const projectId = Number(id)
  const [projectResponse, claimResponse, noteResponse] = await Promise.all([
    getWritingProject(projectId),
    listWritingClaims(projectId),
    listProjectNotes(projectId),
  ])
  selectedProjectId.value = projectId
  selectedProject.value = projectResponse.data
  selectedPapers.value = allPapers.value.filter((paper) => (projectResponse.data.paperIds || []).includes(paper.id))
  projectNotes.value = noteResponse.data || []
  claims.value = claimResponse.data || []
  editorContent.value = projectResponse.data.draftContent || ''
  topicInput.value = projectResponse.data.topic || ''
  outline.value = parseOutlineJson(projectResponse.data.outlineJson)
  relatedWork.value = projectResponse.data.relatedWork ? { content: projectResponse.data.relatedWork } : null
  citationResult.value = null
  if (syncRoute && String(route.query.project || '') !== String(projectId)) {
    await router.replace({ path: '/writing', query: { project: String(projectId) } })
  }
}

function clearProject() {
  selectedProjectId.value = null
  selectedProject.value = null
  selectedPapers.value = []
  projectNotes.value = []
  claims.value = []
  editorContent.value = ''
  topicInput.value = ''
  outline.value = null
  relatedWork.value = null
  citationResult.value = null
}

async function refreshProject() {
  if (!selectedProjectId.value) return
  await selectProject(selectedProjectId.value, false)
  await loadProjects()
}

async function refreshClaims() {
  const response = await listWritingClaims(selectedProjectId.value)
  claims.value = response.data || []
}

function openProjectDialog(edit) {
  editingProject.value = edit
  projectForm.value = edit
    ? { title: selectedProject.value.title, topic: selectedProject.value.topic || '' }
    : { title: '', topic: '' }
  projectDialogVisible.value = true
}

async function saveProject() {
  if (!projectForm.value.title.trim()) {
    ElMessage.warning('请输入项目标题')
    return
  }
  if (editingProject.value) {
    await updateWritingProject(selectedProject.value.id, projectPayload({
      title: projectForm.value.title.trim(),
      topic: projectForm.value.topic,
    }))
    projectDialogVisible.value = false
    await refreshProject()
    ElMessage.success('已保存')
    return
  }
  const response = await createWritingProject({
    title: projectForm.value.title.trim(),
    topic: projectForm.value.topic,
  })
  projectDialogVisible.value = false
  await loadProjects()
  await selectProject(response.data.id)
  ElMessage.success('项目已创建')
}

async function removeProject() {
  const confirmed = await confirmAction('删除该写作项目及其论点证据？')
  if (!confirmed) return
  await deleteWritingProject(selectedProject.value.id)
  projectDialogVisible.value = false
  clearProject()
  await router.replace('/writing')
  await loadProjects()
  ElMessage.success('已删除')
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
  const confirmed = await confirmAction('从项目中移除该论文？')
  if (!confirmed) return
  await removeProjectPaper(selectedProject.value.id, paperId)
  await refreshProject()
  ElMessage.success('已移除')
}

function openPaperResearch(paper) {
  router.push({
    path: `/research/${paper.id}`,
    query: { page: '1', mode: 'analysis', returnTo: `/writing?project=${selectedProject.value.id}` },
  })
}

function openClaimDialog(claim = null) {
  editingClaimId.value = claim?.id || null
  claimForm.value = claim
    ? { sectionName: claim.sectionName, claimText: claim.claimText }
    : { sectionName: '', claimText: '' }
  claimDialogVisible.value = true
}

async function saveClaim() {
  if (!claimForm.value.sectionName.trim() || !claimForm.value.claimText.trim()) {
    ElMessage.warning('请填写章节与论点')
    return
  }
  const request = {
    sectionName: claimForm.value.sectionName.trim(),
    claimText: claimForm.value.claimText.trim(),
  }
  if (editingClaimId.value) await updateWritingClaim(editingClaimId.value, request)
  else await createWritingClaim(selectedProject.value.id, request)
  claimDialogVisible.value = false
  await refreshClaims()
  await loadProjects()
  ElMessage.success('已保存')
}

async function removeClaim(claim) {
  const confirmed = await confirmAction('删除该论点及其全部证据？')
  if (!confirmed) return
  await deleteWritingClaim(claim.id)
  await refreshClaims()
  ElMessage.success('已删除')
}

async function openEvidenceDialog(claim, evidence = null) {
  evidenceClaimId.value = claim.id
  editingEvidenceId.value = evidence?.id || null
  evidenceForm.value = evidence
    ? {
        paperId: evidence.paperId,
        relationType: evidence.relationType,
        pageNumber: evidence.pageNumber || 1,
        locator: evidence.locator || '',
        quoteText: evidence.quoteText,
        note: evidence.note || '',
        researchSessionId: evidence.researchSessionId || null,
      }
    : emptyEvidenceForm(selectedPapers.value[0]?.id)
  await loadResearchArchive()
  evidenceDialogVisible.value = true
}

async function saveEvidence() {
  if (!evidenceForm.value.paperId || !evidenceForm.value.quoteText.trim()) {
    ElMessage.warning('请选择论文并填写证据原文')
    return
  }
  const request = {
    ...evidenceForm.value,
    quoteText: evidenceForm.value.quoteText.trim(),
    locator: evidenceForm.value.locator?.trim() || null,
    note: evidenceForm.value.note?.trim() || null,
  }
  if (editingEvidenceId.value) await updateWritingEvidence(editingEvidenceId.value, request)
  else await createWritingEvidence(evidenceClaimId.value, request)
  evidenceDialogVisible.value = false
  await refreshClaims()
  ElMessage.success('已保存')
}

async function removeEvidence(evidence) {
  const confirmed = await confirmAction('删除该条证据？')
  if (!confirmed) return
  await deleteWritingEvidence(evidence.id)
  await refreshClaims()
  ElMessage.success('已删除')
}

function openEvidenceSource(evidence) {
  router.push(buildEvidenceResearchLocation(evidence, selectedProject.value.id))
}

async function saveDraft() {
  saving.value = true
  try {
    const response = await updateWritingProject(selectedProject.value.id, projectPayload())
    selectedProject.value = response.data
    lastSavedAt.value = `已保存 ${new Date().toLocaleTimeString()}`
    await loadProjects()
    ElMessage.success('已保存')
  } finally {
    saving.value = false
  }
}

async function persistGeneratedContent() {
  const response = await updateWritingProject(selectedProject.value.id, projectPayload())
  selectedProject.value = response.data
  await loadProjects()
}

async function doGenerateOutline() {
  if (!topicInput.value.trim()) {
    ElMessage.warning('请输入研究选题')
    return
  }
  const result = await runOutline(async () => {
    const response = await generateOutline({ topic: topicInput.value.trim(), style: '学术', language: '中文' })
    outline.value = response.data
    await persistGeneratedContent()
    return response.data
  })
  if (result) ElMessage.success('大纲已生成')
}

async function doGenerateRelatedWork() {
  const result = await runRelatedWork(async () => {
    const response = await generateRelatedWork({
      paperIds: selectedPapers.value.map((paper) => paper.id),
      topic: topicInput.value.trim() || selectedProject.value.topic,
      style: '学术',
    })
    relatedWork.value = response.data
    await persistGeneratedContent()
    return response.data
  })
  if (result) ElMessage.success('相关工作已生成')
}

async function doCheckCitations() {
  const result = await runCitation(async () => {
    const response = await checkCitations({
      paragraph: editorContent.value.trim(),
      paperIds: selectedPapers.value.map((paper) => paper.id),
    })
    citationResult.value = response.data
    return response.data
  })
  if (result) ElMessage.success('引用检查完成')
}

function insertText(text) {
  if (!text) return
  editorContent.value = editorContent.value
    ? `${editorContent.value}\n\n${text}`
    : text
  activeTab.value = 'draft'
  editorMode.value = 'edit'
  nextTick(() => {
    const textarea = editorRef.value?.$el?.querySelector('textarea')
    if (textarea) textarea.scrollTop = textarea.scrollHeight
  })
}

function insertOutline() {
  if (!outline.value) return
  const lines = flatOutline.value.map((node) => `${'#'.repeat(Math.min(node.level || 1, 4))} ${node.title}`)
  insertText(lines.join('\n'))
}

function projectPayload(overrides = {}) {
  return {
    title: selectedProject.value.title,
    topic: topicInput.value,
    draftContent: editorContent.value,
    outlineJson: outline.value ? JSON.stringify(outline.value) : '',
    relatedWork: relatedWork.value?.content || '',
    ...overrides,
  }
}

function parseOutlineJson(value) {
  if (!value) return null
  try { return JSON.parse(value) } catch { return null }
}

function emptyEvidenceForm(paperId = null) {
  return {
    paperId,
    relationType: 'SUPPORTS',
    pageNumber: 1,
    locator: '',
    quoteText: '',
    note: '',
    researchSessionId: null,
  }
}

async function confirmAction(message) {
  try {
    await ElMessageBox.confirm(message, '确认', { type: 'warning' })
    return true
  } catch {
    return false
  }
}

watch(() => route.query.project, async (value) => {
  const id = Number(value)
  if (id && id !== selectedProjectId.value && projects.value.some((project) => project.id === id)) {
    await selectProject(id, false)
  }
})

onMounted(async () => {
  await Promise.all([loadAllPapers(), loadProjects(), loadResearchArchive()])
  const requested = Number(route.query.project)
  if (requested && projects.value.some((project) => project.id === requested)) {
    await selectProject(requested, false)
  }
})
</script>

<style scoped>
.writing-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--ra-bg);
}

.workspace-header {
  height: 64px;
  flex: 0 0 64px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  border-bottom: 1px solid var(--ra-border);
  background: var(--ra-panel-bg);
}

.page-title { font-size: 18px; font-weight: 650; margin-right: 8px; }
.project-select { width: min(420px, 42vw); }
.page-empty { flex: 1; }

.workspace-body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
}

.source-panel {
  min-height: 0;
  padding: 18px;
  overflow-y: auto;
  border-right: 1px solid var(--ra-border);
  background: var(--ra-panel-bg);
}

.project-block { padding-bottom: 16px; border-bottom: 1px solid var(--ra-border); }
.project-block h2 { margin: 0 0 8px; font-size: 17px; line-height: 1.4; }
.project-block p { margin: 0; color: var(--ra-text-secondary); font-size: 13px; line-height: 1.6; }
.source-section { padding-top: 16px; }
.section-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 9px; font-weight: 600; font-size: 13px; }
.compact-empty { padding: 12px 0; color: var(--ra-text-tertiary); font-size: 12px; text-align: center; }
.paper-list { display: flex; flex-direction: column; gap: 6px; }
.paper-row { display: flex; align-items: center; gap: 4px; padding: 7px 8px; border-radius: 6px; background: var(--ra-active-bg); }
.paper-link { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; border: 0; padding: 0; text-align: left; color: var(--ra-text); background: none; cursor: pointer; }
.paper-link:hover { color: var(--el-color-primary); }
.notes-section { padding-bottom: 20px; }
.note-card { margin-bottom: 8px; padding: 10px; border: 1px solid var(--ra-border); border-radius: 7px; }
.note-card strong { font-size: 13px; }
.note-card p { margin: 6px 0 2px; color: var(--ra-text-secondary); font-size: 12px; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }

.main-panel { min-width: 0; min-height: 0; padding: 0 22px 18px; overflow: hidden; }
.workspace-tabs { height: 100%; display: flex; flex-direction: column; }
.workspace-tabs :deep(.el-tabs__content) { flex: 1; min-height: 0; overflow-y: auto; }
.workspace-tabs :deep(.el-tab-pane) { min-height: 100%; }
.claims-toolbar { position: sticky; top: 0; z-index: 2; display: flex; align-items: center; gap: 12px; padding: 14px 0; background: var(--ra-bg); }
.coverage-card { width: 180px; padding: 8px 12px; border: 1px solid var(--ra-border); border-radius: 8px; background: var(--ra-panel-bg); }
.coverage-card > span { font-size: 12px; color: var(--ra-text-secondary); }
.coverage-card > strong { float: right; font-size: 16px; }
.coverage-card :deep(.el-progress) { clear: both; padding-top: 6px; }
.metric { min-width: 68px; display: flex; flex-direction: column; align-items: center; }
.metric strong { font-size: 18px; }
.metric span { color: var(--ra-text-tertiary); font-size: 11px; }
.metric.success strong { color: var(--el-color-success); }
.metric.warning strong { color: var(--el-color-info); }
.metric.danger strong { color: var(--el-color-danger); }
.claims-toolbar > .el-button { margin-left: auto; }
.claim-list { display: flex; flex-direction: column; gap: 14px; padding-bottom: 20px; }
.claim-card { padding: 16px; border: 1px solid var(--ra-border); border-radius: 10px; background: var(--ra-panel-bg); }
.claim-header, .claim-tags, .claim-actions, .evidence-topline, .evidence-actions { display: flex; align-items: center; gap: 8px; }
.claim-header, .evidence-topline { justify-content: space-between; }
.claim-text { margin: 12px 0; font-size: 15px; font-weight: 550; line-height: 1.65; }
.claim-empty { padding: 10px 0 2px; color: var(--ra-text-tertiary); font-size: 12px; }
.evidence-list { display: flex; flex-direction: column; gap: 8px; }
.evidence-card { padding: 11px 12px; border-left: 3px solid var(--el-color-primary-light-5); background: var(--ra-active-bg); border-radius: 5px; }
.evidence-topline strong { margin-left: 7px; font-size: 13px; }
.evidence-card blockquote { margin: 9px 0; padding: 0; border: 0; color: var(--ra-text-secondary); font-size: 13px; line-height: 1.65; white-space: pre-wrap; }
.evidence-meta { display: flex; gap: 12px; color: var(--ra-text-tertiary); font-size: 11px; }
.evidence-note { margin: 6px 0 0; font-size: 12px; color: var(--ra-text-secondary); }

.editor-toolbar, .assistant-toolbar { display: flex; align-items: center; gap: 9px; padding: 14px 0; }
.editor-toolbar .el-radio-group { margin-left: auto; }
.saved-text { color: var(--el-color-success); font-size: 12px; }
.editor-body { height: calc(100vh - 180px); }
.editor-body :deep(.el-textarea), .editor-body :deep(.el-textarea__inner) { height: 100%; }
.editor-body :deep(.el-textarea__inner) { font-family: 'Cascadia Code', 'Microsoft YaHei', monospace; line-height: 1.75; }
.preview { height: 100%; overflow-y: auto; padding: 18px; border: 1px solid var(--ra-border); border-radius: 8px; background: var(--ra-panel-bg); line-height: 1.8; }
.assistant-toolbar .el-input { max-width: 440px; }
.assistant-status { min-height: 24px; color: var(--el-color-primary); font-size: 12px; }
.assistant-status span + span { margin-left: 12px; }
.generated-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; padding-bottom: 20px; }
.generated-card { padding: 15px; border: 1px solid var(--ra-border); border-radius: 9px; background: var(--ra-panel-bg); }
.generated-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.outline-node { padding-top: 3px; padding-bottom: 3px; font-size: 13px; }
.generated-content { margin: 0; color: var(--ra-text-secondary); line-height: 1.75; white-space: pre-wrap; }
.citation-card { grid-column: 1 / -1; }
.check-section h4 { margin: 10px 0 6px; color: var(--el-color-success); }
.check-section p { margin: 5px 0; color: var(--ra-text-secondary); line-height: 1.6; }
.check-section.conflict h4 { color: var(--el-color-danger); }
.form-row { display: grid; grid-template-columns: 190px minmax(0, 1fr); gap: 12px; }
.form-row .el-form-item { margin-right: 0; }

@media (max-width: 1050px) {
  .workspace-body { grid-template-columns: 250px minmax(0, 1fr); }
  .metric { display: none; }
  .generated-grid { grid-template-columns: 1fr; }
  .citation-card { grid-column: auto; }
}
</style>
