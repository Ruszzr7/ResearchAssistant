import { reactive, watch } from 'vue'

/**
 * 全局主题状态。
 * <p>
 * 管理亮/暗色模式，持久化到 localStorage，并在 html 元素上挂 dark 类。
 */
const STORAGE_KEY = 'ra-theme'

function getInitialDark() {
  const saved = localStorage.getItem(STORAGE_KEY)
  if (saved !== null) {
    return saved === 'dark'
  }
  // 默认跟随系统
  return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
}

const theme = reactive({
  dark: getInitialDark(),
})

function apply() {
  const html = document.documentElement
  if (theme.dark) {
    html.classList.add('dark')
  } else {
    html.classList.remove('dark')
  }
  localStorage.setItem(STORAGE_KEY, theme.dark ? 'dark' : 'light')
}

export function useTheme() {
  return {
    dark: theme,
    toggle: () => {
      theme.dark = !theme.dark
      apply()
    },
    setDark: (value) => {
      theme.dark = Boolean(value)
      apply()
    },
  }
}

apply()

watch(() => theme.dark, apply)
