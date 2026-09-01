import { Badge, Button, Group, SegmentedControl, Stack, Text, Textarea } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useState } from 'react'
import {
  describeError,
  postJson,
  API_BASE,
  type RagChunkingCompareResponse,
  type RagIngestResponse,
  type RagQueryResponse,
  type RagSource,
} from '../api'
import { RawJsonAccordion } from '../components/RawJsonAccordion'
import { RequestMeta } from '../components/RequestMeta'
import { ResultBody } from '../components/ResultBody'
import { SampleFrame } from '../components/SampleFrame'
import { Workbench } from '../components/Workbench'
import { parentChildGuide } from '../guides'

type Mode = 'sync' | 'compare'

/**
 * 进阶父子文档：子块检索 + 父块展开预览。
 */
export function ParentChildPanel({ provider }: { provider: string }) {
  const [question, setQuestion] = useState('MCP 是什么？')
  const [mode, setMode] = useState<Mode>('sync')
  const [loading, setLoading] = useState(false)
  const [ingesting, setIngesting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [ingestInfo, setIngestInfo] = useState<RagIngestResponse | null>(null)
  const [result, setResult] = useState<RagQueryResponse | null>(null)
  const [compareResult, setCompareResult] = useState<RagChunkingCompareResponse | null>(null)
  const [elapsedMs, setElapsedMs] = useState<number | null>(null)

  const runIngest = async () => {
    setError(null)
    setIngesting(true)
    try {
      const data = await postJson<RagIngestResponse>(`${API_BASE}/rag/ingest`, {
        strategy: 'parent_child',
      })
      setIngestInfo(data)
      notifications.show({
        color: 'teal',
        title: '父子索引完成',
        message: `${data.chunkCount} child chunks`,
      })
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: 'ingest 失败', message })
    } finally {
      setIngesting(false)
    }
  }

  const runSync = async () => {
    setError(null)
    setResult(null)
    setCompareResult(null)
    setElapsedMs(null)
    setLoading(true)
    const started = performance.now()
    try {
      const data = await postJson<RagQueryResponse>(`${API_BASE}/rag/query`, {
        question,
        provider,
        chunkingStrategy: 'parent_child',
        retrievalMode: 'vector',
      })
      setElapsedMs(Math.round(performance.now() - started))
      setResult(data)
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: '查询失败', message })
    } finally {
      setLoading(false)
    }
  }

  const runCompare = async () => {
    setError(null)
    setResult(null)
    setCompareResult(null)
    setElapsedMs(null)
    setLoading(true)
    const started = performance.now()
    try {
      await postJson(`${API_BASE}/rag/ingest`, { strategy: 'all' })
      const data = await postJson<RagChunkingCompareResponse>(
        `${API_BASE}/rag/query/compare-chunking`,
        { question, provider },
      )
      setElapsedMs(Math.round(performance.now() - started))
      setCompareResult(data)
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: 'compare 失败', message })
    } finally {
      setLoading(false)
    }
  }

  const renderSources = (items: RagSource[]) => (
    <Stack gap={6}>
      {items.map((item) => (
        <div key={item.id}>
          <Group gap={6} mb={4}>
            <Badge variant="light">{item.source || 'unknown'}</Badge>
            {item.chunkRole && (
              <Badge size="xs" color="teal">
                {item.chunkRole}
              </Badge>
            )}
          </Group>
          <Text size="sm" c="dimmed">
            子块：{item.excerpt}
          </Text>
          {item.parentExcerpt && (
            <Text size="sm" mt={4}>
              父预览：{item.parentExcerpt}
            </Text>
          )}
        </div>
      ))}
    </Stack>
  )

  return (
    <SampleFrame guide={parentChildGuide}>
      <Workbench
        title="父子文档"
        hint="子块检索、父块拼上下文。compare 会先 ingest all 再对照三路。"
        form={
          <Stack gap="md">
            <Button variant="light" loading={ingesting} onClick={() => void runIngest()}>
              重建父子索引
            </Button>
            {ingestInfo && (
              <Text size="sm" c="dimmed">
                最近 ingest：{ingestInfo.chunkCount} chunks
              </Text>
            )}
            <SegmentedControl
              fullWidth
              value={mode}
              onChange={(value) => setMode(value as Mode)}
              data={[
                { value: 'sync', label: 'parent_child 单路' },
                { value: 'compare', label: '三路对照' },
              ]}
            />
            <Textarea
              label="question"
              minRows={8}
              autosize
              value={question}
              onChange={(event) => setQuestion(event.currentTarget.value)}
            />
            <Button
              loading={loading}
              disabled={!question.trim() || loading}
              onClick={() => (mode === 'compare' ? void runCompare() : void runSync())}
            >
              {mode === 'compare' ? '对照三套命中' : '提问'}
            </Button>
          </Stack>
        }
        result={
          <ResultBody error={error} emptyHint="ingest 后提问，查看子块与父预览。">
            {compareResult && (
              <Stack gap="lg">
                <RequestMeta elapsedMs={elapsedMs} />
                {(['token', 'semantic', 'parentChild'] as const).map((key) => {
                  const side = compareResult[key]
                  if (!side) {
                    return null
                  }
                  return (
                    <div key={key}>
                      <Text fw={600} mb={6}>
                        {key} · {side.corpus}
                      </Text>
                      {side.sources?.length > 0 && renderSources(side.sources)}
                    </div>
                  )
                })}
                <RawJsonAccordion value={compareResult} />
              </Stack>
            )}
            {!compareResult && result && (
              <Stack gap="sm">
                <RequestMeta elapsedMs={elapsedMs} usage={result.usage} />
                <Badge variant="outline">chunking: {result.chunkingStrategy}</Badge>
                {result.sources?.length > 0 && renderSources(result.sources)}
                <pre className="stream-text">{result.answer}</pre>
                <RawJsonAccordion value={result} />
              </Stack>
            )}
          </ResultBody>
        }
      />
    </SampleFrame>
  )
}
