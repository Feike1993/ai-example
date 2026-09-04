import { Badge, Button, Stack, Text, TextInput, Textarea } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useState } from 'react'
import {
  API_BASE,
  describeError,
  postJson,
  type RagIngestResponse,
  type RagMemoryRewriteCompareResponse,
  type RagQueryResponse,
} from '../api'
import { MarkdownBody } from '../components/MarkdownBody'
import { RawJsonAccordion } from '../components/RawJsonAccordion'
import { RequestMeta } from '../components/RequestMeta'
import { ResultBody } from '../components/ResultBody'
import { SampleFrame } from '../components/SampleFrame'
import { Workbench } from '../components/Workbench'
import { memoryInformedRagGuide } from '../guides'

/**
 * 记忆辅助改写：memory_rewrite + 可选三路对照。
 */
export function MemoryInformedRagPanel({ provider }: { provider: string }) {
  const [question, setQuestion] = useState('一期都学了哪些？')
  const [userId, setUserId] = useState('demo')
  const [loading, setLoading] = useState(false)
  const [ingesting, setIngesting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<RagQueryResponse | null>(null)
  const [compare, setCompare] = useState<RagMemoryRewriteCompareResponse | null>(null)
  const [elapsedMs, setElapsedMs] = useState<number | null>(null)

  const runIngest = async () => {
    setIngesting(true)
    try {
      const data = await postJson<RagIngestResponse>(`${API_BASE}/rag/ingest`, {})
      notifications.show({ color: 'teal', title: '索引完成', message: `${data.chunkCount} chunks` })
    } catch (err) {
      notifications.show({ color: 'red', title: 'ingest 失败', message: describeError(err) })
    } finally {
      setIngesting(false)
    }
  }

  const runQuery = async () => {
    setError(null)
    setCompare(null)
    setResult(null)
    setLoading(true)
    const started = performance.now()
    try {
      const data = await postJson<RagQueryResponse>(`${API_BASE}/rag/query`, {
        question,
        provider,
        queryExpansion: 'memory_rewrite',
        userId,
        retrievalMode: 'vector',
      })
      setElapsedMs(Math.round(performance.now() - started))
      setResult(data)
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: 'memory_rewrite 失败', message })
    } finally {
      setLoading(false)
    }
  }

  const runCompare = async () => {
    setError(null)
    setResult(null)
    setLoading(true)
    const started = performance.now()
    try {
      const data = await postJson<RagMemoryRewriteCompareResponse>(
        `${API_BASE}/rag/query/compare-memory-rewrite`,
        { question, provider, userId },
      )
      setElapsedMs(Math.round(performance.now() - started))
      setCompare(data)
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: 'compare-memory-rewrite 失败', message })
    } finally {
      setLoading(false)
    }
  }

  return (
    <SampleFrame guide={memoryInformedRagGuide}>
      <Workbench
        title="记忆辅助改写"
        hint="先 remember 事实并 ingest；memoryHints 不进 RAG sources。"
        form={
          <Stack gap="md">
            <Button variant="light" loading={ingesting} onClick={() => void runIngest()}>
              重建 RAG 索引
            </Button>
            <TextInput label="userId" value={userId} onChange={(e) => setUserId(e.currentTarget.value)} />
            <Textarea
              label="question"
              minRows={5}
              autosize
              value={question}
              onChange={(e) => setQuestion(e.currentTarget.value)}
            />
            <Button loading={loading} disabled={!question.trim()} onClick={() => void runQuery()}>
              memory_rewrite 查询
            </Button>
            <Button variant="light" loading={loading} onClick={() => void runCompare()}>
              三路对照（只比 sources）
            </Button>
          </Stack>
        }
        result={
          <ResultBody error={error} emptyHint="查询后显示 rewrittenQuery 与 memoryHints。">
            {(result || compare) && (
              <Stack gap="sm">
                <RequestMeta elapsedMs={elapsedMs} usage={result?.usage} />
                {result && (
                  <>
                    <Badge variant="light">expansion={result.queryExpansion}</Badge>
                    {result.rewrittenQuery && (
                      <Text size="sm">rewrittenQuery：{result.rewrittenQuery}</Text>
                    )}
                    {result.memoryHints && result.memoryHints.length > 0 && (
                      <div>
                        <Text size="sm" mb={4}>
                          memoryHints
                        </Text>
                        {result.memoryHints.map((h) => (
                          <Text key={h.id} size="sm" c="dimmed">
                            [{h.id}] {h.excerpt}
                          </Text>
                        ))}
                      </div>
                    )}
                    <MarkdownBody>{result.answer}</MarkdownBody>
                    <RawJsonAccordion value={result} />
                  </>
                )}
                {compare && <RawJsonAccordion value={compare} />}
              </Stack>
            )}
          </ResultBody>
        }
      />
    </SampleFrame>
  )
}
