<template>
  <details v-if="sources.length" class="chat-claim-list evidence-source-list">
    <summary>查看依据（{{ sources.length }}）</summary>
    <ol>
      <li v-for="source in sources" :key="source.key">
        <ResearchMarkdown
          v-if="source.kind === '公式' && source.textFormat === 'LATEX' && source.textReliable"
          class="evidence-source__formula"
          :content="`$$${source.fullText}$$`"
        />
        <span v-else class="evidence-source__excerpt">{{ source.excerpt }}</span>
        <span v-if="source.fullTextAvailable === false" class="evidence-source__legacy">
          该历史记录只保存了证据摘要；请在新对话中重新检索以获得完整依据。
        </span>
        <details v-if="source.excerptTruncated" class="evidence-source__full">
          <summary>完整依据（可收起）</summary>
          <ResearchMarkdown
            v-if="source.kind === '公式' && source.textFormat === 'LATEX' && source.textReliable"
            :content="`$$${source.fullText}$$`"
          />
          <pre v-else>{{ source.fullText }}</pre>
        </details>
        <button
          v-if="canJump(source)"
          type="button"
          class="evidence-source__jump"
          :title="source.title"
          @click="$emit('jump', source.target)"
        >{{ source.kind }} · p.{{ source.page }}</button>
      </li>
    </ol>
  </details>
</template>

<script setup>
import ResearchMarkdown from '@/components/ResearchMarkdown.vue'
import { evidenceLocators, validBoxes } from '@/utils/evidenceViewModel.js'

defineEmits(['jump'])
const props = defineProps({
  sources: { type: Array, default: () => [] },
  // 实时消息可以退化为“跳到页码”；档案回放必须有真实 locator 才显示跳转，避免伪定位。
  allowPageOnly: { type: Boolean, default: true },
})

function canJump(source) {
  const page = Number(source?.page)
  const locators = evidenceLocators(source?.target || {})
    .filter(locator => Number(locator?.pageNumber || locator?.page) === page)
  const hasUsableLocator = locators.some(locator => (
    validBoxes(locator?.targetBoxes || locator?.rects || locator?.focusBoxes).length > 0
      || String(locator?.targetText || '').trim().length > 0
  ))
  return Number.isInteger(page) && page > 0
    && (props.allowPageOnly || hasUsableLocator)
}
</script>

<style scoped>
.chat-claim-list { margin: 9px 0 0; font-size: 10px; }
.chat-claim-list summary { color: var(--ra-link); cursor: pointer; }
.chat-claim-list ol { display: flex; flex-direction: column; gap: 7px; margin: 7px 0 0; padding-left: 17px; }
.chat-claim-list li { font-size: 10px; line-height: 1.45; }
.evidence-source__excerpt { display: block; color: var(--ra-text-secondary); }
.evidence-source__formula { display: block; color: var(--ra-text-secondary); }
.evidence-source__legacy { display: block; margin-top: 3px; color: var(--ra-text-tertiary); font-size: 10px; }
.evidence-source__full { margin-top: 4px; color: var(--ra-text-secondary); }
.evidence-source__full summary { cursor: pointer; color: var(--ra-link); font-size: 10px; }
.evidence-source__full pre { max-height: 360px; overflow: auto; margin: 4px 0 0; white-space: pre-wrap; word-break: break-word; }
.evidence-source__jump { margin-top: 4px; padding: 2px 6px; border: 1px solid color-mix(in srgb, var(--ra-link) 45%, var(--ra-border)); border-radius: 999px; color: var(--ra-link); background: transparent; font-size: 10px; cursor: pointer; }
</style>
