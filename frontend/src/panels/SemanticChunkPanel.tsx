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
import { semanticChunkGuide } from '../guides'

type Mode = 'sync' | 'compare'
type Strategy = 'token' | 'semantic'

/**
 * 进阶语义分块：token vs semantic corpus 对照。
 */
export function SemanticChunkPanel({ provider }: { provider: string }) {
  const [question, setQuestion] = useState('MCP 是什么？')
  const [mode, setMode] = useState<Mode>('compare')
  const [strategy, setStrategy] = useState<Strategy>('semantic')
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
      const data = await postJson<RagIngestResponse>(`${API_BASE}/rag/ingest`, { strategy: 'all' })
      setIngestInfo(data)
      notifications.show({
        color: 'teal',
        title: '索引完成',
        message: `${data.chunkCount} chunks · strategy=${data.strategy ?? 'all'}`,
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
        chunkingStrategy: strategy,
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
      const data = await postJson<RagChunkingCompareResponse>(
        `${API_BASE}/rag/query/compare-chunking`,
        { question, provider, retrievalMode: 'vector' },
      )
      setElapsedMs(Math.round(performance.now() - started))
      setCompareResult(data)
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: 'compare-chunking 失败', message })
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
            {item.metadata?.heading != null && (
              <Badge size="xs" color="teal">
                {String(item.metadata.heading)}
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
    <SampleFrame guide={semanticChunkGuide}>
      <Workbench
        title="语义分块"
        hint="先 ingest strategy=all。对照 token / semantic 两套 corpus 的 sources。"
        form={
          <Stack gap="md">
            <Button variant="light" loading={ingesting} onClick={() => void runIngest()}>
              重建索引（all）
            </Button>
            {ingestInfo && (
              <Text size="sm" c="dimmed">
                最近 ingest：{ingestInfo.chunkCount} chunks
                {ingestInfo.corpora
                  ? ` · ${Object.entries(ingestInfo.corpora)
                      .map(([k, v]) => `${k}=${v}`)
                      .join(', ')}`
                  : ''}
              </Text>
            )}
            <SegmentedControl
              fullWidth
              value={mode}
              onChange={(value) => setMode(value as Mode)}
              data={[
                { value: 'sync', label: '单路 POST' },
                { value: 'compare', label: 'token vs semantic' },
              ]}
            />
            {mode === 'sync' && (
              <SegmentedControl
                fullWidth
                value={strategy}
                onChange={(value) => setStrategy(value as Strategy)}
                data={[
                  { value: 'token', label: 'token' },
                  { value: 'semantic', label: 'semantic' },
                ]}
              />
            )}
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
              {mode === 'compare' ? '对照两套命中' : '提问'}
            </Button>
          </Stack>
        }
        result={
          <ResultBody error={error} emptyHint="ingest 后运行对照或单路 semantic。">
            {compareResult && (
              <Stack gap="lg">
                <RequestMeta elapsedMs={elapsedMs} />
                {(['token', 'semantic'] as const).map((key) => {
                  const side = compareResult[key]
                  return (
                    <div key={key}>
                      <Text fw={600} mb={6}>
                        {key} · {side.corpus}
                      </Text>
                      {side.retrievalEmpty && (
                        <Badge color="orange" variant="light" mb={6}>
                          retrievalEmpty
                        </Badge>
                      )}
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
                {result.chunkingStrategy && (
                  <Badge variant="outline">chunking: {result.chunkingStrategy}</Badge>
                )}
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
