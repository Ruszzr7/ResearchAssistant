<template>
  <div class="reading-plan-page">
    <aside class="plan-sidebar">
      <header class="sidebar-heading">
        <div><h3>阅读计划</h3><small>围绕研究目标组织阅读</small></div>
        <el-button type="primary" size="small" @click="openPlanDialog()">新建</el-button>
      </header>

      <div v-if="!plans.length && !loading" class="empty-hint">暂无阅读计划</div>
      <div v-else class="plan-list">
        <article
          v-for="plan in plans"
          :key="plan.id"
          :class="['plan-card', { active: selectedPlan?.id === plan.id }]"
          tabindex="0"
          @click="selectPlan(plan)"
          @keydown.enter="selectPlan(plan)"
        >
          <div class="plan-card__heading">
            <b>{{ plan.name }}</b>
            <span>{{ plan.doneItems || 0 }}/{{ plan.totalItems || 0 }}</span>
          </div>
          <p>{{ plan.objective || plan.name }}</p>
          <el-progress :percentage="planProgress(plan)" :stroke-width="4" :show-text="false" />
          <footer>
            <span>{{ dateRange(plan) }}</span>
            <div>
              <button type="button" @click.stop="openPlanDialog(plan)">编辑</button>
              <button type="button" class="danger" @click.stop="removePlan(plan.id)">删除</button>
            </div>
          </footer>
        </article>
      </div>

      <section class="weekly-section">
        <h4>本周要读</h4>
        <div v-if="!weeklyItems.length" class="empty-hint compact">本周没有待读任务</div>
        <button
          v-for="item in weeklyItems"
          :key="item.id"
          type="button"
          class="weekly-item"
          @click="openWeeklyItem(item)"
        >
          <span>{{ item.paperTitle || '未命名论文' }}</span>
          <small>{{ item.planName }} · {{ item.deadline ? formatDate(item.deadline) : '未设期限' }}</small>
        </button>
      </section>
    </aside>

    <main class="plan-workspace" v-loading="loading">
      <el-empty v-if="!selectedPlan && !loading" description="选择或新建一个阅读计划" />
      <template v-else-if="selectedPlan">
        <header class="workspace-heading">
          <div><h2>{{ selectedPlan.name }}</h2><span>{{ dateRange(selectedPlan) }}</span></div>
          <div>
            <el-button @click="openPlanDialog(selectedPlan)">编辑目标</el-button>
            <el-button type="primary" @click="openItemDialog()">添加论文</el-button>
          </div>
        </header>

        <section class="goal-card">
          <div class="goal-card__copy">
            <small>研究目标</small>
            <p>{{ selectedPlan.objective || selectedPlan.name }}</p>
          </div>
          <div v-if="selectedPlan.successCriteria" class="goal-card__copy criteria">
            <small>完成标准</small>
            <p>{{ selectedPlan.successCriteria }}</p>
          </div>
          <div class="goal-card__progress">
            <b>{{ doneCount }}/{{ items.length }}</b>
            <span>已有阅读产出</span>
            <el-progress :percentage="selectedProgress" :stroke-width="6" />
          </div>
        </section>

        <el-empty v-if="!items.length" description="添加论文，并为它定义一个明确的阅读问题" />
        <div v-else class="reading-item-list">
          <article v-for="item in items" :key="item.id" class="reading-item" :class="`is-${item.status.toLowerCase()}`">
            <header>
              <div class="paper-copy">
                <div class="paper-title-line">
                  <el-tag size="small" :type="statusType(item.status)" effect="plain">{{ statusText(item.status) }}</el-tag>
                  <h3>{{ item.paperTitle || '未命名论文' }}</h3>
                </div>
                <div class="paper-meta">
                  <span>{{ outputLabel(item.expectedOutput) }}</span>
                  <span v-if="item.deadline" :class="{ overdue: isOverdue(item.deadline) && item.status !== 'DONE' }">截止 {{ formatDate(item.deadline) }}</span>
                  <span>优先级 {{ item.priority || 1 }}</span>
                </div>
              </div>
              <div class="item-actions">
                <el-button size="small" :disabled="!paperHasPdf(item.paperId)" @click="startResearch(item)">
                  {{ item.researchSessionId ? '继续研究' : '开始研究' }}
                </el-button>
                <el-button size="small" type="primary" plain @click="openOutcomeDialog(item)">
                  {{ item.outcome ? '编辑产出' : '记录产出' }}
                </el-button>
                <el-dropdown trigger="click">
                  <el-button size="small" text>更多</el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item @click="openItemDialog(item)">编辑任务</el-dropdown-item>
                      <el-dropdown-item v-if="item.status !== 'TODO'" @click="resetItem(item)">重置为待读</el-dropdown-item>
                      <el-dropdown-item divided @click="removeItem(item.id)">移除论文</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </header>

            <div class="reading-question">
              <small>阅读问题</small>
              <p>{{ item.readingQuestion || '尚未设置，建议先明确希望从论文中回答的问题。' }}</p>
            </div>
            <div v-if="item.outcome" class="reading-outcome">
              <small>阅读产出 · {{ formatDateTime(item.completedAt) }}</small>
              <p>{{ item.outcome }}</p>
            </div>
            <div v-else class="outcome-pending">完成阅读后记录结论，产出会随计划长期保留。</div>
          </article>
        </div>
      </template>
    </main>

    <el-dialog v-model="planDialogVisible" :title="editingPlan ? '编辑阅读目标' : '新建阅读计划'" width="540px" append-to-body>
      <el-form label-position="top">
        <el-form-item label="计划名称"><el-input v-model="planForm.name" maxlength="200" placeholder="例如：RSMA 有限块长方法调研" /></el-form-item>
        <el-form-item label="研究目标"><el-input v-model="planForm.objective" type="textarea" :rows="3" maxlength="2000" show-word-limit placeholder="完成这组阅读后，你希望回答什么研究问题？" /></el-form-item>
        <el-form-item label="完成标准"><el-input v-model="planForm.successCriteria" type="textarea" :rows="2" maxlength="2000" placeholder="例如：形成方法对比表，并确定一个可复现实验" /></el-form-item>
        <div class="date-fields">
          <el-form-item label="开始日期"><el-date-picker v-model="planForm.startDate" type="date" style="width:100%" /></el-form-item>
          <el-form-item label="结束日期"><el-date-picker v-model="planForm.endDate" type="date" style="width:100%" /></el-form-item>
        </div>
      </el-form>
      <template #footer><el-button @click="planDialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="savePlan">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="itemDialogVisible" :title="editingItem ? '编辑阅读任务' : '添加论文'" width="560px" append-to-body>
      <el-form label-position="top">
        <el-form-item label="论文">
          <el-select v-model="itemForm.paperId" filterable :disabled="Boolean(editingItem)" placeholder="从文库选择论文" style="width:100%">
            <el-option v-for="paper in papers" :key="paper.id" :label="paper.title" :value="paper.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="阅读问题"><el-input v-model="itemForm.readingQuestion" type="textarea" :rows="3" maxlength="2000" show-word-limit placeholder="例如：该方法依赖哪些信道假设，是否适合我的场景？" /></el-form-item>
        <el-form-item label="预期产出"><el-select v-model="itemForm.expectedOutput" style="width:100%"><el-option v-for="option in READING_OUTPUT_OPTIONS" :key="option.value" :label="option.label" :value="option.value" /></el-select></el-form-item>
        <div class="date-fields">
          <el-form-item label="截止日期"><el-date-picker v-model="itemForm.deadline" type="date" style="width:100%" /></el-form-item>
          <el-form-item label="优先级"><el-rate v-model="itemForm.priority" :max="5" /></el-form-item>
        </div>
        <el-form-item label="补充说明"><el-input v-model="itemForm.notes" type="textarea" :rows="2" maxlength="2000" placeholder="可选：阅读范围、需要重点核验的图表等" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="itemDialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveItem">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="outcomeDialogVisible" title="记录阅读产出" width="620px" append-to-body>
      <p class="outcome-dialog-question">{{ outcomeItem?.readingQuestion || outcomeItem?.paperTitle }}</p>
      <el-input v-model="outcomeText" type="textarea" :rows="9" maxlength="20000" show-word-limit placeholder="记录你从论文中得到的答案、证据、保留意见与下一步行动。保存后该任务标记为完成。" />
      <template #footer><el-button @click="outcomeDialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveOutcome">保存并完成</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import {
  addPlanItem, createReadingPlan, deletePlanItem, deleteReadingPlan, getReadingPlan,
  getReminders, getWeeklyReading, listReadingPlans, updatePlanItem, updateReadingPlan,
} from '@/api/readingPlan.js'
import { listPapers } from '@/api/paper.js'
import { createResearchSession } from '@/api/researchArchive.js'
import { outputLabel, planCompletion, READING_OUTPUT_OPTIONS } from '@/utils/readingPlan.js'

