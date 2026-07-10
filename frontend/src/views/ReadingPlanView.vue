<template>
  <div class="reading-plan-page">
    <div class="left-panel">
      <div class="panel-header">
        <h4>阅读计划</h4>
        <el-button type="primary" size="small" @click="openPlanDialog()">新建</el-button>
      </div>

      <div v-if="plans.length === 0" class="empty-hint">暂无阅读计划</div>
      <div v-else class="plan-list">
        <div
          v-for="plan in plans"
          :key="plan.id"
          class="plan-card"
          :class="{ active: selectedPlan?.id === plan.id }"
          @click="selectPlan(plan)"
        >
          <div class="plan-status-bar" :class="planStatusClass(plan)"></div>
          <div class="plan-content">
            <div class="plan-name">{{ plan.name }}</div>
            <div class="plan-meta">
              <span v-if="plan.startDate && plan.endDate">
                {{ formatDate(plan.startDate) }} ~ {{ formatDate(plan.endDate) }}
              </span>
              <span v-else>未设置时间范围</span>
            </div>
            <div class="plan-progress">
              <span v-if="plan.totalItems">{{ plan.doneItems }}/{{ plan.totalItems }} 完成</span>
              <span v-else>空计划</span>
            </div>
            <div class="plan-actions">
              <el-button size="small" link @click.stop="openPlanDialog(plan)">编辑</el-button>
              <el-button size="small" link type="danger" @click.stop="deletePlan(plan.id)">删除</el-button>
            </div>
          </div>
        </div>
      </div>

      <div class="weekly-section">
        <h4>本周要读</h4>
        <div v-if="weeklyItems.length === 0" class="empty-hint">本周没有待读任务</div>
        <div v-else class="weekly-list">
          <div v-for="item in weeklyItems" :key="item.id" class="weekly-item">
            <div class="weekly-title" :title="item.paperTitle">{{ item.paperTitle || '未命名论文' }}</div>
            <div class="weekly-meta">
              <el-tag size="small" :type="statusType(item.status)">{{ statusText(item.status) }}</el-tag>
              <span v-if="item.deadline" :class="{ overdue: isOverdue(item.deadline) }">
                {{ formatDate(item.deadline) }}
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="right-panel">
      <div v-if="!selectedPlan" class="empty-hint-large">请从左侧选择一个阅读计划</div>
      <template v-else>
        <div class="panel-header">
          <h3>{{ selectedPlan.name }}</h3>
          <el-button type="primary" size="small" @click="openItemDialog()">添加论文</el-button>
        </div>

        <div v-if="items.length === 0" class="empty-hint-large">计划为空，点击右上角添加论文</div>
        <el-table v-else :data="items" style="width: 100%">
          <el-table-column prop="paperTitle" label="论文" min-width="180" show-overflow-tooltip />
          <el-table-column label="标签" min-width="140">
            <template #default="{ row }">
              <el-tag v-for="tag in (row.paperTags || [])" :key="tag" size="small" style="margin-right:4px">{{ tag }}</el-tag>
              <span v-if="!(row.paperTags || []).length" style="color:var(--ra-text-tertiary);font-size:12px">—</span>
            </template>
          </el-table-column>
          <el-table-column label="截止日期" width="120">
            <template #default="{ row }">
              {{ row.deadline ? formatDate(row.deadline) : '—' }}
            </template>
          </el-table-column>
          <el-table-column label="优先级" width="90">
            <template #default="{ row }">
              <el-rate v-model="row.priority" :max="3" disabled />
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-select v-model="row.status" size="small" @change="updateStatus(row)">
                <el-option label="待读" value="TODO" />
                <el-option label="在读" value="IN_PROGRESS" />
                <el-option label="完成" value="DONE" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="备注" min-width="160">
            <template #default="{ row }">
              <span v-if="row.notes" class="item-notes" :title="row.notes">{{ row.notes }}</span>
              <span v-else style="color:var(--ra-text-tertiary);font-size:12px">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button size="small" link type="danger" @click="deleteItem(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </div>

    <!-- 计划表单弹窗 -->
    <el-dialog v-model="planDialogVisible" :title="editingPlan ? '编辑计划' : '新建计划'" width="480px">
      <el-form label-width="80px">
        <el-form-item label="名称">
          <el-input v-model="planForm.name" placeholder="例如：暑期精读计划" />
        </el-form-item>
        <el-form-item label="开始日期">
          <el-date-picker v-model="planForm.startDate" type="date" placeholder="选择开始日期" style="width:100%" />
        </el-form-item>
        <el-form-item label="结束日期">
          <el-date-picker v-model="planForm.endDate" type="date" placeholder="选择结束日期" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="planDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="savePlan">保存</el-button>
      </template>
    </el-dialog>

    <!-- 添加条目弹窗 -->
    <el-dialog v-model="itemDialogVisible" title="添加论文到计划" width="520px">
      <el-form label-width="80px">
        <el-form-item label="论文">
          <el-select v-model="itemForm.paperId" filterable placeholder="从文库选择论文" style="width:100%">
            <el-option v-for="p in papers" :key="p.id" :label="p.title" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="截止日期">
          <el-date-picker v-model="itemForm.deadline" type="date" placeholder="选择截止日期" style="width:100%" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-rate v-model="itemForm.priority" :max="3" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="itemForm.notes" type="textarea" :rows="3" placeholder="记录阅读目标、关注点等" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveItem">添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listReadingPlans,
  getReadingPlan,
  createReadingPlan,
  updateReadingPlan,
  deleteReadingPlan,
  addPlanItem,
  updatePlanItem,
  deletePlanItem,
  getWeeklyReading,
} from '@/api/readingPlan'
import { listPapers } from '@/api/paper'

