import { Alert, Badge, Button, Group, PasswordInput, Stack, Table, Text, TextInput } from '@mantine/core'
import { useEffect, useState } from 'react'
import { ApiError, describeError } from '../../api'
import { ResultBody } from '../../components/ResultBody'
import { Workbench } from '../../components/Workbench'
import { DEMO_ACCOUNTS, getProductionUser, getRateRemaining, setSession } from '../lib/auth'
import { getAudit, postToken, type AuditRow } from '../lib/productionApi'

type SecurityPanelProps = {
  onLogout: () => void
}

/**
 * 安全面板：当前身份、限流剩余、最近审计、演示账号。
 */
export function SecurityPanel({ onLogout }: SecurityPanelProps) {
  const user = getProductionUser()
  const [rows, setRows] = useState<AuditRow[]>([])
  const [error, setError] = useState<string | null>(null)
  const remaining = getRateRemaining()

  const load = async () => {
    setError(null)
    try {
      setRows(await getAudit(30))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : describeError(err))
    }
  }

  useEffect(() => {
    void load()
  }, [])

  return (
    <Workbench
      title="安全"
      hint="JWT 身份、租户隔离、限流与审计。问题原文不落库，只有 SHA-256。"
      form={
        <Stack gap="sm">
          <Text size="sm" fw={600}>
            当前身份
          </Text>
          <Text size="sm">
            {user ? `${user.username} · ${user.tenant} · ${user.roles.join(', ')}` : '未登录'}
          </Text>
          <Text size="sm">
            限流剩余：{remaining ?? '尚未发起需限流的请求'}
          </Text>
          <Button variant="default" onClick={() => void load()}>
            刷新审计
          </Button>
          <Button variant="subtle" color="red" onClick={onLogout}>
            退出登录
          </Button>
          <Text size="xs" c="dimmed">
            演示账号密码均为 demo
          </Text>
          {DEMO_ACCOUNTS.map((item) => (
            <Text key={item.username} size="xs" c="dimmed">
              {item.username} / {item.tenant} / {item.roles} — {item.hint}
            </Text>
          ))}
        </Stack>
      }
      result={
        <ResultBody error={error} emptyHint="登录后这里列出本租户最近的审计记录。">
          {rows.length > 0 ? (
            <Table striped highlightOnHover withTableBorder>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>时间</Table.Th>
                  <Table.Th>用户</Table.Th>
                  <Table.Th>动作</Table.Th>
                  <Table.Th>状态</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((row) => (
                  <Table.Tr key={row.id}>
                    <Table.Td>
                      <Text size="xs">{row.createdAt}</Text>
                    </Table.Td>
                    <Table.Td>{row.principal}</Table.Td>
                    <Table.Td>
                      <Badge size="sm" variant="light">
                        {row.action}
                      </Badge>
                    </Table.Td>
                    <Table.Td>{row.status}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          ) : null}
        </ResultBody>
      }
    />
  )
}

type LoginFormProps = {
  onLoggedIn: () => void
}

/**
 * 工业级登录门。
 */
export function LoginForm({ onLoggedIn }: LoginFormProps) {
  const [username, setUsername] = useState('alice')
  const [password, setPassword] = useState('demo')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const onSubmit = async () => {
    setLoading(true)
    setError(null)
    try {
      const token = await postToken(username.trim(), password)
      setSession(token.token, {
        username: token.username,
        tenant: token.tenant,
        roles: token.roles,
      })
      onLoggedIn()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : describeError(err))
    } finally {
      setLoading(false)
    }
  }

  return (
    <Stack gap="md" maw={420} mx="auto" mt="xl">
      <Text fw={700} size="lg">
        工业级链路登录
      </Text>
      <Text size="sm" c="dimmed">
        教学样例路径仍然匿名开放；这里只武装 /api/v1。演示密码均为 demo。
      </Text>
      {error ? (
        <Alert color="red" variant="light" title="登录失败">
          {error}
        </Alert>
      ) : null}
      <TextInput label="用户名" value={username} onChange={(event) => setUsername(event.currentTarget.value)} />
      <PasswordInput label="密码" value={password} onChange={(event) => setPassword(event.currentTarget.value)} />
      <Button onClick={() => void onSubmit()} loading={loading} disabled={!username.trim()}>
        登录
      </Button>
      <Group gap="xs">
        {DEMO_ACCOUNTS.map((item) => (
          <Badge key={item.username} variant="light">
            {item.username}
          </Badge>
        ))}
      </Group>
    </Stack>
  )
}
