import MarkdownIt from 'markdown-it'
import { katex } from '@mdit/plugin-katex'

const markdown = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: false,
  typographer: false,
})

markdown.use(katex, {
  delimiters: 'all',
  mathFence: true,
  throwOnError: false,
  strict: 'ignore',
  trust: false,
  maxExpand: 1000,
  output: 'htmlAndMathml',
})

const defaultLinkOpen = markdown.renderer.rules.link_open
  || ((tokens, index, options, env, renderer) => renderer.renderToken(tokens, index, options))
markdown.renderer.rules.link_open = (tokens, index, options, env, renderer) => {
  tokens[index].attrSet('target', '_blank')
  tokens[index].attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen(tokens, index, options, env, renderer)
}

export function renderResearchMarkdown(value) {
  const source = String(value || '')
  if (!source) return ''
  try {
    return markdown.render(source)
  } catch {
    return `<p>${escapeHtml(source).replace(/\r?\n/g, '<br>')}</p>`
  }
}

function escapeHtml(value) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;')
}
