import { Badge, Button, Group, SegmentedControl, Stack, Text, Textarea } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useState } from 'react'
import {
  describeError,
  postJson,
  API_BASE,
  type RagExpansionCompareResponse,
  type RagIngestResponse,
  type RagQueryResponse,
  type RagSource,
} from '../api'
import { RawJsonAccordion } from '../components/RawJsonAccordion'
import { RequestMeta } from '../components/RequestMeta'
import { ResultBody } from '../components/ResultBody'
import { SampleFrame } from '../components/SampleFrame'
import { Workbench } from '../components/Workbench'
import { hydeGuide } from '../guides'

type Mode = 'sync' | 'compare'
type Expansion = 'none' | 'rewrite' | 'hyde'

/**
 * 进阶 HyDE：none / rewrite / hyde 对照；假想文档仅预览。
 */
export function HydePanel({ provider }: { provider: string }) {
  const [question, setQuestion] = useState('一期都学了哪些能力？')
  const [mode, setMode] = useState<Mode>('compare')
  const [expansion, setExpansion] = useState<Expansion>('hyde')
  const [loading, setLoading] = useState(false)
  const [ingesting, setIngesting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [ingestInfo, setIngestInfo] = useState<RagIngestResponse | null>(null)
  const [result, setResult] = useState<RagQueryResponse | null>(null)
  const [compareResult, setCompareResult] = useState<RagExpansionCompareResponse | null>(null)
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
        queryExpansion: expansion,
        retrievalMode: 'vector',
      })
      setElapsedMs(Math.round(performance.now() - started))
      setResult(data)
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: 'HyDE 查询失败', message })
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
      const data = await postJson<RagExpansionCompareResponse>(
        `${API_BASE}/rag/query/compare-expansion`,
        { question, provider, retrievalMode: 'vector' },
      )
      setElapsedMs(Math.round(performance.now() - started))
      setCompareResult(data)
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: 'compare-expansion 失败', message })
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
          </Group>
          <Text size="sm" c="dimmed">
            {item.excerpt}
          </Text>
        </div>
      ))}
    </Stack>
  )

  return (
    <SampleFrame guide={hydeGuide}>
      <Workbench
        title="HyDE"
        hint="假想文档只用于检索。compare-expansion 默认只比 sources，不三次生成。"
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
                { value: 'sync', label: '单路 POST' },
                { value: 'compare', label: 'none / rewrite / hyde' },
              ]}
            />
            {mode === 'sync' && (
              <SegmentedControl
                fullWidth
                value={expansion}
                onChange={(value) => setExpansion(value as Expansion)}
                data={[
                  { value: 'none', label: 'none' },
                  { value: 'rewrite', label: 'rewrite' },
                  { value: 'hyde', label: 'hyde' },
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
            <Button loading={loading} disabled={!question.trim() || loading} onClick={onSubmit}>
              {mode === 'compare' ? '对照三套命中' : '提问'}
            </Button>
          </Stack>
        }
        result={
          <ResultBody error={error} emptyHint="ingest 后运行对照或单路 hyde。">
            {compareResult && (
              <Stack gap="lg">
                <RequestMeta elapsedMs={elapsedMs} />
                {(['none', 'rewrite', 'hyde'] as const).map((key) => {
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
                      {side.hypotheticalDocument && (
                        <Text size="sm" mb={6} c="dimmed">
                          假想文档预览：{side.hypotheticalDocument.slice(0, 200)}
                          {side.hypotheticalDocument.length > 200 ? '…' : ''}
                        </Text>
                      )}
                      {side.rewrittenQuery && (
                        <Text size="sm" mb={6} c="dimmed">
                          改写：{side.rewrittenQuery}
                        </Text>
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
                {result.queryExpansion && (
                  <Badge variant="outline">expansion: {result.queryExpansion}</Badge>
                )}
                {result.hypotheticalDocument && (
                  <div>
                    <Text size="sm" mb={4}>
                      假想文档预览
                    </Text>
                    <Text size="sm" c="dimmed">
                      {result.hypotheticalDocument}
                    </Text>
                  </div>
                )}
                {result.retrievalEmpty && (
                  <Badge color="orange" variant="light">
                    retrievalEmpty
                  </Badge>
                )}
                {result.sources?.length > 0 && (
                  <div>
                    <Text size="sm" mb={6}>
                      sources（真实 chunk）
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
