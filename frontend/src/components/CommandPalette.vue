<template>
  <el-dialog
    v-model="visible"
    width="520px"
    :show-close="false"
    :close-on-click-modal="true"
    :close-on-press-escape="true"
    class="command-palette-dialog"
    align-center
    @opened="focusInput"
  >
    <div class="command-palette">
      <el-input
        ref="inputRef"
        v-model="query"
        placeholder="输入命令或快捷键…"
        size="large"
        clearable
        @keydown.down.prevent="move(1)"
        @keydown.up.prevent="move(-1)"
        @keydown.enter.prevent="execute"
        @keydown.esc="visible = false"
      >
        <template #prefix>
          <span class="search-icon">Ctrl+K</span>
        </template>
      </el-input>

      <div class="command-list" ref="listRef">
        <div
          v-for="(cmd, idx) in filtered"
          :key="cmd.id"
          class="command-item"
          :class="{ active: idx === activeIndex }"
          @mouseenter="activeIndex = idx"
          @click="executeByIndex(idx)"
        >
          <span class="command-title">{{ cmd.title }}</span>
          <span v-if="cmd.subtitle" class="command-subtitle">{{ cmd.subtitle }}</span>
          <span v-if="cmd.shortcut" class="command-shortcut">{{ cmd.shortcut }}</span>
        </div>
        <div v-if="!filtered.length" class="command-empty">未找到命令</div>
      </div>

      <div class="command-footer">
        <span>↑↓ 选择 · Enter 执行 · Esc 关闭</span>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'

const props = defineProps({
  commands: { type: Array, default: () => [] },
  modelValue: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'execute'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const query = ref('')
const activeIndex = ref(0)
const inputRef = ref(null)
const listRef = ref(null)

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return props.commands
  return props.commands.filter(c =>
    c.title.toLowerCase().includes(q) ||
    (c.subtitle && c.subtitle.toLowerCase().includes(q)) ||
    (c.keywords && c.keywords.some(k => k.toLowerCase().includes(q)))
  )
})

watch(visible, (v) => {
  if (v) {
    query.value = ''
    activeIndex.value = 0
  }
})

watch(filtered, () => {
  activeIndex.value = 0
})

function focusInput() {
  nextTick(() => inputRef.value?.focus())
}

function move(delta) {
  const len = filtered.value.length
  if (!len) return
  activeIndex.value = (activeIndex.value + delta + len) % len
  scrollActiveIntoView()
}

function scrollActiveIntoView() {
  nextTick(() => {
    const list = listRef.value
    if (!list) return
    const item = list.children[activeIndex.value]
    if (item) item.scrollIntoView({ block: 'nearest' })
  })
}

function execute() {
  const cmd = filtered.value[activeIndex.value]
  if (cmd) {
    emit('execute', cmd)
    visible.value = false
  }
}

function executeByIndex(idx) {
  activeIndex.value = idx
  execute()
}
</script>

<style scoped>
.search-icon {
  margin-left: 8px;
  font-size: 12px;
  color: var(--ra-text-secondary);
  background: var(--ra-bg);
  padding: 2px 6px;
  border-radius: 4px;
  border: 1px solid var(--ra-border);
}
.command-list {
  max-height: 320px;
  overflow-y: auto;
  margin-top: 8px;
}
.command-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  color: var(--ra-text);
}
.command-item:hover,
.command-item.active {
  background: var(--ra-bg);
}
.command-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.command-subtitle {
  font-size: 11px;
  color: var(--ra-text-secondary);
}
.command-shortcut {
  font-size: 11px;
  color: var(--ra-text-secondary);
  background: var(--ra-bg);
  padding: 2px 6px;
  border-radius: 4px;
  border: 1px solid var(--ra-border);
  white-space: nowrap;
}
.command-empty {
  padding: 24px;
  text-align: center;
  color: var(--ra-text-secondary);
  font-size: 13px;
}
.command-footer {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--ra-border);
  font-size: 11px;
  color: var(--ra-text-secondary);
  text-align: right;
}
</style>
