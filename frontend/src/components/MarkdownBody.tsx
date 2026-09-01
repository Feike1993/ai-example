import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

type MarkdownBodyProps = {
  children: string
  /** 流式输出时在末尾显示光标 */
  streaming?: boolean
}

/**
 * 将 Markdown 字符串渲染为可读正文（GFM：加粗、列表、表格、删除线等）。
 * 不启用 raw HTML，避免 XSS。
 */
export function MarkdownBody({ children, streaming = false }: MarkdownBodyProps) {
  if (!children && !streaming) {
    return null
  }
  return (
    <div className="markdown-body">
      {children ? <ReactMarkdown remarkPlugins={[remarkGfm]}>{children}</ReactMarkdown> : null}
      {streaming ? <span className="sse-caret" /> : null}
    </div>
  )
}