defineOptions({ name: 'ReadingPlanView' })

const route = useRoute()
const router = useRouter()
const plans = ref([])
const selectedPlan = ref(null)
const items = ref([])
const weeklyItems = ref([])
const papers = ref([])
const loading = ref(false)
const saving = ref(false)

const planDialogVisible = ref(false)
const editingPlan = ref(null)
const planForm = reactive({ name: '', objective: '', successCriteria: '', startDate: null, endDate: null })
const itemDialogVisible = ref(false)
const editingItem = ref(null)
const itemForm = reactive({ paperId: null, readingQuestion: '', expectedOutput: 'SUMMARY', deadline: null, priority: 1, notes: '' })
const outcomeDialogVisible = ref(false)
const outcomeItem = ref(null)
const outcomeText = ref('')

const doneCount = computed(() => items.value.filter(item => item.status === 'DONE' && item.outcome).length)
const selectedProgress = computed(() => planCompletion(doneCount.value, items.value.length))

onMounted(async () => {
  loading.value = true
  try {
    await Promise.all([loadPlans(), loadWeekly(), loadPapers(), loadReminders()])
    const requested = Number(route.query.plan)
    const initial = plans.value.find(plan => Number(plan.id) === requested) || plans.value[0]
    if (initial) await selectPlan(initial)
  } finally { loading.value = false }
})

