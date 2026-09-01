import ReactMarkdown from 'react-markdown'

/**
 * 将 Markdown 字符串渲染为可读正文（加粗、列表、标题等）。
 * 不启用 raw HTML，避免 XSS。
 */
export function MarkdownBody({ children }: { children: string }) {
  if (!children) {
    return null
  }
  return (
    <div className="markdown-body">
      <ReactMarkdown>{children}</ReactMarkdown>
    </div>
  )
}
