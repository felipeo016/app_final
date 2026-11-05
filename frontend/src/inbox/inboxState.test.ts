import { describe, expect, it } from 'vitest'
import type { Conversation } from '../api/types'
import {
  applyConversation,
  DEFAULT_FILTERS,
  filtersToQuery,
  inboxReducer,
  INITIAL_INBOX,
  isUnanswered,
  matchesFilters,
  type InboxFilters,
} from './inboxState'

const ME = 'user-me'

function conversation(id: string, overrides: Partial<Conversation> = {}): Conversation {
  return {
    id,
    contact: { id: `contact-${id}`, name: `Cliente ${id}` },
    channel: 'WHATSAPP',
    status: 'NEW',
    open: true,
    assignedTo: null,
    lastMessagePreview: 'hola',
    lastMessageAt: '2026-11-04T10:00:00Z',
    lastInboundAt: '2026-11-04T10:00:00Z',
    lastOutboundAt: null,
    firstResponseAt: null,
    unreadCount: 1,
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

const filters = (overrides: Partial<InboxFilters>): InboxFilters => ({ ...DEFAULT_FILTERS, ...overrides })

describe('filtros', () => {
  it('arma la consulta solo con los filtros activos', () => {
    expect(filtersToQuery(DEFAULT_FILTERS)).toBe('open=true&size=25')
    expect(filtersToQuery(DEFAULT_FILTERS, 25, '1730000000000_abc')).toBe('open=true&size=25&before=1730000000000_abc')
    expect(filtersToQuery({ ...DEFAULT_FILTERS, status: 'ALL' })).toBe('size=25')
    expect(filtersToQuery(filters({ status: 'NEW', channel: 'FACEBOOK', assignee: 'me', unanswered: true }))).toBe(
      'status=NEW&channel=FACEBOOK&assignee=me&unanswered=true&size=25',
    )
  })

  it('aplica estado, canal y asignado como el backend', () => {
    const mine = conversation('a', { assignedTo: { id: ME, name: 'Yo' }, status: 'IN_PROGRESS', channel: 'INSTAGRAM' })
    expect(matchesFilters(mine, filters({ status: 'IN_PROGRESS' }), ME)).toBe(true)
    expect(matchesFilters(mine, filters({ status: 'NEW' }), ME)).toBe(false)
    expect(matchesFilters(mine, filters({ channel: 'WHATSAPP' }), ME)).toBe(false)
    expect(matchesFilters(mine, filters({ assignee: 'me' }), ME)).toBe(true)
    expect(matchesFilters(mine, filters({ assignee: 'unassigned' }), ME)).toBe(false)
    expect(matchesFilters(mine, filters({ assignee: 'otro' }), ME)).toBe(false)
    expect(matchesFilters(mine, filters({ assignee: ME }), ME)).toBe(true)
    expect(matchesFilters(conversation('b'), filters({ assignee: 'unassigned' }), ME)).toBe(true)
  })

  it('por defecto solo muestra las abiertas; "Todas" incluye las resueltas', () => {
    const resolved = conversation('r', { status: 'RESOLVED', open: false })
    const working = conversation('w', { status: 'IN_PROGRESS' })
    expect(DEFAULT_FILTERS.status).toBe('OPEN')
    expect(matchesFilters(conversation('n'), DEFAULT_FILTERS, ME)).toBe(true)
    expect(matchesFilters(working, DEFAULT_FILTERS, ME)).toBe(true)
    expect(matchesFilters(resolved, DEFAULT_FILTERS, ME)).toBe(false)
    expect(matchesFilters(resolved, filters({ status: 'ALL' }), ME)).toBe(true)
    expect(matchesFilters(resolved, filters({ status: 'RESOLVED' }), ME)).toBe(true)
    expect(matchesFilters(working, filters({ status: 'RESOLVED' }), ME)).toBe(false)
  })

  it('"sin responder": abierta y con mensaje del cliente posterior a la última respuesta', () => {
    expect(isUnanswered(conversation('a'))).toBe(true)
    expect(isUnanswered(conversation('a', { lastOutboundAt: '2026-11-04T10:05:00Z' }))).toBe(false)
    expect(isUnanswered(conversation('a', { lastOutboundAt: '2026-11-04T09:55:00Z' }))).toBe(true)
    expect(isUnanswered(conversation('a', { open: false, status: 'RESOLVED' }))).toBe(false)
    expect(isUnanswered(conversation('a', { lastInboundAt: null }))).toBe(false)
  })
})

describe('aplicar eventos', () => {
  const older = conversation('old', { lastMessageAt: '2026-11-04T08:00:00Z' })
  const newer = conversation('new', { lastMessageAt: '2026-11-04T09:00:00Z' })

  it('agrega una conversación nueva en su lugar por actividad', () => {
    const list = applyConversation([newer, older], conversation('x', { lastMessageAt: '2026-11-04T08:30:00Z' }), true)
    expect(list.map((c) => c.id)).toEqual(['new', 'x', 'old'])
  })

  it('reemplaza la existente y la sube al recibir actividad', () => {
    const updated = { ...older, lastMessageAt: '2026-11-04T11:00:00Z', unreadCount: 3, atRisk: true }
    const list = applyConversation([newer, older], updated, true)
    expect(list.map((c) => c.id)).toEqual(['old', 'new'])
    expect(list[0].unreadCount).toBe(3)
    expect(list[0].atRisk).toBe(true)
  })

  it('quita la conversación si deja de cumplir los filtros', () => {
    expect(applyConversation([newer, older], { ...older, status: 'RESOLVED' }, false).map((c) => c.id)).toEqual([
      'new',
    ])
    const untouched = [newer]
    expect(applyConversation(untouched, older, false)).toBe(untouched)
  })
})

describe('recarga con eventos concurrentes', () => {
  it('reaplica sobre la respuesta los eventos llegados durante la recarga', () => {
    let state = inboxReducer(INITIAL_INBOX, { type: 'load-start', requestId: 1 })
    const fresh = conversation('a', { unreadCount: 5, lastMessageAt: '2026-11-04T12:00:00Z' })
    state = inboxReducer(state, { type: 'event', conversation: fresh, matches: true })
    // La respuesta se calculó antes del evento: trae la versión vieja
    const stale = conversation('a', { unreadCount: 1 })
    state = inboxReducer(state, { type: 'load-success', requestId: 1, conversations: [stale, conversation('b')], hasMore: false })

    expect(state.loading).toBe(false)
    expect(state.buffered).toEqual([])
    expect(state.conversations.find((c) => c.id === 'a')?.unreadCount).toBe(5)
    expect(state.conversations.map((c) => c.id)).toEqual(['a', 'b'])
  })

  it('descarta la respuesta de una recarga anterior', () => {
    let state = inboxReducer(INITIAL_INBOX, { type: 'load-start', requestId: 1 })
    state = inboxReducer(state, { type: 'load-start', requestId: 2 })
    state = inboxReducer(state, { type: 'load-success', requestId: 1, conversations: [conversation('viejo')], hasMore: false })
    expect(state.loading).toBe(true)
    expect(state.conversations).toEqual([])
    state = inboxReducer(state, { type: 'load-success', requestId: 2, conversations: [conversation('nuevo')], hasMore: false })
    expect(state.conversations.map((c) => c.id)).toEqual(['nuevo'])
  })

  it('fuera de una recarga los eventos no se acumulan', () => {
    let state = inboxReducer(INITIAL_INBOX, { type: 'load-start', requestId: 1 })
    state = inboxReducer(state, { type: 'load-success', requestId: 1, conversations: [], hasMore: false })
    state = inboxReducer(state, { type: 'event', conversation: conversation('a'), matches: true })
    expect(state.buffered).toEqual([])
    expect(state.conversations).toHaveLength(1)
  })
})