async function loadPlans() {
  const response = await listReadingPlans()
  plans.value = response.data || []
}

async function loadWeekly() {
  const response = await getWeeklyReading()
  weeklyItems.value = response.data || []
}

async function loadPapers() { papers.value = await listPapers() }

async function selectPlan(plan) {
  const response = await getReadingPlan(plan.id)
  selectedPlan.value = response.data
  items.value = response.data?.items || []
  await router.replace({ path: '/reading-plans', query: { plan: String(plan.id) } })
}

function openPlanDialog(plan = null) {
  editingPlan.value = plan
  Object.assign(planForm, {
    name: plan?.name || '', objective: plan?.objective || '', successCriteria: plan?.successCriteria || '',
    startDate: plan?.startDate || null, endDate: plan?.endDate || null,
  })
  planDialogVisible.value = true
}

async function savePlan() {
  if (!planForm.name.trim() || !planForm.objective.trim()) return ElMessage.warning('请填写计划名称和研究目标')
  saving.value = true
  try {
    const payload = {
      name: planForm.name.trim(), objective: planForm.objective.trim(), successCriteria: planForm.successCriteria.trim() || null,
      startDate: toDateString(planForm.startDate), endDate: toDateString(planForm.endDate),
    }
    const response = editingPlan.value
      ? await updateReadingPlan(editingPlan.value.id, payload) : await createReadingPlan(payload)
    planDialogVisible.value = false
    await loadPlans()
    const saved = plans.value.find(plan => Number(plan.id) === Number(response.data.id))
    if (saved) await selectPlan(saved)
    ElMessage.success('已保存')
  } catch (reason) { showError(reason, '保存失败') }
  finally { saving.value = false }
}

