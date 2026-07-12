import { ref, onUnmounted } from 'vue'

export function isTerminal(status) {
  return ['COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED', 'DEAD_LETTER'].includes(status)
}

/** Own the task list timer so routed views cannot leak polling callbacks. */
export function useTaskPolling({ interval = 3000, isActive = task => !isTerminal(task?.status) } = {}) {
  const polling = ref(false)
  let timer = null
  let stopped = false

  function stop() {
    stopped = true
    polling.value = false
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
  }

  function schedule(load) {
    if (stopped) return
    if (timer) clearTimeout(timer)
    polling.value = true
    timer = setTimeout(async () => {
      timer = null
      if (!stopped) await load()
    }, interval)
  }

  function update(tasks, load) {
    const active = (tasks || []).some(isActive)
    polling.value = active
    if (active) schedule(load)
    else if (timer) {
      clearTimeout(timer)
      timer = null
    }
  }

  onUnmounted(stop)
  return { polling, update, schedule, stop }
}
