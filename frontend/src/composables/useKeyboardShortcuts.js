import { onMounted, onUnmounted } from 'vue'

/**
 * 全局快捷键 composable。
 * <p>
 * 在 input / textarea / contenteditable 内聚焦时不触发路由类快捷键，
 * 避免干扰正常输入。
 */
export function useKeyboardShortcuts(shortcuts) {
  function isTypingElement() {
    const el = document.activeElement
    if (!el) return false
    const tag = el.tagName?.toLowerCase()
    return tag === 'input' || tag === 'textarea' || el.isContentEditable
  }

  function handler(e) {
    const key = e.key
    const ctrl = e.ctrlKey || e.metaKey
    const shift = e.shiftKey
    const alt = e.altKey

    for (const s of shortcuts) {
      if (s.key && s.key !== key) continue
      if (s.ctrl !== undefined && s.ctrl !== ctrl) continue
      if (s.shift !== undefined && s.shift !== shift) continue
      if (s.alt !== undefined && s.alt !== alt) continue
      if (s.whenTyping === false && isTypingElement()) continue

      e.preventDefault()
      s.action(e)
      return
    }
  }

  onMounted(() => window.addEventListener('keydown', handler))
  onUnmounted(() => window.removeEventListener('keydown', handler))
}