async function removePlan(id) {
  try {
    await ElMessageBox.confirm('删除计划会移除其中的阅读任务与产出。', '删除阅读计划', { type: 'warning' })
    await deleteReadingPlan(id)
    if (selectedPlan.value?.id === id) { selectedPlan.value = null; items.value = [] }
    await Promise.all([loadPlans(), loadWeekly()])
    ElMessage.success('已删除')
  } catch (reason) { if (!['cancel', 'close'].includes(reason)) showError(reason, '删除失败') }
}

function openItemDialog(item = null) {
  editingItem.value = item
  Object.assign(itemForm, {
    paperId: item?.paperId || null, readingQuestion: item?.readingQuestion || '', expectedOutput: item?.expectedOutput || 'SUMMARY',
    deadline: item?.deadline || null, priority: item?.priority || 1, notes: item?.notes || '',
  })
  itemDialogVisible.value = true
}

async function saveItem() {
  if (!itemForm.paperId) return ElMessage.warning('请选择论文')
  if (!itemForm.readingQuestion.trim()) return ElMessage.warning('请设置一个明确的阅读问题')
  saving.value = true
  try {
    const payload = {
      ...(editingItem.value ? {} : { paperId: itemForm.paperId }),
      readingQuestion: itemForm.readingQuestion.trim(), expectedOutput: itemForm.expectedOutput,
      deadline: toDateString(itemForm.deadline), priority: itemForm.priority || 1, notes: itemForm.notes.trim() || null,
    }
    if (editingItem.value) await updatePlanItem(selectedPlan.value.id, editingItem.value.id, payload)
    else await addPlanItem(selectedPlan.value.id, payload)
    itemDialogVisible.value = false
    await refreshSelectedPlan()
    ElMessage.success('已保存')
  } catch (reason) { showError(reason, '保存失败') }
  finally { saving.value = false }
}

async function startResearch(item) {
  try {
    let sessionId = item.researchSessionId
    if (!sessionId) {
      const session = await createResearchSession({
        paperIds: [item.paperId], primaryPaperId: item.paperId,
        title: `${selectedPlan.value.name} · ${item.paperTitle}`, mode: 'analysis', lastPage: 1, outputLanguage: 'ZH',
      })
      sessionId = session.id
      await updatePlanItem(item.planId, item.id, { researchSessionId: sessionId, status: 'IN_PROGRESS' })
    } else if (item.status === 'TODO') {
      await updatePlanItem(item.planId, item.id, { status: 'IN_PROGRESS' })
    }
    await router.push({
      path: `/research/${item.paperId}`,
      query: { session: String(sessionId), page: '1', mode: 'analysis', returnTo: `/reading-plans?plan=${item.planId}` },
    })
  } catch (reason) { showError(reason, '无法开始研究') }
}

function openOutcomeDialog(item) {
  outcomeItem.value = item
  outcomeText.value = item.outcome || ''
  outcomeDialogVisible.value = true
}

async function saveOutcome() {
  if (!outcomeText.value.trim()) return ElMessage.warning('请先填写阅读产出')
  saving.value = true
  try {
    await updatePlanItem(outcomeItem.value.planId, outcomeItem.value.id, { outcome: outcomeText.value.trim(), status: 'DONE' })
    outcomeDialogVisible.value = false
    await refreshSelectedPlan()
    ElMessage.success('阅读产出已保存')
  } catch (reason) { showError(reason, '保存失败') }
  finally { saving.value = false }
}

async function resetItem(item) {
  try {
    await updatePlanItem(item.planId, item.id, { status: 'TODO' })
    await refreshSelectedPlan()
  } catch (reason) { showError(reason, '操作失败') }
}