const plans = ref([])
const selectedPlan = ref(null)
const items = ref([])
const weeklyItems = ref([])
const papers = ref([])

const planDialogVisible = ref(false)
const editingPlan = ref(null)
const planForm = reactive({ name: '', startDate: null, endDate: null })

const itemDialogVisible = ref(false)
const itemForm = reactive({ paperId: null, deadline: null, priority: 1, notes: '' })

async function loadPlans() {
  const res = await listReadingPlans()
  plans.value = res.data
}

async function loadWeekly() {
  const res = await getWeeklyReading()
  weeklyItems.value = res.data
}

async function loadPapers() {
  papers.value = await listPapers()
}

async function selectPlan(plan) {
  selectedPlan.value = plan
  const res = await getReadingPlan(plan.id)
  items.value = res.data.items || []
}

function openPlanDialog(plan = null) {
  editingPlan.value = plan
  if (plan) {
    planForm.name = plan.name
    planForm.startDate = plan.startDate
    planForm.endDate = plan.endDate
  } else {
    planForm.name = ''
    planForm.startDate = null
    planForm.endDate = null
  }
  planDialogVisible.value = true
}

async function savePlan() {
  if (!planForm.name.trim()) {
    ElMessage.warning('请输入计划名称')
    return
  }
  const payload = {
    name: planForm.name.trim(),
    startDate: toDateString(planForm.startDate),
    endDate: toDateString(planForm.endDate),
  }
  if (editingPlan.value) {
    await updateReadingPlan(editingPlan.value.id, payload)
    if (selectedPlan.value?.id === editingPlan.value.id) {
      selectedPlan.value.name = payload.name
    }
  } else {
    await createReadingPlan(payload)
  }
  planDialogVisible.value = false
  await loadPlans()
  ElMessage.success('已保存')
}

async function deletePlan(id) {
  try {
    await ElMessageBox.confirm('删除计划会同时删除其中的所有论文条目，是否继续？', '确认删除', { type: 'warning' })
    await deleteReadingPlan(id)
    if (selectedPlan.value?.id === id) {
      selectedPlan.value = null
      items.value = []
    }
    await loadPlans()
    await loadWeekly()
    ElMessage.success('已删除')
  } catch (e) { /* cancel */ }
}

function openItemDialog() {
  itemForm.paperId = null
  itemForm.deadline = null
  itemForm.priority = 1
  itemForm.notes = ''
  itemDialogVisible.value = true
}

