import { Badge, Button, Group, Stack, Text, Textarea } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useEffect, useState } from 'react'
import {
  describeError,
  getJson,
  postJson,
  API_BASE,
  type McpChatResponse,
  type McpToolsResponse,
} from '../api'
import { RawJsonAccordion } from '../components/RawJsonAccordion'
import { ResultBody } from '../components/ResultBody'
import { SampleFrame } from '../components/SampleFrame'
import { Workbench } from '../components/Workbench'
import { mcpGuide } from '../guides'

/**
 * MCP 样例：展示已注册工具，并走 MCP 工具集问答。
 */
export function McpPanel({ provider }: { provider: string }) {
  const [prompt, setPrompt] = useState('北京天气怎么样？再算 3+5')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<McpChatResponse | null>(null)
  const [toolNames, setToolNames] = useState<string[]>([])

  useEffect(() => {
    void getJson<McpToolsResponse>(`${API_BASE}/mcp/tools`)
      .then((data) => setToolNames(data.toolNames ?? []))
      .catch(() => setToolNames([]))
  }, [])

  const run = async () => {
    setError(null)
    setResult(null)
    setLoading(true)
    try {
      const data = await postJson<McpChatResponse>(`${API_BASE}/mcp/chat`, { prompt, provider })
      setResult(data)
      if (data.toolNames?.length) {
        setToolNames(data.toolNames)
      }
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: 'MCP 失败', message })
    } finally {
      setLoading(false)
    }
  }

  return (
    <SampleFrame guide={mcpGuide}>
      <Workbench
        title="MCP"
        hint="POST /ai-example/mcp/chat；工具经 MCP Server 注册（Streamable HTTP /mcp）。"
        form={
          <Stack gap="md">
            <div>
              <Text size="sm" mb={6}>
                已发现工具
              </Text>
              <Group gap={6}>
                {toolNames.length === 0 && (
                  <Text size="sm" c="dimmed">
                    启动后端后自动加载
                  </Text>
                )}
                {toolNames.map((name) => (
                  <Badge key={name} variant="light">
                    {name}
                  </Badge>
                ))}
              </Group>
            </div>
            <Textarea
              label="prompt"
              minRows={8}
              autosize
              value={prompt}
              onChange={(event) => setPrompt(event.currentTarget.value)}
            />
            <Button loading={loading} disabled={!prompt.trim() || loading} onClick={() => void run()}>
              调用
            </Button>
          </Stack>
        }
        result={
          <ResultBody error={error} emptyHint="调用后展示回复与 MCP 工具名列表。">
            {result && (
              <Stack gap="sm">
                <pre className="stream-text">{result.content}</pre>
                <RawJsonAccordion value={result} />
              </Stack>
            )}
          </ResultBody>
        }
      />
    </SampleFrame>
  )
}