async function removeItem(itemId) {
  try {
    await ElMessageBox.confirm('从计划中移除该论文及其阅读产出？', '移除论文', { type: 'warning' })
    await deletePlanItem(selectedPlan.value.id, itemId)
    await refreshSelectedPlan()
    ElMessage.success('已移除')
  } catch (reason) { if (!['cancel', 'close'].includes(reason)) showError(reason, '移除失败') }
}

async function refreshSelectedPlan() {
  await Promise.all([selectPlan(selectedPlan.value), loadPlans(), loadWeekly()])
}

async function openWeeklyItem(item) {
  const plan = plans.value.find(candidate => Number(candidate.id) === Number(item.planId))
  if (plan) await selectPlan(plan)
}

async function loadReminders() {
  try {
    const response = await getReminders()
    const list = response.data || []
    if (!list.length) return
    const today = new Date().toISOString().split('T')[0]
    const overdue = list.filter(item => item.deadline && item.deadline < today).length
    ElNotification({ title: '阅读提醒', message: overdue ? `${overdue} 项已逾期，${list.length} 项待处理` : `${list.length} 项将在三天内到期`, type: overdue ? 'warning' : 'info', duration: 6000 })
  } catch { /* reminders are secondary */ }
}

function paperHasPdf(paperId) { return Boolean(papers.value.find(paper => Number(paper.id) === Number(paperId))?.pdfPath) }
function planProgress(plan) { return planCompletion(plan.doneItems || 0, plan.totalItems || 0) }
function dateRange(plan) { return plan.startDate || plan.endDate ? `${formatDate(plan.startDate) || '未定'} – ${formatDate(plan.endDate) || '未定'}` : '未设置时间范围' }
function formatDate(value) { return value ? String(value).slice(0, 10) : '' }
function formatDateTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '刚刚' }
function toDateString(value) { return value ? (typeof value === 'string' ? value.slice(0, 10) : value.toISOString().slice(0, 10)) : null }
function isOverdue(value) { return value && String(value).slice(0, 10) < new Date().toISOString().slice(0, 10) }
function statusText(status) { return { TODO: '待读', IN_PROGRESS: '研究中', DONE: '已有产出' }[status] || status }
function statusType(status) { return status === 'DONE' ? 'success' : status === 'IN_PROGRESS' ? 'warning' : 'info' }
function showError(reason, fallback) { ElMessage.error(reason?.response?.data?.message || reason?.message || fallback) }
</script>

