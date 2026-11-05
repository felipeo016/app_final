// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AssignableUser, Conversation, Role } from '../api/types'
import type { Session } from '../auth/session'
import type { ConversationChat } from '../conversation/useConversation'
import ContactPanel from './ContactPanel'

const USERS: AssignableUser[] = [
  { id: 'owner-1', name: 'Lucía', role: 'OWNER' },
  { id: 'agent-1', name: 'Ana', role: 'AGENT' },
  { id: 'agent-2', name: 'Luis', role: 'AGENT' },
]

function conversation(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: 'c1',
    contact: { id: 'contact-1', name: 'María' },
    channel: 'WHATSAPP',
    status: 'IN_PROGRESS',
    open: true,
    assignedTo: null,
    lastMessagePreview: 'hola',
    lastMessageAt: '2026-11-04T10:00:00Z',
    lastInboundAt: '2026-11-04T10:00:00Z',
    lastOutboundAt: null,
    firstResponseAt: null,
    unreadCount: 0,
    atRisk: false,
    atRiskSince: null,
    replyWindowOpen: true,
    replyWindowExpiresAt: null,
    isSale: false,
    saleAmount: null,
    tags: [],
    createdAt: '2026-11-04T09:00:00Z',
    ...overrides,
  }
}

function session(role: Role, userId: string): Session {
  return {
    token: 't',
    expiresAt: Date.now() + 3_600_000,
    user: { id: userId, tenantId: 'tenant', name: 'Yo', email: 'yo@test.com', role, active: true, createdAt: '' },
    tenant: { id: 'tenant', name: 'Acme' },
  }
}

function chat(current: Conversation) {
  return {
    conversation: current,
    assign: vi.fn().mockResolvedValue(undefined),
    markSale: vi.fn().mockResolvedValue(undefined),
    changeStatus: vi.fn().mockResolvedValue(undefined),
  } as unknown as ConversationChat & { assign: ReturnType<typeof vi.fn>; markSale: ReturnType<typeof vi.fn> }
}

const PROFILE = {
  id: 'contact-1',
  name: 'María',
  identities: [{ channel: 'WHATSAPP', externalId: '573001112222' }],
  notes: null,
  createdAt: '2026-11-01T00:00:00Z',
  conversations: [
    { id: 'c1', channel: 'WHATSAPP', status: 'IN_PROGRESS', open: true, lastMessagePreview: 'hola', lastMessageAt: null, createdAt: '' },
    { id: 'old', channel: 'WHATSAPP', status: 'RESOLVED', open: false, lastMessagePreview: 'gracias', lastMessageAt: null, createdAt: '2026-10-01T00:00:00Z' },
  ],
}

function renderPanel(role: Role, userId: string, current: Conversation, onOpenConversation = vi.fn()) {
  const fake = chat(current)
  render(
    <ContactPanel
      chat={fake}
      session={session(role, userId)}
      users={USERS}
      onOpenConversation={onOpenConversation}
      onBackToChat={vi.fn()}
    />,
  )
  return { fake, onOpenConversation }
}

describe('panel del cliente', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify(PROFILE), { status: 200, headers: { 'Content-Type': 'application/json' } })),
    )
  })
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('un AGENT ve "Tomar este chat" si está libre y "Soltar este chat" si es suyo; nunca la lista', async () => {
    const user = userEvent.setup()
    const { fake } = renderPanel('AGENT', 'agent-1', conversation())

    expect(screen.queryByRole('combobox', { name: 'Agente asignado' })).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Tomar este chat' }))
    expect(fake.assign).toHaveBeenCalledWith('agent-1')

    cleanup()
    const mine = renderPanel('AGENT', 'agent-1', conversation({ assignedTo: { id: 'agent-1', name: 'Ana' } }))
    await user.click(screen.getByRole('button', { name: 'Soltar este chat' }))
    expect(mine.fake.assign).toHaveBeenCalledWith(null)

    cleanup()
    renderPanel('AGENT', 'agent-1', conversation({ assignedTo: { id: 'agent-2', name: 'Luis' } }))
    expect(screen.queryByRole('button', { name: /Tomar|Soltar/ })).toBeNull()
    expect(screen.getByText('Luis')).toBeTruthy()
  })

  it('el OWNER elige a cualquier usuario asignable, incluido él mismo', async () => {
    const user = userEvent.setup()
    const { fake } = renderPanel('OWNER', 'owner-1', conversation())

    const select = screen.getByRole('combobox', { name: 'Agente asignado' })
    expect([...select.querySelectorAll('option')].map((o) => o.textContent)).toEqual([
      'Sin asignar',
      'Lucía (tú)',
      'Ana',
      'Luis',
    ])
    await user.selectOptions(select, 'agent-2')
    expect(fake.assign).toHaveBeenCalledWith('agent-2')
  })

  it('el estado no ofrece "Resuelto": resolver queda solo en el encabezado del chat', () => {
    renderPanel('OWNER', 'owner-1', conversation())
    const status = screen.getByRole('combobox', { name: 'Estado de la conversación' })
    expect([...status.querySelectorAll('option')].map((o) => o.textContent)).toEqual(['Nuevo', 'En proceso'])
    expect(screen.queryByRole('button', { name: /resuelta|Resolver/i })).toBeNull()
  })

  it('marca la venta en pesos sin decimales y permite quitarla', async () => {
    const user = userEvent.setup()
    const { fake } = renderPanel('AGENT', 'agent-1', conversation())

    await user.click(screen.getByRole('button', { name: 'Marcar como venta' }))
    const amount = screen.getByRole('textbox', { name: 'Monto de la venta en pesos' }) as HTMLInputElement
    await user.type(amount, '150000')
    expect(amount.value).toBe('150.000')
    await user.click(screen.getByRole('button', { name: 'Guardar venta' }))
    expect(fake.markSale).toHaveBeenCalledWith(true, 150000)

    cleanup()
    const sold = renderPanel('AGENT', 'agent-1', conversation({ isSale: true, saleAmount: 150000 }))
    expect(screen.getByText(/150\.000/).textContent?.replace(/ /g, ' ')).toBe('$ 150.000')
    await user.click(screen.getByRole('button', { name: 'Quitar marca de venta' }))
    expect(sold.fake.markSale).toHaveBeenCalledWith(false, null)
  })

  it('el historial lista las conversaciones anteriores y las abre', async () => {
    const user = userEvent.setup()
    const { onOpenConversation } = renderPanel('AGENT', 'agent-1', conversation())

    const previous = await waitFor(() => screen.getByRole('button', { name: /gracias/ }))
    expect(screen.queryByRole('button', { name: /^.*hola$/ })).toBeNull() // la actual no aparece
    await user.click(previous)
    expect(onOpenConversation).toHaveBeenCalledWith('old')
  })
})
