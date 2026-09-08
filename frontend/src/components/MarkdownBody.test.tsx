import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MarkdownBody } from './MarkdownBody'

describe('MarkdownBody', () => {
  it('渲染 GFM 表格', () => {
    render(
      <MarkdownBody>{'| 名称 | 值 |\n| --- | --- |\n| topK | 4 |'}</MarkdownBody>,
    )

    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '名称' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'topK' })).toBeInTheDocument()
  })

  it('渲染代码块与行内格式', () => {
    const { container } = render(
      <MarkdownBody>{'**重点**与 `inline`\n\n```java\nint a = 1;\n```'}</MarkdownBody>,
    )

    expect(screen.getByText('重点').tagName).toBe('STRONG')
    expect(container.querySelector('pre code')?.textContent).toContain('int a = 1;')
  })

  it('不渲染原始 HTML，避免注入', () => {
    const { container } = render(
      <MarkdownBody>{'<img src=x onerror="alert(1)" /> 普通文本'}</MarkdownBody>,
    )

    expect(container.querySelector('img')).toBeNull()
    expect(container.textContent).toContain('普通文本')
  })

  it('流式时显示光标，非流式空内容不渲染任何东西', () => {
    const { container: streamingBox } = render(<MarkdownBody streaming>{''}</MarkdownBody>)
    expect(streamingBox.querySelector('.sse-caret')).not.toBeNull()

    const { container: emptyBox } = render(<MarkdownBody>{''}</MarkdownBody>)
    expect(emptyBox.innerHTML).toBe('')
  })
})
