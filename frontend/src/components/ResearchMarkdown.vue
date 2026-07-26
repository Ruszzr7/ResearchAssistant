<template>
  <div class="research-markdown" v-html="rendered" />
</template>

<script setup>
import { computed } from 'vue'
import { renderResearchMarkdown } from '@/utils/researchMarkdown.js'

const props = defineProps({
  content: { type: String, default: '' },
})

const rendered = computed(() => renderResearchMarkdown(props.content))
</script>

<style scoped>
.research-markdown { overflow-wrap: anywhere; font-size: inherit; line-height: inherit; }
.research-markdown :deep(> :first-child) { margin-top: 0; }
.research-markdown :deep(> :last-child) { margin-bottom: 0; }
.research-markdown :deep(p) { margin: 5px 0; }
.research-markdown :deep(h1),
.research-markdown :deep(h2),
.research-markdown :deep(h3),
.research-markdown :deep(h4) { margin: 10px 0 5px; line-height: 1.35; }
.research-markdown :deep(ul),
.research-markdown :deep(ol) { margin: 6px 0; padding-left: 20px; }
.research-markdown :deep(blockquote) {
  margin: 7px 0;
  padding-left: 9px;
  border-left: 3px solid var(--ra-border);
  color: var(--ra-text-secondary);
}
.research-markdown :deep(code) {
  padding: 1px 3px;
  border-radius: 3px;
  background: var(--ra-hover-bg);
}
.research-markdown :deep(pre) {
  overflow-x: auto;
  padding: 9px;
  border-radius: 6px;
  background: var(--ra-hover-bg);
  white-space: pre;
}
.research-markdown :deep(pre code) { padding: 0; background: transparent; }
.research-markdown :deep(table) {
  display: block;
  max-width: 100%;
  overflow-x: auto;
  margin: 8px 0;
  border-collapse: collapse;
  white-space: nowrap;
}
.research-markdown :deep(th),
.research-markdown :deep(td) {
  padding: 5px 7px;
  border: 1px solid var(--ra-border);
  text-align: left;
}
.research-markdown :deep(th) { background: var(--ra-hover-bg); }
.research-markdown :deep(.katex-display) {
  max-width: 100%;
  overflow-x: auto;
  overflow-y: hidden;
  margin: 8px 0;
  padding: 2px 0;
}
.research-markdown :deep(a) { color: var(--ra-link); }
</style>
