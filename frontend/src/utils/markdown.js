/**
 * 简易 Markdown → HTML 转换器。
 *
 * 仅支持本项目中流式报告使用的少量语法：
 * - `# 标题` / `## 标题` / `### 标题`
 * - 段落通过双换行分割为 `<p>`
 *
 * 不依赖外部库，适合静态展示简单报告。
 * @param {string} text
 * @returns {string}
 */
export function simpleMarkdownToHtml(text) {
  if (!text) return ''
  return text
    .replace(/\n\n/g, '</p><p>')
    .replace(/### (.+)/g, '<h4>$1</h4>')
    .replace(/## (.+)/g, '<h3>$1</h3>')
    .replace(/# (.+)/g, '<h2>$1</h2>')
}
