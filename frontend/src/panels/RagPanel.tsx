import { Badge, Button, Group, SegmentedControl, Stack, Text, Textarea } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useEffect, useRef, useState } from 'react'
import {
  describeError,
  postJson,
  streamRag,
  API_BASE,
  type RagIngestResponse,
  type RagQueryResponse,
  type RagSource,
} from '../api'
import { RawJsonAccordion } from '../components/RawJsonAccordion'
import { RequestMeta } from '../components/RequestMeta'
import { ResultBody } from '../components/ResultBody'
import { SampleFrame } from '../components/SampleFrame'
import { Workbench } from '../components/Workbench'
import { ragGuide } from '../guides'

type Mode = 'sync' | 'sse'

/**
 * RAG 样例：一键 ingest、同步提问、可选 SSE，展示 sources。
 */
export function RagPanel({ provider }: { provider: string }) {
  const [question, setQuestion] = useState('本项目第一期学了什么？RAG 是什么？')
  const [mode, setMode] = useState<Mode>('sync')
  const [loading, setLoading] = useState(false)
  const [streaming, setStreaming] = useState(false)
  const [ingesting, setIngesting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [ingestInfo, setIngestInfo] = useState<RagIngestResponse | null>(null)
  const [result, setResult] = useState<RagQueryResponse | null>(null)
  const [streamText, setStreamText] = useState('')
  const [sources, setSources] = useState<RagSource[]>([])
  const [retrievalEmpty, setRetrievalEmpty] = useState(false)
  const [elapsedMs, setElapsedMs] = useState<number | null>(null)
  const [ttftMs, setTtftMs] = useState<number | null>(null)
  const stopRef = useRef<(() => void) | null>(null)

  useEffect(() => {
    return () => {
      stopRef.current?.()
    }
  }, [])

  const runIngest = async () => {
    setError(null)
    setIngesting(true)
    try {
      const data = await postJson<RagIngestResponse>(`${API_BASE}/rag/ingest`, {})
      setIngestInfo(data)
      notifications.show({
        color: 'teal',
        title: '索引完成',
        message: `${data.chunkCount} chunks · ${data.sources?.join(', ') ?? ''}`,
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
    setStreamText('')
    setSources([])
    setRetrievalEmpty(false)
    setElapsedMs(null)
    setTtftMs(null)
    setLoading(true)
    const started = performance.now()
    try {
      const data = await postJson<RagQueryResponse>(`${API_BASE}/rag/query`, {
        question,
        provider,
      })
      setElapsedMs(Math.round(performance.now() - started))
      setResult(data)
      setSources(data.sources ?? [])
      setRetrievalEmpty(data.retrievalEmpty === true)
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: 'RAG 失败', message })
    } finally {
      setLoading(false)
    }
  }

  const runSse = () => {
    setError(null)
    setResult(null)
    setStreamText('')
    setSources([])
    setRetrievalEmpty(false)
    setElapsedMs(null)
    setTtftMs(null)
    setLoading(true)
    setStreaming(true)
    const started = performance.now()
    let first = true
    let acc = ''
    let latestSources: RagSource[] = []
    let latestEmpty = false

    stopRef.current = streamRag(
      question,
      provider,
      (nextSources, empty) => {
        latestSources = nextSources
        latestEmpty = empty
        setSources(nextSources)
        setRetrievalEmpty(empty)
      },
      (chunk) => {
        if (first) {
          first = false
          setTtftMs(Math.round(performance.now() - started))
        }
        acc += chunk
        setStreamText(acc)
      },
      () => {
        stopRef.current = null
        setStreaming(false)
        setLoading(false)
        setResult({
          answer: acc,
          sources: latestSources,
          retrievalEmpty: latestEmpty,
          usage: null,
        })
      },
      (err) => {
        stopRef.current = null
        setStreaming(false)
        setLoading(false)
        setError(err.message)
        notifications.show({ color: 'red', title: 'RAG SSE 失败', message: err.message })
      },
    )
  }

  const onSubmit = () => {
    if (loading && streaming) {
      stopRef.current?.()
      stopRef.current = null
      setStreaming(false)
      setLoading(false)
      return
    }
    if (mode === 'sse') {
      runSse()
      return
    }
    void runSync()
  }

  return (
    <SampleFrame guide={ragGuide}>
      <Workbench
        title="RAG"
        hint="先 ingest，再提问。需要 Docker pgvector + DashScope Embedding Key。"
        streaming={streaming}
        form={
          <Stack gap="md">
            <Button variant="light" loading={ingesting} onClick={() => void runIngest()}>
              重建索引（ingest）
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
                { value: 'sync', label: '同步 POST' },
                { value: 'sse', label: 'SSE 流式' },
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
              loading={loading && !streaming}
              disabled={!question.trim() || (loading && !streaming)}
              onClick={onSubmit}
            >
              {streaming ? '停止' : '提问'}
            </Button>
          </Stack>
        }
        result={
          <ResultBody error={error} emptyHint="ingest 后提问，答案与 sources 会出现在这里。">
            {(streaming || streamText || result || sources.length > 0 || retrievalEmpty) && (
              <Stack gap="sm">
                {mode === 'sync' ? (
                  <RequestMeta elapsedMs={elapsedMs} usage={result?.usage} />
                ) : (
                  <RequestMeta elapsedMs={ttftMs} elapsedLabel="TTFT" />
                )}
                {retrievalEmpty && (
                  <Badge color="orange" variant="light">
                    retrievalEmpty：无足够命中，已拒答
                  </Badge>
                )}
                {sources.length > 0 && (
                  <div>
                    <Text size="sm" mb={6}>
                      sources
                    </Text>
                    <Stack gap={6}>
                      {sources.map((item) => (
                        <div key={item.id}>
                          <Group gap={6} mb={4}>
                            <Badge variant="light">{item.source || 'unknown'}</Badge>
                          </Group>
                          <Text size="sm" c="dimmed">
                            {item.excerpt}
                          </Text>
                        </div>
                      ))}
                    </Stack>
                  </div>
                )}
                <pre className="stream-text">
                  {streaming ? streamText : (result?.answer ?? streamText)}
                  {streaming && <span className="sse-caret" />}
                </pre>
                {!streaming && result && <RawJsonAccordion value={result} />}
              </Stack>
            )}
          </ResultBody>
        }
      />
    </SampleFrame>
  )
}
