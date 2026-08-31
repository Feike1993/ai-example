import { Badge, Button, Group, Stack, Text, TextInput, Textarea } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useState } from 'react'
import {
  API_BASE,
  describeError,
  postJson,
  type MemoryChatResponse,
  type MemoryRecallResponse,
  type MemoryRememberResponse,
} from '../api'
import { RawJsonAccordion } from '../components/RawJsonAccordion'
import { RequestMeta } from '../components/RequestMeta'
import { ResultBody } from '../components/ResultBody'
import { SampleFrame } from '../components/SampleFrame'
import { Workbench } from '../components/Workbench'
import { memoryGuide } from '../guides'

type MemoryAction = 'remember' | 'recall' | 'chat'

/**
 * 长期记忆：remember / recall / chat；与 RAG 演示语料隔离。
 */
export function MemoryPanel({ provider }: { provider: string }) {
  const [userId, setUserId] = useState('demo')
  const [fact, setFact] = useState('用户名叫小明，喜欢北京烤鸭')
  const [prompt, setPrompt] = useState('根据记忆，我喜欢吃什么？')
  const [pendingAction, setPendingAction] = useState<MemoryAction | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [rememberResult, setRememberResult] = useState<MemoryRememberResponse | null>(null)
  const [recallResult, setRecallResult] = useState<MemoryRecallResponse | null>(null)
  const [chatResult, setChatResult] = useState<MemoryChatResponse | null>(null)
  const [elapsedMs, setElapsedMs] = useState<number | null>(null)

  const runRemember = async () => {
    setError(null)
    setPendingAction('remember')
    try {
      const data = await postJson<MemoryRememberResponse>(`${API_BASE}/memory/remember`, {
        text: fact,
        userId,
      })
      setRememberResult(data)
      if (data.duplicate) {
        notifications.show({
          color: 'yellow',
          title: '记忆已存在',
          message: '相同事实不会重复写入',
        })
      } else if (data.updated) {
        notifications.show({
          color: 'teal',
          title: '已合并更新已有记忆',
          message: data.id,
        })
      } else {
        notifications.show({ color: 'teal', title: '已写入记忆', message: data.id })
      }
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: 'remember 失败', message })
    } finally {
      setPendingAction(null)
    }
  }

  const runRecall = async () => {
    setError(null)
    setPendingAction('recall')
    const started = performance.now()
    try {
      const data = await postJson<MemoryRecallResponse>(`${API_BASE}/memory/recall`, {
        query: prompt,
        userId,
      })
      setElapsedMs(Math.round(performance.now() - started))
      setRecallResult(data)
      setChatResult(null)
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: 'recall 失败', message })
    } finally {
      setPendingAction(null)
    }
  }

  const runChat = async () => {
    setError(null)
    setPendingAction('chat')
    const started = performance.now()
    try {
      const data = await postJson<MemoryChatResponse>(`${API_BASE}/memory/chat`, {
        prompt,
        userId,
        provider,
      })
      setElapsedMs(Math.round(performance.now() - started))
      setChatResult(data)
      setRecallResult({ userId: data.userId, sources: data.sources, empty: data.retrievalEmpty })
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: 'memory chat 失败', message })
    } finally {
      setPendingAction(null)
    }
  }

  const busy = pendingAction !== null

  const clearMemory = async () => {
    try {
      await fetch(`${API_BASE}/memory?userId=${encodeURIComponent(userId)}`, { method: 'DELETE' })
      setRememberResult(null)
      setRecallResult(null)
      setChatResult(null)
      notifications.show({ color: 'teal', title: '已清空记忆', message: userId })
    } catch (err) {
      notifications.show({ color: 'red', title: '清空失败', message: describeError(err) })
    }
  }

  const sources = chatResult?.sources ?? recallResult?.sources ?? []

  return (
    <SampleFrame guide={memoryGuide}>
      <Workbench
        title="长期记忆"
        hint="需要 Docker pgvector + DashScope Embedding。corpus=long-term-memory，与 RAG 演示语料隔离。"
        form={
          <Stack gap="md">
            <TextInput
              label="userId"
              value={userId}
              onChange={(event) => setUserId(event.currentTarget.value)}
            />
            <Textarea
              label="事实（remember）"
              minRows={3}
              autosize
              value={fact}
              onChange={(event) => setFact(event.currentTarget.value)}
            />
            <Textarea
              label="问题（recall / chat）"
              minRows={4}
              autosize
              value={prompt}
              onChange={(event) => setPrompt(event.currentTarget.value)}
            />
            <Group>
              <Button
                loading={pendingAction === 'remember'}
                disabled={!fact.trim() || busy}
                onClick={() => void runRemember()}
              >
                Remember
              </Button>
              <Button
                variant="light"
                loading={pendingAction === 'recall'}
                disabled={!prompt.trim() || busy}
                onClick={() => void runRecall()}
              >
                Recall
              </Button>
              <Button
                loading={pendingAction === 'chat'}
                disabled={!prompt.trim() || busy}
                onClick={() => void runChat()}
              >
                Chat with memory
              </Button>
              <Button variant="subtle" color="red" onClick={() => void clearMemory()}>
                清空
              </Button>
            </Group>
          </Stack>
        }
        result={
          <ResultBody error={error} emptyHint="先 Remember 事实，再 Recall 或 Chat。">
            {(rememberResult || sources.length > 0 || chatResult) && (
              <Stack gap="sm">
                <RequestMeta elapsedMs={elapsedMs} usage={chatResult?.usage} />
                {rememberResult && (
                  <Text size="sm" c="dimmed">
                    最近写入：{rememberResult.text}（id={rememberResult.id}）
                  </Text>
                )}
                {chatResult?.retrievalEmpty && (
                  <Badge color="orange" variant="light">
                    retrievalEmpty：无记忆命中，已拒答
                  </Badge>
                )}
                {sources.length > 0 && (
                  <Stack gap={6}>
                    <Text size="sm">sources</Text>
                    {sources.map((item) => (
                      <Text key={item.id} size="sm" c="dimmed">
                        {item.excerpt}
                      </Text>
                    ))}
                  </Stack>
                )}
                {chatResult && <pre className="stream-text">{chatResult.answer}</pre>}
                {(chatResult || recallResult) && (
                  <RawJsonAccordion value={chatResult ?? recallResult} />
                )}
              </Stack>
            )}
          </ResultBody>
        }
      />
    </SampleFrame>
  )
}
