<template>
  <template v-if="count > 0">
    <span class="toolbar-divider"></span>
    <span class="selection-count">已选 {{ count }}</span>
    <el-button size="small" text @click="$emit('move')">移动</el-button>
    <el-button size="small" text type="danger" @click="$emit('delete')">删除</el-button>
    <el-button size="small" text @click="$emit('clear')">取消</el-button>
  </template>
</template>

<script setup>
defineProps({ count: { type: Number, default: 0 } })
defineEmits(['move', 'delete', 'clear'])
</script>

<style scoped>
.selection-count {
  font-size: 12px;
  color: var(--ra-text-secondary);
}
</style>
