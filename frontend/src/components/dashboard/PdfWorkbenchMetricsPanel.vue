<template>
  <el-card shadow="hover" class="workbench-quality-card">
    <template #header>
      <div class="quality-header">
        <div>
          <b>PDF 工作台质量</b>
          <small>版面、证据与固定 Workflow 的可验证指标</small>
        </div>
        <div class="quality-actions">
          <el-select v-model="days" size="small" aria-label="指标时间范围" @change="load">
            <el-option label="近 7 天" :value="7" />
            <el-option label="近 30 天" :value="30" />
            <el-option label="近 90 天" :value="90" />
          </el-select>
          <el-button text size="small" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-skeleton v-if="loading && !metrics" :rows="5" animated />
    <div v-else-if="error" class="quality-error">
      <span>{{ error }}</span>
      <el-button size="small" @click="load">重试</el-button>
    </div>
    <template v-else-if="metrics">
      <div class="quality-grid">
        <div class="quality-stat" :class="{ healthy: evalHealth.healthy, warning: !evalHealth.healthy }">
          <span>确定性评测</span>
          <b>{{ evalHealth.deterministic }}</b>
          <small>{{ evalHealth.healthy ? '当前规则全部通过' : '存在回归，请检查 case' }}</small>
        </div>
        <div class="quality-stat">
          <span>真实 PDF 评测</span>
          <b>{{ evalHealth.real }}</b>
          <small>另有 {{ evalHealth.realSkipped }} 个样本未配置</small>
        </div>
        <div class="quality-stat">
          <span>平均版面质量</span>
          <b>{{ formatMetricPercent(metrics.layout.averageQuality, 1) }}</b>
          <small>{{ metrics.layout.latestArtifacts }} 份最新制品 · 低质量 {{ metrics.layout.lowQualityArtifacts }}</small>
        </div>
        <div class="quality-stat">
          <span>Workflow 完成率</span>
          <b>{{ formatMetricPercent(metrics.runs.completionRate, 1) }}</b>
          <small>{{ metrics.runs.completed }}/{{ terminalRuns }} 个终态运行完成</small>
        </div>
        <div class="quality-stat">
          <span>Claim 证据覆盖</span>
          <b>{{ formatMetricPercent(metrics.evidence.claimCoverage, 1) }}</b>
          <small>{{ metrics.evidence.groundedClaims }}/{{ metrics.evidence.claims }} 条结论已回链</small>
        </div>
        <div class="quality-stat">
          <span>多篇逐论文覆盖</span>
          <b>{{ comparisonCoverageText }}</b>
          <small>{{ metrics.evidence.fullyCoveredComparisonRuns }}/{{ metrics.evidence.comparisonRuns }} 次对比覆盖全部论文</small>
        </div>
      </div>

      <div class="quality-details">
        <div class="layout-health">
          <div class="detail-heading">
            <b>版面与降级</b>
            <span v-if="metrics.runWindowTruncated">运行窗口已截断至 5000 条</span>
          </div>
          <div class="layout-lines">
            <div>
              <span>解析回退</span>
              <b>{{ metrics.layout.fallbackAccepted }}/{{ metrics.layout.fallbackAttempted }} 采用</b>
              <small>平均质量增益 {{ formatMetricPercent(metrics.layout.averageAcceptedQualityGain, 1) }}</small>
            </div>
            <div>
              <span>内容精度</span>
              <b>{{ metrics.layout.textBlocks }} 文本 · {{ metrics.layout.structuredBlocks }} 结构化</b>
              <small>{{ metrics.layout.regionBlocks }} 个区域需回原页核对</small>
            </div>
            <div>
              <span>证据门禁</span>
              <b>{{ metrics.evidence.evidenceGateRejectedFailures }} 次拒绝</b>
              <small>{{ metrics.evidence.noEvidenceFailures }} 次没有安全证据 · {{ metrics.evidence.repairedRuns }} 次 repair</small>
            </div>
          </div>
        </div>

        <div class="workflow-health">
          <div class="detail-heading"><b>逐 Workflow</b></div>
          <div class="workflow-row workflow-row--head">
            <span>功能</span><span>完成</span><span>repair</span><span>耗时</span><span>tokens</span>
          </div>
          <div v-for="row in workflowRows" :key="row.workflow" class="workflow-row">
            <span>{{ row.label }} <small>{{ row.completed }}/{{ row.terminal }}</small></span>
            <span>{{ formatMetricPercent(row.completionRate) }}</span>
            <span>{{ formatMetricPercent(row.repairRate) }}</span>
            <span>{{ row.averageLatency }}</span>
            <span>{{ row.averageTokens }}</span>
          </div>
        </div>
      </div>

      <div class="quality-footnote">
        仅聚合质量、状态、耗时与计数，不读取或展示论文正文、问题和文件路径。
      </div>
    </template>
  </el-card>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { getWorkbenchMetrics } from '@/api/workbench.js'
