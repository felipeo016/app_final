import { describe, expect, it, vi } from 'vitest'
import type { Conversation, Page } from '../api/types'
import {
  applyConversation,
  compareConversations,
  cursorOf,
  DEFAULT_FILTERS,
  inboxReducer,
  INITIAL_INBOX,
  type InboxState,
} from './inboxState'
import { fetchUpTo } from './useInbox'

/** Conversación con actividad a los {@code minute} minutos (mayor = más reciente). */
function conversation(id: string, minute: number, overrides: Partial<Conversation> = {}): Conversation {
  const at = new Date(Date.UTC(2026, 10, 4, 10, minute)).toISOString()
  return {
    id,
    contact: { id: `contact-${id}`, name: id },
    channel: 'WHATSAPP',
    status: 'NEW',
    open: true,
    assignedTo: null,
    lastMessagePreview: id,
    lastMessageAt: at,
    lastInboundAt: at,
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
    createdAt: at,
    ...overrides,
  }
}

const ids = (state: InboxState) => state.conversations.map((c) => c.id)

/** Bandeja con la primera página cargada y más en el servidor. */
function firstPageLoaded(): InboxState {
  let state = inboxReducer(INITIAL_INBOX, { type: 'load-start', requestId: 1 })
  state = inboxReducer(state, {
    type: 'load-success',
    requestId: 1,
    conversations: [conversation('c50', 50), conversation('c40', 40), conversation('c30', 30)],
    hasMore: true,
  })
  return state
}

describe('orden y cursor', () => {
  it('a igual hora desempata por id, como el backend', () => {
    const a = conversation('aaa', 10)
    const b = conversation('bbb', 10)
    expect([a, b].sort(compareConversations).map((c) => c.id)).toEqual(['bbb', 'aaa'])
  })

  it('el cursor es la hora en ms y el id de la última conversación', () => {
    expect(cursorOf(conversation('c30', 30))).toBe(`${Date.UTC(2026, 10, 4, 10, 30)}_c30`)
    expect(cursorOf(conversation('sin-hora', 0, { lastMessageAt: null }))).toBe('_sin-hora')
  })
})

describe('Cargar más', () => {
  it('agrega la página siguiente al final sin repetir', () => {
    let state = inboxReducer(firstPageLoaded(), { type: 'more-start', requestId: 2 })
    expect(state.loadingMore).toBe(true)
    state = inboxReducer(state, {
      type: 'more-success',
      requestId: 2,
      conversations: [conversation('c30', 30), conversation('c20', 20), conversation('c10', 10)],
      hasMore: false,
    })
    expect(ids(state)).toEqual(['c50', 'c40', 'c30', 'c20', 'c10'])
    expect(state.hasMore).toBe(false)
    expect(state.loadingMore).toBe(false)
  })

  it('conserva la versión que ya actualizó un evento y reaplica los eventos llegados durante la carga', () => {
    let state = inboxReducer(firstPageLoaded(), { type: 'more-start', requestId: 2 })
    // Mientras carga: c40 recibe un mensaje (sube) y c10, de la página que llega, se resuelve
    state = inboxReducer(state, {
      type: 'event',
      conversation: conversation('c40', 55, { unreadCount: 3 }),
      matches: true,
    })
    state = inboxReducer(state, {
      type: 'event',
      conversation: conversation('c10', 10, { status: 'RESOLVED', open: false }),
      matches: false,
    })
    state = inboxReducer(state, {
      type: 'more-success',
      requestId: 2,
      conversations: [conversation('c20', 20), conversation('c10', 10)],
      hasMore: false,
    })
    expect(ids(state)).toEqual(['c40', 'c50', 'c30', 'c20'])
    expect(state.conversations[0].unreadCount).toBe(3)
  })

  it('una recarga descarta la respuesta de un "Cargar más" anterior', () => {
    let state = inboxReducer(firstPageLoaded(), { type: 'more-start', requestId: 2 })
    state = inboxReducer(state, { type: 'load-start', requestId: 3 })
    state = inboxReducer(state, {
      type: 'more-success',
      requestId: 2,
      conversations: [conversation('viejo', 1)],
      hasMore: false,
    })
    expect(ids(state)).not.toContain('viejo')
    expect(state.loading).toBe(true)
  })
})

describe('eventos con varias páginas', () => {
  it('una conversación nueva para la lista pero más antigua que la última cargada espera a "Cargar más"', () => {
    const list = [conversation('c50', 50), conversation('c30', 30)]
    // Si se insertara al final, el cursor saltaría las conversaciones entre c30 y c5 aún no cargadas
    expect(applyConversation(list, conversation('c5', 5), true, true).map((c) => c.id)).toEqual(['c50', 'c30'])
    // Sin más páginas no hay hueco posible: entra
    expect(applyConversation(list, conversation('c5', 5), true, false).map((c) => c.id)).toEqual(['c50', 'c30', 'c5'])
    // Si es más reciente que la última cargada, entra en su sitio
    expect(applyConversation(list, conversation('c40', 40), true, true).map((c) => c.id)).toEqual([
      'c50',
      'c40',
      'c30',
    ])
  })

  it('una conversación ya cargada siempre se actualiza o se quita, esté donde esté', () => {
    const list = [conversation('c50', 50), conversation('c30', 30)]
    const updated = applyConversation(list, conversation('c30', 30, { assignedTo: { id: 'u', name: 'Ana' } }), true, true)
    expect(updated[1].assignedTo?.name).toBe('Ana')
    expect(applyConversation(list, conversation('c30', 30), false, true).map((c) => c.id)).toEqual(['c50'])
  })
})

describe('recargar sin perder páginas', () => {
  /** Servidor falso con {@code total} conversaciones ordenadas, paginado por cursor como el backend. */
  function fakeServer(total: number) {
    const all = Array.from({ length: total }, (_, i) => conversation(`c${String(1000 - i)}`, 0, {
      lastMessageAt: new Date(Date.UTC(2026, 10, 4) + (total - i) * 60_000).toISOString(),
    }))
    const urls: string[] = []
    const fetchPage = vi.fn(async (url: string): Promise<Page<Conversation>> => {
      urls.push(url)
      const params = new URLSearchParams(url.split('?')[1])
      const size = Number(params.get('size'))
      const before = params.get('before')
      const start = before ? all.findIndex((c) => cursorOf(c) === before) + 1 : 0
      const items = all.slice(start, start + size)
      const more = start + size < all.length
      return {
        items,
        page: 0,
        size,
        totalItems: total,
        totalPages: 0,
        nextCursor: more ? cursorOf(items[items.length - 1]) : null,
      }
    })
    return { all, urls, fetchPage }
  }

  it('trae tantas conversaciones como estaban cargadas, recorriendo páginas si pasan de 100', async () => {
    const server = fakeServer(180)
    const result = await fetchUpTo(DEFAULT_FILTERS, 150, undefined, server.fetchPage)

    expect(result.items.map((c) => c.id)).toEqual(server.all.slice(0, 150).map((c) => c.id))
    expect(result.hasMore).toBe(true)
    expect(server.urls).toHaveLength(2)
    expect(server.urls[0]).toContain('size=100')
    expect(server.urls[1]).toContain('size=50')
    expect(server.urls[1]).toContain('before=')
  })

  it('con pocas conversaciones basta una petición y no queda "Cargar más"', async () => {
    const server = fakeServer(10)
    const result = await fetchUpTo(DEFAULT_FILTERS, 25, undefined, server.fetchPage)

    expect(result.items).toHaveLength(10)
    expect(result.hasMore).toBe(false)
    expect(server.urls).toHaveLength(1)
  })
})
