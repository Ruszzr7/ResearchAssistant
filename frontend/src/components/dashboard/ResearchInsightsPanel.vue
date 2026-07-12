<template>
  <el-row :gutter="16" class="insights-row">
    <el-col :xs="24" :lg="14">
      <el-card shadow="hover" class="panel-card">
        <template #header>
          <div class="panel-header">
            <span>引用图谱</span>
            <div class="graph-controls">
              <el-select v-model="selectedPaperId" size="small" placeholder="选择论文" filterable clearable>
                <el-option v-for="paper in papers" :key="paper.id" :label="paper.title" :value="paper.id" />
              </el-select>
              <el-button size="small" type="primary" :loading="graphLoading" :disabled="!selectedPaperId" @click="loadGraph">
                展开
              </el-button>
            </div>
          </div>
        </template>
        <div v-if="graphError" class="insight-error">{{ graphError }}</div>
        <div v-else-if="graphNodes.length" class="graph-wrap">
          <svg viewBox="0 0 720 260" role="img" aria-label="论文引用关系图">
            <line v-for="edge in graphEdges" :key="edge.key" v-bind="edgeLine(edge)" class="graph-edge" />
            <g v-for="node in graphNodes" :key="node.key" class="graph-node" :transform="`translate(${node.x},${node.y})`">
              <circle :r="node.root ? 28 : 22" :class="{ root: node.root }" />
              <text text-anchor="middle" y="42">{{ node.label }}</text>
            </g>
          </svg>
          <div class="graph-legend">节点数量 {{ graphNodes.length }} · 仅展示当前根论文的前后向引用候选</div>
        </div>
        <el-empty v-else description="选择论文后展开引用网络" :image-size="70" />
      </el-card>
    </el-col>

    <el-col :xs="24" :lg="10">
      <el-card shadow="hover" class="panel-card">
        <template #header><div class="panel-header"><span>研究主题概览</span><span class="muted">基于当前文库</span></div></template>
        <div v-if="topicStats.length" class="topic-list">
          <div v-for="item in topicStats" :key="item.label" class="topic-item">
            <div class="topic-meta"><span>{{ item.label }}</span><strong>{{ item.count }}</strong></div>
            <el-progress :percentage="item.percent" :stroke-width="7" :show-text="false" />
          </div>
        </div>
        <el-empty v-else description="暂无主题数据" :image-size="70" />
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '@/api'
import { listPapers } from '@/api/paper'

const papers = ref([])
const selectedPaperId = ref(null)
const graphLoading = ref(false)
const graphError = ref('')
const graphCandidates = ref([])

const graphNodes = computed(() => {
  const root = papers.value.find(p => String(p.id) === String(selectedPaperId.value))
  if (!root) return []
  const nodes = [{ key: `root-${root.id}`, label: shortTitle(root.title), root: true, x: 360, y: 130 }]
  const candidates = graphCandidates.value.slice(0, 10)
  candidates.forEach((candidate, index) => {
    const angle = (Math.PI * 2 * index) / Math.max(1, candidates.length)
    nodes.push({
      key: candidate.externalId || candidate.doi || candidate.title || String(index),
      label: shortTitle(candidate.title),
      root: false,
      x: 360 + Math.cos(angle) * 260,
      y: 130 + Math.sin(angle) * 88
    })
  })
  return nodes
})

const graphEdges = computed(() => graphNodes.value.slice(1).map(node => ({
  key: `root-${node.key}`,
  from: graphNodes.value[0],
  to: node
})))

const topicStats = computed(() => {
  const counts = new Map()
  for (const paper of papers.value) {
    const labels = String(paper.source || '未分类').split(/[;,/]/).map(v => v.trim()).filter(Boolean)
    for (const label of (labels.length ? labels : ['未分类']).slice(0, 2)) {
      counts.set(label, (counts.get(label) || 0) + 1)
    }
  }
  const values = [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 6)
  const max = Math.max(...values.map(([, count]) => count), 1)
  return values.map(([label, count]) => ({ label, count, percent: Math.round(count / max * 100) }))
})

function shortTitle(value) {
  const text = String(value || '未命名').trim()
  return text.length > 18 ? text.slice(0, 18) + '…' : text
}

function edgeLine(edge) {
  return { x1: edge.from.x, y1: edge.from.y, x2: edge.to.x, y2: edge.to.y }
}

async function loadGraph() {
  if (!selectedPaperId.value) return
  graphLoading.value = true
  graphError.value = ''
  try {
    const response = await api.post('/search/expand/network', {
      paperId: selectedPaperId.value,
      directions: ['forward', 'backward'],
      limit: 12
    })
    graphCandidates.value = response.data?.candidates || []
  } catch (error) {
    graphCandidates.value = []
    graphError.value = error.response?.data?.message || '引用网络暂时不可用'
  } finally {
    graphLoading.value = false
  }
}

onMounted(async () => {
  try {
    papers.value = await listPapers({ size: 200 })
  } catch (error) {
    graphError.value = '论文主题数据加载失败'
  }
})
</script>

<style scoped>
.insights-row { margin-top: 16px; }
.graph-controls { display: flex; gap: 8px; align-items: center; }
.graph-controls .el-select { width: 220px; }
.graph-wrap { min-height: 260px; }
.graph-wrap svg { width: 100%; height: 260px; overflow: visible; }
.graph-edge { stroke: var(--ra-border); stroke-width: 1.5; stroke-dasharray: 4 3; }
.graph-node circle { fill: var(--ra-panel-bg); stroke: #409eff; stroke-width: 2; }
.graph-node circle.root { fill: #409eff; stroke: #1d4f91; }
.graph-node text { fill: var(--ra-text-secondary); font-size: 11px; }
.graph-node.root text { fill: var(--ra-text); }
.graph-legend, .muted { color: var(--ra-text-tertiary); font-size: 12px; }
.insight-error { color: var(--el-color-danger); padding: 24px 0; }
.topic-list { display: flex; flex-direction: column; gap: 14px; padding: 6px 0; }
.topic-meta { display: flex; justify-content: space-between; color: var(--ra-text-secondary); font-size: 13px; margin-bottom: 5px; }
</style>
