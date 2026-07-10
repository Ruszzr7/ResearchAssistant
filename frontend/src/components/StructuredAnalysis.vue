<template>
  <div class="structured-analysis">
    <!-- 相关度评分 -->
    <div v-if="data?.relevanceScore != null" class="sa-block relevance">
      <div class="sa-title">
        相关度评分
        <el-tag :type="relevanceTagType" size="small">{{ data.relevanceScore }} / 10</el-tag>
      </div>
      <div class="sa-body">{{ data.relevanceReason || '暂无说明' }}</div>
    </div>

    <!-- 核心信息 -->
    <div class="sa-block">
      <div class="sa-title">核心贡献</div>
      <div class="sa-body">{{ data?.coreContribution || '未提取' }}</div>
    </div>

    <div class="sa-block">
      <div class="sa-title">方法概述</div>
      <div class="sa-body">{{ data?.methodSummary || '未提取' }}</div>
    </div>

    <!-- 实验设置 -->
    <div class="sa-block">
      <div class="sa-title">实验设置</div>
      <el-descriptions v-if="experimentSetup" :column="1" size="small" border>
        <el-descriptions-item label="任务定义">{{ experimentSetup.taskDefinition || '--' }}</el-descriptions-item>
        <el-descriptions-item label="数据集">{{ formatList(experimentSetup.datasets) }}</el-descriptions-item>
        <el-descriptions-item label="基线方法">{{ formatList(experimentSetup.baselines) }}</el-descriptions-item>
        <el-descriptions-item label="评测指标">{{ formatList(experimentSetup.metrics) }}</el-descriptions-item>
        <el-descriptions-item label="实现细节">{{ experimentSetup.implementationDetails || '--' }}</el-descriptions-item>
      </el-descriptions>
      <div v-else class="sa-empty">未提取到实验设置</div>
    </div>

    <!-- Benchmark 结果 -->
    <div class="sa-block">
      <div class="sa-title">Benchmark 结果</div>
      <el-table v-if="benchmarkResults?.length" :data="benchmarkResults" size="small" border>
        <el-table-column prop="metric" label="指标" min-width="100" />
        <el-table-column prop="value" label="论文值" min-width="90" />
        <el-table-column prop="baselineValue" label="基线值" min-width="90" />
        <el-table-column prop="dataset" label="数据集" min-width="110" />
        <el-table-column prop="source" label="来源" min-width="80" />
        <el-table-column prop="note" label="说明" min-width="140" show-overflow-tooltip />
      </el-table>
      <div v-else class="sa-empty">未提取到 Benchmark 结果</div>
    </div>

    <!-- 可复现要素 -->
    <div class="sa-block">
      <div class="sa-title">可复现要素</div>
      <div v-if="reproducibleArtifacts?.length">
        <div v-for="(item, idx) in reproducibleArtifacts" :key="idx" class="artifact-item">
          <div class="artifact-header">
            <el-tag size="small">{{ artifactTypeLabel(item.type) }}</el-tag>
            <span class="artifact-title">{{ item.title || '未命名' }}</span>
            <span v-if="item.location" class="artifact-location">{{ item.location }}</span>
          </div>
          <pre v-if="item.content" class="artifact-content">{{ item.content }}</pre>
        </div>
      </div>
      <div v-else class="sa-empty">未提取到可复现要素</div>
    </div>

    <!-- 公式 -->
    <div class="sa-block">
      <div class="sa-title">识别公式</div>
      <div v-if="formulas?.length">
        <pre v-for="(formula, idx) in formulas" :key="idx" class="artifact-content">{{ formula }}</pre>
      </div>
      <div v-else class="sa-empty">未提取到公式</div>
    </div>

    <!-- 图表 -->
    <div class="sa-block">
      <div class="sa-title">图表与表格</div>
      <div v-if="figures?.length">
        <div v-for="(fig, idx) in figures" :key="idx" class="artifact-item">
          <div class="artifact-header">
            <el-tag size="small" :type="fig.type === 'TABLE' ? 'warning' : 'info'">{{ typeLabel(fig.type) }}</el-tag>
            <span class="artifact-title">{{ fig.caption || '未命名' }}</span>
            <span class="artifact-location">第 {{ fig.page }} 页</span>
          </div>
          <div v-if="fig.imagePath" class="figure-image-hint">已保存图片: {{ fig.imagePath }}</div>
        </div>
      </div>
      <div v-else class="sa-empty">未提取到图表或表格</div>
    </div>

    <!-- 主要发现与局限 -->
    <div class="sa-block">
      <div class="sa-title">主要发现</div>
      <ul v-if="keyFindings?.length">
        <li v-for="(item, idx) in keyFindings" :key="idx">{{ item }}</li>
      </ul>
      <div v-else class="sa-empty">未提取</div>
    </div>

    <div class="sa-block">
      <div class="sa-title">局限性</div>
      <ul v-if="limitations?.length">
        <li v-for="(item, idx) in limitations" :key="idx">{{ item }}</li>
      </ul>
      <div v-else class="sa-empty">未提取</div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  data: {
    type: Object,
    default: () => ({})
  }
})

