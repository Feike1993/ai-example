import { Alert, Badge, Button, Group, NumberInput, Stack, Text, Textarea } from '@mantine/core'
import { useEffect, useRef, useState } from 'react'
import { ApiError, describeError } from '../../api'
import { MarkdownBody } from '../../components/MarkdownBody'
import { ResultBody } from '../../components/ResultBody'
import { Workbench } from '../../components/Workbench'
import { chatStreamUrl, postIngest, resumeUrl } from '../lib/productionApi'
import {
  StreamAbortedError,
  StreamServerError,
  streamRun,
  type ProductionSource,
  type SourcesPayload,
  type UsagePayload,
} from '../lib/sseClient'

type ProductionChatPanelProps = {
  provider: string
}

/**
 * 生产链路问答面板。
 *
 * 与样例面板的关键差别在收尾判定：这里区分「完成」「取消」「断流」「服务端报错」四种终态，
 * 只有拿到 done 才展示为成功。断流时保留已收到的正文，但明确标注结果不完整——
 * 把不完整的回答当成完整答案展示，是这类界面最危险的默认行为。
 */
export function ProductionChatPanel({ provider }: ProductionChatPanelProps) {
  const [question, setQuestion] = useState('这个项目的 RAG 是怎么做的？')
  const [topK, setTopK] = useState<number | string>(4)
  const [answer, setAnswer] = useState('')
  const [sources, setSources] = useState<ProductionSource[]>([])
  const [meta, setMeta] = useState<{ runId: string; retrievalMode?: string } | null>(null)
  const [usage, setUsage] = useState<UsagePayload | null>(null)
  const [malformed, setMalformed] = useState(0)
  const [status, setStatus] = useState<'idle' | 'streaming' | 'done' | 'cancelled' | 'aborted'>('idle')
  const [error, setError] = useState<string | null>(null)
  const [ingesting, setIngesting] = useState(false)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => () => abortRef.current?.abort(), [])

  const reset = () => {
    setAnswer('')
    setSources([])
    setMeta(null)
    setUsage(null)
    setMalformed(0)
    setError(null)
  }

  const onStream = async () => {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller
    reset()
    setStatus('streaming')

    let acc = ''
    try {
      const result = await streamRun(
        chatStreamUrl({ question, provider, topK: Number(topK) || undefined }),
        {
          signal: controller.signal,
          resumeUrl,
          maxResumes: 2,
          handlers: {
            onMeta: (payload) => setMeta({ runId: payload.runId }),
            onSources: (payload: SourcesPayload) => {
              setSources(payload.sources ?? [])
              setMeta((prev) =>
                prev ? { ...prev, retrievalMode: payload.retrievalMode } : prev,
              )
            },
            onDelta: (text) => {
              acc += text
              setAnswer(acc)
            },
            onUsage: setUsage,
            onMalformed: () => setMalformed((count) => count + 1),
          },
        },
      )
      setStatus(result.outcome === 'cancelled' ? 'cancelled' : 'done')
    } catch (err) {
      if (err instanceof StreamAbortedError) {
        setStatus('aborted')
        setError(err.message)
      } else if (err instanceof StreamServerError) {
        setStatus('aborted')
        setError(`${err.message}（错误码 ${err.code}）`)
      } else {
        setStatus('aborted')
        setError(describeError(err))
      }
    } finally {
      abortRef.current = null
    }
  }

  const onStop = () => {
    abortRef.current?.abort()
    abortRef.current = null
  }

  const onIngest = async () => {
    setIngesting(true)
    setError(null)
    try {
      const result = await postIngest()
      setAnswer(`已重建语料 ${result.corpus}：${result.chunkCount} 块，来自 ${result.sources.length} 个文件。`)
      setSources([])
      setStatus('done')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : describeError(err))
      setStatus('aborted')
    } finally {
      setIngesting(false)
    }
  }

  const streaming = status === 'streaming'

  return (
    <Workbench
      title="生产问答"
      hint="严格 SSE 契约：meta → sources → delta → usage → done。只有收到 done 才算成功；断流会显式标注。"
      streaming={streaming}
      form={
        <Stack gap="sm">
          <Textarea
            label="问题"
            autosize
            minRows={3}
            value={question}
            onChange={(event) => setQuestion(event.currentTarget.value)}
          />
          <NumberInput label="topK" min={1} max={20} value={topK} onChange={setTopK} />
          <Group gap="sm">
            <Button onClick={onStream} loading={streaming} disabled={!question.trim()}>
              流式提问
            </Button>
            <Button variant="default" onClick={onStop} disabled={!streaming}>
              停止
            </Button>
            <Button variant="subtle" onClick={onIngest} loading={ingesting}>
              重建语料
            </Button>
          </Group>
          <Text size="xs" c="dimmed">
            当前模型：{provider}
          </Text>
        </Stack>
      }
      result={
        <ResultBody error={error} emptyHint="提交问题后，这里按事件顺序展示检索来源与流式答案。">
          {/* 状态行从发起请求那刻就要在：取消或断流可能一个字都没收到，
              此时更需要告诉用户「发生了什么」，而不是退回空态提示 */}
          {status !== 'idle' ? (
            <Stack gap="sm">
              <Group gap="xs">
                <StatusBadge status={status} />
                {meta?.retrievalMode ? <Badge variant="light">{meta.retrievalMode}</Badge> : null}
                {malformed > 0 ? (
                  <Badge color="orange" variant="light">
                    {malformed} 条事件无法解析
                  </Badge>
                ) : null}
              </Group>
              {status === 'aborted' ? (
                <Alert color="orange" variant="light" title="结果可能不完整">
                  未收到 done 事件，以下内容是断流前已接收的部分。
                </Alert>
              ) : null}
              <MarkdownBody streaming={streaming}>{answer}</MarkdownBody>
              {sources.length > 0 ? <SourceList sources={sources} /> : null}
              {usage ? (
                <Text size="xs" c="dimmed">
                  answerChars {usage.answerChars} · sourceCount {usage.sourceCount}
                </Text>
              ) : null}
              {meta?.runId ? (
                <Text size="xs" c="dimmed">
                  runId {meta.runId}
                </Text>
              ) : null}
            </Stack>
          ) : null}
        </ResultBody>
      }
    />
  )
}

function StatusBadge({ status }: { status: string }) {
  const map: Record<string, { color: string; label: string }> = {
    idle: { color: 'gray', label: '待命' },
    streaming: { color: 'blue', label: '生成中' },
    done: { color: 'teal', label: '已完成' },
    cancelled: { color: 'gray', label: '已取消' },
    aborted: { color: 'orange', label: '未完成' },
  }
  const view = map[status] ?? map.idle
  return (
    <Badge color={view.color} variant="light">
      {view.label}
    </Badge>
  )
}

function SourceList({ sources }: { sources: ProductionSource[] }) {
  return (
    <Stack gap={6}>
      <Text size="sm" fw={600}>
        检索来源（{sources.length}）
      </Text>
      {sources.map((source) => (
        <div key={source.id} className="industrial-source">
          <Text size="xs" c="dimmed">
            {source.source}
            {source.heading ? ` · ${source.heading}` : ''}
          </Text>
          <Text size="sm">{source.excerpt}</Text>
        </div>
      ))}
    </Stack>
  )
}
