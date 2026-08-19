import { CodeHighlight } from '@mantine/code-highlight'

/**
 * 以当前主题高亮展示 JSON。
 */
export function JsonBlock({ value }: { value: unknown }) {
  return (
    <CodeHighlight
      code={JSON.stringify(value, null, 2)}
      language="json"
      radius="md"
      copyLabel="复制"
      copiedLabel="已复制"
    />
  )
}
