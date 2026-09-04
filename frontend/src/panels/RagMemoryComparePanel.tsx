import { Badge, Button, Checkbox, Stack, Text, TextInput, Textarea } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useState } from 'react'
import {
  API_BASE,
  describeError,
  postJson,
  type RagMemoryCompareResponse,
} from '../api'
import { MarkdownBody } from '../components/MarkdownBody'
import { RawJsonAccordion } from '../components/RawJsonAccordion'
import { RequestMeta } from '../components/RequestMeta'
import { ResultBody } from '../components/ResultBody'
import { SampleFrame } from '../components/SampleFrame'
import { Workbench } from '../components/Workbench'
import { ragMemoryCompareGuide } from '../guides'

/**
 * RAG vs 记忆双路对照。
 */
export function RagMemoryComparePanel({ provider }: { provider: string }) {
  const [question, setQuestion] = useState('我喜欢吃什么？一期 RAG 是什么？')
  const [userId, setUserId] = useState('demo')
  const [generateAnswers, setGenerateAnswers] = useState(true)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<RagMemoryCompareResponse | null>(null)
  const [elapsedMs, setElapsedMs] = useState<number | null>(null)

  const run = async () => {
    setError(null)
    setResult(null)
    setLoading(true)
    const started = performance.now()
    try {
      const data = await postJson<RagMemoryCompareResponse>(`${API_BASE}/rag/query/compare-memory`, {
        question,
        provider,
        userId,
        generateAnswers,
      })
      setElapsedMs(Math.round(performance.now() - started))
      setResult(data)
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: 'compare-memory 失败', message })
    } finally {
      setLoading(false)
    }
  }

  return (
    <SampleFrame guide={ragMemoryCompareGuide}>
      <Workbench
        title="RAG vs 记忆"
        hint="同问双路：知识库 vs 个人事实；语料按 corpus 隔离。"
        form={
          <Stack gap="md">
            <TextInput label="userId" value={userId} onChange={(e) => setUserId(e.currentTarget.value)} />
            <Textarea
              label="question"
              minRows={5}
              autosize
              value={question}
              onChange={(e) => setQuestion(e.currentTarget.value)}
            />
            <Checkbox
              label="generateAnswers"
              checked={generateAnswers}
              onChange={(e) => setGenerateAnswers(e.currentTarget.checked)}
            />
            <Button loading={loading} disabled={!question.trim()} onClick={() => void run()}>
              对照
            </Button>
          </Stack>
        }
        result={
          <ResultBody error={error} emptyHint="对照后左右展示 RAG 与 Memory。">
            {result && (
              <Stack gap="md">
                <RequestMeta elapsedMs={elapsedMs} usage={result.rag.usage} />
                <div>
                  <Badge color={result.rag.retrievalEmpty ? 'orange' : 'teal'} variant="light">
                    RAG empty={String(result.rag.retrievalEmpty)}
                  </Badge>
                  <MarkdownBody>{result.rag.answer}</MarkdownBody>
                </div>
                <div>
                  <Badge color={result.memory.retrievalEmpty ? 'orange' : 'teal'} variant="light">
                    Memory empty={String(result.memory.retrievalEmpty)} · {result.memory.userId}
                  </Badge>
                  <MarkdownBody>{result.memory.answer}</MarkdownBody>
                </div>
                <RawJsonAccordion value={result} />
              </Stack>
            )}
          </ResultBody>
        }
      />
    </SampleFrame>
  )
}