import {
  evaluationHealth,
  formatMetricPercent,
  workflowMetricRows,
} from '@/utils/workbenchMetrics.js'

const days = ref(30)
const loading = ref(false)
const metrics = ref(null)
const error = ref('')

const evalHealth = computed(() => evaluationHealth(metrics.value?.evaluation))
const workflowRows = computed(() => workflowMetricRows(metrics.value))
const terminalRuns = computed(() => (metrics.value?.runs?.completed || 0)
  + (metrics.value?.runs?.failed || 0) + (metrics.value?.runs?.cancelled || 0))
const comparisonCoverageText = computed(() => {
  const total = metrics.value?.evidence?.comparisonRuns || 0
  return total ? formatMetricPercent(metrics.value.evidence.comparisonCoverageRate, 1) : '暂无运行'
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    metrics.value = await getWorkbenchMetrics(days.value)
  } catch {
    error.value = '工作台质量指标加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.workbench-quality-card { margin-top: 16px; border: 1px solid var(--ra-border); background: var(--ra-header-bg); }
.quality-header, .quality-actions, .detail-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.quality-header > div:first-child { display: flex; flex-direction: column; gap: 3px; }
.quality-header b { color: var(--ra-text); font-size: 14px; }
.quality-header small, .detail-heading span { color: var(--ra-text-tertiary); font-size: 10px; }
.quality-actions .el-select { width: 94px; }
.quality-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 10px; }
.quality-stat { display: flex; min-width: 0; min-height: 82px; flex-direction: column; justify-content: center; padding: 11px; border: 1px solid var(--ra-border); border-radius: 8px; background: var(--ra-bg); }
.quality-stat > span { color: var(--ra-text-secondary); font-size: 11px; }
.quality-stat > b { margin: 5px 0 3px; color: var(--ra-text); font-size: 20px; line-height: 1; }
.quality-stat > small { overflow: hidden; color: var(--ra-text-tertiary); font-size: 9px; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
.quality-stat.healthy { border-color: color-mix(in srgb, #4caf50 42%, var(--ra-border)); }
.quality-stat.warning { border-color: color-mix(in srgb, #e6a23c 52%, var(--ra-border)); }
.quality-details { display: grid; grid-template-columns: minmax(0, .9fr) minmax(0, 1.1fr); gap: 12px; margin-top: 12px; }
.layout-health, .workflow-health { min-width: 0; padding: 12px; border: 1px solid var(--ra-border); border-radius: 8px; }
.detail-heading { min-height: 20px; color: var(--ra-text); font-size: 12px; }
.layout-lines { display: grid; gap: 8px; margin-top: 8px; }
.layout-lines > div { display: grid; grid-template-columns: 76px minmax(0, 1fr); gap: 2px 8px; padding-top: 7px; border-top: 1px solid var(--ra-border); }
.layout-lines span { grid-row: 1 / span 2; align-self: center; color: var(--ra-text-secondary); font-size: 11px; }
.layout-lines b { overflow: hidden; color: var(--ra-text); font-size: 11px; font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }
.layout-lines small { color: var(--ra-text-tertiary); font-size: 9px; }
.workflow-row { display: grid; grid-template-columns: minmax(90px, 1.3fr) repeat(4, minmax(54px, .7fr)); gap: 6px; padding: 7px 0; border-top: 1px solid var(--ra-border); color: var(--ra-text-secondary); font-size: 10px; text-align: right; }
.workflow-row > span:first-child { overflow: hidden; color: var(--ra-text); text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.workflow-row small { color: var(--ra-text-tertiary); font-size: 9px; }
.workflow-row--head { margin-top: 6px; color: var(--ra-text-tertiary); }
.workflow-row--head > span:first-child { color: var(--ra-text-tertiary); }
.quality-footnote { margin-top: 10px; color: var(--ra-text-tertiary); font-size: 9px; text-align: right; }
.quality-error { display: flex; align-items: center; justify-content: center; gap: 10px; min-height: 100px; color: var(--el-color-danger); font-size: 12px; }
@media (max-width: 1200px) { .quality-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 760px) { .quality-grid, .quality-details { grid-template-columns: 1fr; } .quality-header { align-items: flex-start; } }
</style>