async function saveItem() {
  if (!itemForm.paperId) {
    ElMessage.warning('请选择论文')
    return
  }
  await addPlanItem(selectedPlan.value.id, {
    paperId: itemForm.paperId,
    deadline: toDateString(itemForm.deadline),
    priority: itemForm.priority || 0,
    notes: itemForm.notes || null,
  })
  itemDialogVisible.value = false
  await selectPlan(selectedPlan.value)
  await loadWeekly()
  ElMessage.success('已添加')
}

async function updateStatus(row) {
  await updatePlanItem(row.planId, row.id, { status: row.status })
  await loadWeekly()
}

async function deleteItem(itemId) {
  try {
    await ElMessageBox.confirm('确定从计划中移除该论文？', '确认移除', { type: 'warning' })
    await deletePlanItem(selectedPlan.value.id, itemId)
    await selectPlan(selectedPlan.value)
    await loadWeekly()
    ElMessage.success('已移除')
  } catch (e) { /* cancel */ }
}

function formatDate(d) {
  if (!d) return ''
  return d.substring ? d.substring(0, 10) : new Date(d).toISOString().split('T')[0]
}

function toDateString(v) {
  if (!v) return null
  return v.substring ? v.substring(0, 10) : new Date(v).toISOString().split('T')[0]
}

function isOverdue(d) {
  return d && d < new Date().toISOString().split('T')[0]
}

function statusType(s) {
  if (s === 'DONE') return 'success'
  if (s === 'IN_PROGRESS') return 'warning'
  return 'info'
}

function statusText(s) {
  if (s === 'DONE') return '完成'
  if (s === 'IN_PROGRESS') return '在读'
  return '待读'
}

function planStatusClass(plan) {
  const total = plan.totalItems || 0
  if (!total) return 'status-empty'
  if (plan.doneItems === total) return 'status-done'
  if (plan.inProgressItems > 0) return 'status-progress'
  return 'status-todo'
}

onMounted(() => {
  loadPlans()
  loadWeekly()
  loadPapers()
})
</script>

<style scoped>
.reading-plan-page {
  display: flex;
  height: calc(100vh - 61px);
  background: var(--ra-bg);
}
.left-panel {
  width: 320px;
  border-right: 1px solid var(--ra-border);
  background: var(--ra-panel-bg);
  padding: 20px;
  overflow-y: auto;
}
.right-panel {
  flex: 1;
  padding: 24px 32px;
  overflow-y: auto;
}
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.left-panel h4,
.right-panel h3 {
  margin: 0;
}
.plan-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.plan-card {
  display: flex;
  border: 1px solid var(--ra-border);
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
}
.plan-card:hover { background: var(--ra-hover-bg); }
.plan-card.active {
  border-color: var(--ra-link);
  background: var(--ra-active-bg);
}
.plan-status-bar {
  width: 4px;
  flex-shrink: 0;
  background: #909399;
}
.plan-status-bar.status-done { background: #67c23a; }
.plan-status-bar.status-progress { background: #409eff; }
.plan-status-bar.status-todo { background: #909399; }
.plan-status-bar.status-empty { background: #c0c4cc; }
.plan-content {
  flex: 1;
  padding: 12px;
  min-width: 0;
}
.plan-name { font-weight: 600; margin-bottom: 4px; }
.plan-meta { font-size: 12px; color: var(--ra-text-tertiary); margin-bottom: 4px; }
.plan-progress { font-size: 12px; color: var(--ra-text-secondary); margin-bottom: 8px; }
.plan-actions { display: flex; gap: 8px; }
.weekly-section {
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid var(--ra-border);
}
.weekly-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.weekly-item {
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--ra-bg);
}
.weekly-title {
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 4px;
}
.weekly-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--ra-text-secondary);
}
.weekly-meta .overdue { color: #f56c6c; font-weight: 600; }
.empty-hint {
  text-align: center;
  color: var(--ra-text-tertiary);
  font-size: 13px;
  padding: 20px 0;
}
.empty-hint-large {
  color: var(--ra-text-tertiary);
  font-size: 15px;
  text-align: center;
  padding-top: 80px;
}
.item-notes {
  display: inline-block;
  max-width: 100%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
  color: var(--ra-text-secondary);
}
</style>
