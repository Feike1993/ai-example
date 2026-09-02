import { Badge, Button, Group, SegmentedControl, Stack, Text, Textarea } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useEffect, useState } from 'react'
import {
  describeError,
  getJson,
  postJson,
  putJson,
  API_BASE,
  ApiError,
  type McpChatResponse,
  type McpToolsResponse,
} from '../api'
import { MarkdownBody } from '../components/MarkdownBody'
import { RawJsonAccordion } from '../components/RawJsonAccordion'
import { RequestMeta } from '../components/RequestMeta'
import { ResultBody } from '../components/ResultBody'
import { SampleFrame } from '../components/SampleFrame'
import { Workbench } from '../components/Workbench'
import { mcpGuide } from '../guides'

type McpMode = 'remote' | 'inprocess'

/**
 * MCP 样例面板。
 *
 * SegmentedControl ↔ `PUT /mcp/mode`：切换进程内全局模式（不重启）。
 * remote 列工具失败时仍更新本地 mode、清空工具列表，主应用其它样例不受影响。
 */
export function McpPanel({ provider }: { provider: string }) {
  const [prompt, setPrompt] = useState('北京天气怎么样？再算 3+5')
  const [loading, setLoading] = useState(false)
  const [modeSwitching, setModeSwitching] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<McpChatResponse | null>(null)
  const [toolNames, setToolNames] = useState<string[]>([])
  const [elapsedMs, setElapsedMs] = useState<number | null>(null)
  const [mode, setMode] = useState<McpMode>('remote')

  useEffect(() => {
    void getJson<McpToolsResponse>(`${API_BASE}/mcp/tools`)
      .then((data) => {
        setToolNames(data.toolNames ?? [])
        if (data.mode === 'remote' || data.mode === 'inprocess') {
          setMode(data.mode)
        }
        if (data.error) {
          notifications.show({ color: 'yellow', title: 'MCP 工具未就绪', message: data.error })
        }
      })
      .catch(() => {
        setToolNames([])
      })
  }, [])

  const switchMode = async (next: McpMode) => {
    if (next === mode || modeSwitching) {
      return
    }
    setModeSwitching(true)
    try {
      const data = await putJson<McpToolsResponse>(`${API_BASE}/mcp/mode`, { mode: next })
      setMode((data.mode === 'inprocess' ? 'inprocess' : 'remote') as McpMode)
      setToolNames(data.toolNames ?? [])
    } catch (err) {
      // 后端已先写入 mode；503 body 常仍带 mode/error
      let switched: McpMode = next
      if (err instanceof ApiError) {
        try {
          const body = JSON.parse(err.body) as McpToolsResponse
          if (body.mode === 'remote' || body.mode === 'inprocess') {
            switched = body.mode
          }
        } catch {
          /* 非 JSON 时仍用目标 next */
        }
      }
      setMode(switched)
      setToolNames([])
      notifications.show({ color: 'red', title: '模式已切换，工具不可用', message: describeError(err) })
    } finally {
      setModeSwitching(false)
    }
  }

  const run = async () => {
    setError(null)
    setResult(null)
    setElapsedMs(null)
    setLoading(true)
    const started = performance.now()
    try {
      const data = await postJson<McpChatResponse>(`${API_BASE}/mcp/chat`, { prompt, provider })
      setElapsedMs(Math.round(performance.now() - started))
      setResult(data)
      if (data.toolNames?.length) {
        setToolNames(data.toolNames)
      }
      if (data.mode === 'remote' || data.mode === 'inprocess') {
        setMode(data.mode)
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
        hint={
          mode === 'remote'
            ? 'mode=remote：请先启动 mcp-server（8081），两端 MCP_BEARER_TOKEN 须一致（默认 dev-mcp-token）。401 时优先查密钥。可切 inprocess 免旁进程。'
            : 'mode=inprocess：同进程工具直挂 ChatClient，无需 mcp-server / Bearer。'
        }
        form={
          <Stack gap="md">
            <div>
              <Text size="sm" mb={6}>
                模式
              </Text>
              <SegmentedControl
                fullWidth
                value={mode}
                onChange={(value) => void switchMode(value as McpMode)}
                data={[
                  { label: 'remote', value: 'remote' },
                  { label: 'inprocess', value: 'inprocess' },
                ]}
                disabled={modeSwitching || loading}
              />
            </div>
            <div>
              <Text size="sm" mb={6}>
                已发现工具
              </Text>
              <Group gap={6}>
                {toolNames.length === 0 && (
                  <Text size="sm" c="dimmed">
                    {mode === 'remote' ? '需 mcp-server:8081，或切到 inprocess' : '启动后端后自动加载'}
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
            <Button loading={loading} disabled={!prompt.trim() || loading || modeSwitching} onClick={() => void run()}>
              调用
            </Button>
          </Stack>
        }
        result={
          <ResultBody error={error} emptyHint="调用后展示回复与 MCP 工具名列表。">
            {result && (
              <Stack gap="sm">
                <RequestMeta elapsedMs={elapsedMs} usage={result.usage} />
                <Badge variant="light" color={(result.mode || mode) === 'remote' ? 'teal' : 'gray'}>
                  mode {result.mode || mode}
                </Badge>
                <MarkdownBody>{result.content}</MarkdownBody>
                <RawJsonAccordion value={result} />
              </Stack>
            )}
          </ResultBody>
        }
      />
    </SampleFrame>
  )
}
