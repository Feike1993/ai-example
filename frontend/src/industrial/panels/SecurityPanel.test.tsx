import { MantineProvider } from '@mantine/core'
import { render, screen, waitFor } from '@testing-library/react'
import type { ReactElement } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setSession } from '../lib/auth'
import { SecurityPanel } from './SecurityPanel'

function renderPanel(ui: ReactElement) {
  return render(<MantineProvider forceColorScheme="light">{ui}</MantineProvider>)
}

describe('SecurityPanel', () => {
  beforeEach(() => {
    sessionStorage.clear()
    setSession('tok-1', { username: 'alice', tenant: 'tenant-a', roles: ['USER'] })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  it('展示本租户审计列表', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify([
      {
        id: 9,
        tenantId: 'tenant-a',
        principal: 'alice',
        action: 'auth.login',
        path: '/api/v1/auth/token',
        status: 200,
        runId: null,
        questionSha256: null,
        durationMs: null,
        ip: '127.0.0.1',
        createdAt: '2026-09-09T01:00:00Z',
      },
    ]), { status: 200 })))
    renderPanel(<SecurityPanel onLogout={() => undefined} />)
    await waitFor(() => expect(screen.getByText('auth.login')).toBeInTheDocument())
    expect(screen.getByText('alice · tenant-a · USER')).toBeInTheDocument()
  })
})
