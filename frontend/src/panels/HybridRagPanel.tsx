import { Badge, Button, Group, SegmentedControl, Stack, Switch, Text, Textarea } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useState } from 'react'
import {
  describeError,
  postJson,
  API_BASE,
  type RagCompareResponse,
  type RagIngestResponse,
  type RagQueryResponse,
  type RagSource,
} from '../api'
import { RawJsonAccordion } from '../components/RawJsonAccordion'
import { RequestMeta } from '../components/RequestMeta'
import { ResultBody } from '../components/ResultBody'
import { SampleFrame } from '../components/SampleFrame'
import { Workbench } from '../components/Workbench'
import { hybridRagGuide } from '../guides'

type Mode = 'sync' | 'compare'
type RetrievalMode = 'vector' | 'hybrid'

/**
 * 进阶 Hybrid RAG：向量 + PG 全文 + RRF；compare 并排对照。
 */
export function HybridRagPanel({ provider }: { provider: string }) {
  const [question, setQuestion] = useState('MCP 和 Function Calling 区别？RAG 是什么？')
  const [mode, setMode] = useState<Mode>('compare')
  const [retrievalMode, setRetrievalMode] = useState<RetrievalMode>('hybrid')
  const [rewriteQuery, setRewriteQuery] = useState(false)
  const [loading, setLoading] = useState(false)
  const [ingesting, setIngesting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [ingestInfo, setIngestInfo] = useState<RagIngestResponse | null>(null)
  const [result, setResult] = useState<RagQueryResponse | null>(null)
  const [compareResult, setCompareResult] = useState<RagCompareResponse | null>(null)
  const [elapsedMs, setElapsedMs] = useState<number | null>(null)

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

  const buildBody = () => ({
    question,
    provider,
    retrievalMode,
    rewriteQuery,
  })

  const runSync = async () => {
    setError(null)
    setResult(null)
    setCompareResult(null)
    setElapsedMs(null)
    setLoading(true)
    const started = performance.now()
    try {
      const data = await postJson<RagQueryResponse>(`${API_BASE}/rag/query`, buildBody())
      setElapsedMs(Math.round(performance.now() - started))
      setResult(data)
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: 'Hybrid RAG 失败', message })
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
      const data = await postJson<RagCompareResponse>(`${API_BASE}/rag/query/compare`, buildBody())
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

  const onSubmit = () => {
    if (mode === 'compare') {
      void runCompare()
      return
    }
    void runSync()
  }

  const renderSources = (items: RagSource[]) => (
    <Stack gap={6}>
      {items.map((item) => (
        <div key={item.id}>
          <Group gap={6} mb={4}>
            <Badge variant="light">{item.source || 'unknown'}</Badge>
            {item.vectorRank != null && <Badge size="xs">vec #{item.vectorRank}</Badge>}
            {item.keywordRank != null && (
              <Badge size="xs" color="grape">
                kw #{item.keywordRank}
              </Badge>
            )}
            {item.rrfScore != null && (
              <Badge size="xs" color="teal">
                rrf {item.rrfScore.toFixed(4)}
              </Badge>
            )}
          </Group>
          <Text size="sm" c="dimmed">
            {item.excerpt}
          </Text>
        </div>
      ))}
    </Stack>
  )

  return (
    <SampleFrame guide={hybridRagGuide}>
      <Workbench
        title="Hybrid RAG"
        hint="需先 ingest。Hybrid = 向量 + PG 全文 + RRF；compare 并排对照 vector / hybrid。"
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
              value={retrievalMode}
              onChange={(value) => setRetrievalMode(value as RetrievalMode)}
              data={[
                { value: 'vector', label: 'vector' },
                { value: 'hybrid', label: 'hybrid' },
              ]}
            />
            <Switch
              label="rewriteQuery（检索前改写问题）"
              checked={rewriteQuery}
              onChange={(event) => setRewriteQuery(event.currentTarget.checked)}
            />
            <SegmentedControl
              fullWidth
              value={mode}
              onChange={(value) => setMode(value as Mode)}
              data={[
                { value: 'sync', label: '单路 POST' },
                { value: 'compare', label: 'vector vs hybrid' },
              ]}
            />
            <Textarea
              label="question"
              minRows={8}
              autosize
              value={question}
              onChange={(event) => setQuestion(event.currentTarget.value)}
            />
            <Button loading={loading} disabled={!question.trim() || loading} onClick={onSubmit}>
              {mode === 'compare' ? '并排对比' : '提问'}
            </Button>
          </Stack>
        }
        result={
          <ResultBody error={error} emptyHint="ingest 后运行 compare 或单路 hybrid，结果会出现在这里。">
            {compareResult && (
              <Stack gap="lg">
                <RequestMeta elapsedMs={elapsedMs} />
                {(['vector', 'hybrid'] as const).map((key) => {
                  const side = compareResult[key]
                  return (
                    <div key={key}>
                      <Text fw={600} mb={6}>
                        {key}
                      </Text>
                      {side.retrievalEmpty && (
                        <Badge color="orange" variant="light" mb={6}>
                          retrievalEmpty
                        </Badge>
                      )}
                      {side.sources?.length > 0 && renderSources(side.sources)}
                      <pre className="stream-text">{side.answer}</pre>
                    </div>
                  )
                })}
                <RawJsonAccordion value={compareResult} />
              </Stack>
            )}
            {!compareResult && result && (
              <Stack gap="sm">
                <RequestMeta elapsedMs={elapsedMs} usage={result.usage} />
                {result.retrievalMode && (
                  <Badge variant="outline">mode: {result.retrievalMode}</Badge>
                )}
                {result.retrievalEmpty && (
                  <Badge color="orange" variant="light">
                    retrievalEmpty：无足够命中，已拒答
                  </Badge>
                )}
                {result.sources?.length > 0 && (
                  <div>
                    <Text size="sm" mb={6}>
                      sources
                    </Text>
                    {renderSources(result.sources)}
                  </div>
                )}
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