const relevanceTagType = computed(() => {
  const score = props.data?.relevanceScore
  if (score == null) return 'info'
  if (score >= 8) return 'success'
  if (score >= 5) return 'warning'
  return 'danger'
})

const experimentSetup = computed(() => safeJsonParse(props.data?.experimentSetupJson))
const benchmarkResults = computed(() => safeJsonParse(props.data?.benchmarkResultsJson, []))
const reproducibleArtifacts = computed(() => safeJsonParse(props.data?.reproducibleArtifactsJson, []))
const keyFindings = computed(() => safeJsonParse(props.data?.keyFindingsJson, []))
const limitations = computed(() => safeJsonParse(props.data?.limitationsJson, []))
const formulas = computed(() => safeJsonParse(props.data?.formulasJson, []))
const figures = computed(() => safeJsonParse(props.data?.figuresJson, []))

function safeJsonParse(json, fallback = null) {
  if (!json) return fallback
  try {
    return JSON.parse(json)
  } catch (e) {
    return fallback
  }
}

function formatList(list) {
  if (!list || !list.length) return '--'
  return list.join('、')
}

function artifactTypeLabel(type) {
  const map = {
    FORMULA: '公式',
    PSEUDOCODE: '伪代码',
    SOURCE_CODE: '源码链接',
    DATASET: '数据集',
    METRIC: '指标',
    OTHER: '其他'
  }
  return map[type] || type || '其他'
}

function typeLabel(type) {
  if (type === 'TABLE') return '表格'
  return '图表'
}
</script>

<style scoped>
.structured-analysis {
  padding: 16px;
  background: var(--ra-panel-bg);
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.7;
}

.sa-block {
  margin-bottom: 20px;
}

.sa-title {
  font-weight: 600;
  color: var(--ra-text);
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.sa-body {
  color: var(--ra-text-secondary);
  white-space: pre-wrap;
}

.sa-empty {
  color: var(--ra-text-tertiary);
  font-size: 13px;
  padding: 8px 0;
}

.relevance .sa-title {
  font-size: 15px;
}

.artifact-item {
  margin-bottom: 12px;
  padding: 10px 12px;
  background: var(--ra-bg);
  border-radius: 6px;
}

.artifact-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.artifact-title {
  font-weight: 500;
  color: var(--ra-text);
}

.artifact-location {
  margin-left: auto;
  color: var(--ra-text-tertiary);
  font-size: 12px;
}

.artifact-content {
  margin: 0;
  padding: 8px;
  background: var(--ra-panel-bg);
  border-radius: 4px;
  overflow-x: auto;
  font-family: 'Courier New', Consolas, monospace;
  font-size: 12px;
  color: var(--ra-text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}

ul {
  margin: 0;
  padding-left: 18px;
  color: var(--ra-text-secondary);
}

li {
  margin-bottom: 4px;
}
.figure-image-hint {
  font-size: 12px;
  color: var(--ra-text-tertiary);
  padding: 6px 0 0;
  word-break: break-all;
}
</style>