<style scoped>
.reading-plan-page { display: flex; height: calc(100vh - 61px); min-height: 0; background: var(--ra-bg); color: var(--ra-text); }
.plan-sidebar { flex: 0 0 310px; overflow-y: auto; padding: 18px; border-right: 1px solid var(--ra-border); background: var(--ra-panel-bg); box-sizing: border-box; }
.sidebar-heading, .workspace-heading, .reading-item > header, .plan-card__heading, .plan-card footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.sidebar-heading { align-items: flex-start; margin-bottom: 16px; }
.sidebar-heading h3, .workspace-heading h2 { margin: 0; }
.sidebar-heading small, .workspace-heading span { color: var(--ra-text-tertiary); font-size: 11px; }
.sidebar-heading > div, .workspace-heading > div:first-child { display: flex; flex-direction: column; gap: 4px; }
.plan-list { display: flex; flex-direction: column; gap: 9px; }
.plan-card { padding: 11px; border: 1px solid var(--ra-border); border-radius: 8px; background: var(--ra-panel-bg); cursor: pointer; }
.plan-card:hover, .plan-card:focus-visible, .plan-card.active { border-color: var(--ra-link); outline: none; }
.plan-card.active { background: var(--ra-active-bg); }
.plan-card__heading b { overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.plan-card__heading span, .plan-card footer { color: var(--ra-text-tertiary); font-size: 9px; }
.plan-card p { display: -webkit-box; overflow: hidden; margin: 7px 0 9px; color: var(--ra-text-secondary); font-size: 11px; line-height: 1.45; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.plan-card footer { margin-top: 8px; }
.plan-card footer div { display: flex; gap: 7px; }
.plan-card footer button { padding: 0; border: 0; color: var(--ra-link); background: transparent; font-size: 9px; cursor: pointer; }
.plan-card footer button.danger { color: var(--el-color-danger); }
.weekly-section { margin-top: 22px; padding-top: 15px; border-top: 1px solid var(--ra-border); }
.weekly-section h4 { margin: 0 0 9px; }
.weekly-item { display: flex; flex-direction: column; gap: 3px; width: 100%; margin-bottom: 6px; padding: 8px; border: 0; border-radius: 6px; color: var(--ra-text); background: var(--ra-bg); text-align: left; cursor: pointer; }
.weekly-item span, .weekly-item small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.weekly-item span { font-size: 11px; }.weekly-item small { color: var(--ra-text-tertiary); font-size: 9px; }
.plan-workspace { flex: 1; min-width: 0; overflow-y: auto; padding: 22px 26px; box-sizing: border-box; }
.workspace-heading { margin-bottom: 15px; }.workspace-heading h2 { font-size: 20px; }.workspace-heading > div:last-child { display: flex; gap: 8px; }
.goal-card { display: grid; grid-template-columns: minmax(0, 1.3fr) minmax(0, 1fr) 180px; gap: 16px; margin-bottom: 16px; padding: 15px; border: 1px solid var(--ra-border); border-radius: 9px; background: var(--ra-panel-bg); }
.goal-card__copy small, .reading-question small, .reading-outcome small { color: var(--ra-text-tertiary); font-size: 9px; }.goal-card__copy p { margin: 6px 0 0; font-size: 12px; line-height: 1.55; white-space: pre-wrap; }
.goal-card__copy.criteria { padding-left: 15px; border-left: 1px solid var(--ra-border); }
.goal-card__progress { display: flex; flex-direction: column; justify-content: center; }.goal-card__progress b { font-size: 21px; }.goal-card__progress span { margin: 2px 0 8px; color: var(--ra-text-tertiary); font-size: 10px; }
.reading-item-list { display: flex; flex-direction: column; gap: 10px; }
.reading-item { padding: 14px; border: 1px solid var(--ra-border); border-left: 4px solid var(--ra-border); border-radius: 8px; background: var(--ra-panel-bg); }.reading-item.is-in_progress { border-left-color: var(--el-color-warning); }.reading-item.is-done { border-left-color: var(--el-color-success); }
.paper-copy { min-width: 0; }.paper-title-line { display: flex; align-items: center; gap: 7px; }.paper-title-line h3 { overflow: hidden; margin: 0; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.paper-meta { display: flex; gap: 12px; margin-top: 5px; color: var(--ra-text-tertiary); font-size: 9px; }.paper-meta .overdue { color: var(--el-color-danger); }
.item-actions { display: flex; flex: 0 0 auto; align-items: center; gap: 4px; }
.reading-question, .reading-outcome { margin-top: 11px; padding: 9px 10px; border-radius: 6px; background: var(--ra-bg); }.reading-question p, .reading-outcome p { margin: 4px 0 0; font-size: 11px; line-height: 1.55; white-space: pre-wrap; }
.reading-outcome { background: color-mix(in srgb, var(--el-color-success) 7%, var(--ra-panel-bg)); }.outcome-pending { margin-top: 9px; color: var(--ra-text-tertiary); font-size: 9px; }
.date-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }.empty-hint { padding: 25px 0; color: var(--ra-text-tertiary); font-size: 12px; text-align: center; }.empty-hint.compact { padding: 10px 0; }.outcome-dialog-question { margin: 0 0 10px; color: var(--ra-text-secondary); font-size: 12px; }
@media (max-width: 1100px) { .plan-sidebar { flex-basis: 260px; }.goal-card { grid-template-columns: 1fr 150px; }.goal-card__copy.criteria { display: none; } }
</style>
