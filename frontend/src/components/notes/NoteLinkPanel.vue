<template>
  <div class="note-panel">
    <div class="note-panel-header">
      <span>论文笔记</span>
      <el-button size="small" text @click="$emit('new-note')">+ 新建</el-button>
    </div>
    <div v-if="notes.length" class="note-list">
      <div
        v-for="note in notes"
        :key="note.id"
        class="note-item"
        :class="{ active: selectedId === note.id }"
        @click="select(note)"
      >
        <div class="note-title">{{ note.title || '无标题' }}</div>
        <div class="note-meta">第 {{ note.page || '-' }} 页 · {{ formatTime(note.updatedAt) }}</div>
        <div class="note-actions">
          <el-button size="small" text @click.stop="$emit('edit', note)">编辑</el-button>
          <el-button size="small" text type="danger" @click.stop="$emit('delete', note)">删除</el-button>
        </div>
      </div>
    </div>
    <div v-else class="note-empty">暂无笔记，选中文字后右键可创建</div>
  </div>
</template>

<script setup>
defineProps({
  notes: { type: Array, default: () => [] },
  selectedId: { type: Number, default: null }
})

const emit = defineEmits(['new-note', 'edit', 'delete', 'select'])

function select(note) {
  emit('select', note)
}

function formatTime(t) {
  if (!t) return ''
  return new Date(t).toLocaleString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
</script>

<style scoped>
.note-panel {
  width: 240px;
  background: var(--ra-panel-bg);
  border-left: 1px solid var(--ra-border);
  display: flex;
  flex-direction: column;
  height: 100%;
}
.note-panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  border-bottom: 1px solid var(--ra-border-light);
  font-size: 14px;
  font-weight: 600;
}
.note-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}
.note-item {
  padding: 10px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 8px;
  border: 1px solid var(--ra-border-light);
}
.note-item:hover, .note-item.active {
  background: var(--ra-active-bg);
}
.note-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--ra-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.note-meta {
  font-size: 11px;
  color: var(--ra-text-tertiary);
  margin-top: 4px;
}
.note-actions {
  display: flex;
  gap: 4px;
  margin-top: 6px;
}
.note-empty {
  padding: 20px;
  text-align: center;
  font-size: 12px;
  color: var(--ra-text-tertiary);
}
</style>
