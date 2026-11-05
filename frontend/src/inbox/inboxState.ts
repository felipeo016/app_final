import { toMillis, type ChannelType, type Conversation, type ConversationStatus } from '../api/types'

/** Filtros de la bandeja. {@code assignee}: '' = todos, 'me', 'unassigned' o el id de un usuario. */
export interface InboxFilters {
  status: ConversationStatus | ''
  channel: ChannelType | ''
  assignee: string
  unanswered: boolean
}

export const DEFAULT_FILTERS: InboxFilters = { status: '', channel: '', assignee: '', unanswered: false }

/** Parámetros de GET /api/conversations para estos filtros. */
export function filtersToQuery(filters: InboxFilters, size = 100): string {
  const params = new URLSearchParams()
  if (filters.status) params.set('status', filters.status)
  if (filters.channel) params.set('channel', filters.channel)
  if (filters.assignee) params.set('assignee', filters.assignee)
  if (filters.unanswered) params.set('unanswered', 'true')
  params.set('size', String(size))
  return params.toString()
}

/** Igual que el filtro "unanswered" del backend: abierta y con mensaje del cliente posterior a la última respuesta. */
export function isUnanswered(conversation: Conversation): boolean {
  if (!conversation.open) return false
  const inbound = toMillis(conversation.lastInboundAt)
  if (inbound === null) return false
  const outbound = toMillis(conversation.lastOutboundAt)
  return outbound === null || inbound > outbound
}

/**
 * Decide en el cliente si una conversación que llega por WebSocket pertenece a la vista filtrada, con las
 * mismas reglas que aplica el backend al listar.
 */
export function matchesFilters(conversation: Conversation, filters: InboxFilters, currentUserId: string): boolean {
  if (filters.status && conversation.status !== filters.status) return false
  if (filters.channel && conversation.channel !== filters.channel) return false
  const assignee = conversation.assignedTo?.id ?? null
  if (filters.assignee === 'me' && assignee !== currentUserId) return false
  if (filters.assignee === 'unassigned' && assignee !== null) return false
  if (filters.assignee && filters.assignee !== 'me' && filters.assignee !== 'unassigned' && assignee !== filters.assignee) {
    return false
  }
  if (filters.unanswered && !isUnanswered(conversation)) return false
  return true
}

/** Mismo orden que el backend: actividad más reciente primero; a igualdad, la creada más tarde. */
export function compareConversations(a: Conversation, b: Conversation): number {
  const byActivity = (toMillis(b.lastMessageAt) ?? 0) - (toMillis(a.lastMessageAt) ?? 0)
  if (byActivity !== 0) return byActivity
  return (toMillis(b.createdAt) ?? 0) - (toMillis(a.createdAt) ?? 0)
}

/**
 * Aplica la conversación de un evento a la lista: la reemplaza (o la agrega) si cumple los filtros y la
 * quita si dejó de cumplirlos. Devuelve una lista nueva ordenada.
 */
export function applyConversation(list: Conversation[], conversation: Conversation, matches: boolean): Conversation[] {
  const others = list.filter((item) => item.id !== conversation.id)
  if (!matches) {
    return others.length === list.length ? list : others
  }
  return [...others, conversation].sort(compareConversations)
}

// --- estado con recarga y eventos concurrentes ---

interface BufferedEvent {
  conversation: Conversation
  matches: boolean
}

export interface InboxState {
  conversations: Conversation[]
  loading: boolean
  error: string | null
  /** Eventos llegados mientras se recargaba la lista: se vuelven a aplicar sobre la respuesta. */
  buffered: BufferedEvent[]
  /** Número de la recarga en curso; descarta respuestas de recargas anteriores. */
  requestId: number
}

export const INITIAL_INBOX: InboxState = { conversations: [], loading: true, error: null, buffered: [], requestId: 0 }

export type InboxAction =
  | { type: 'load-start'; requestId: number }
  | { type: 'load-success'; requestId: number; conversations: Conversation[] }
  | { type: 'load-error'; requestId: number; error: string }
  | { type: 'event'; conversation: Conversation; matches: boolean }

export function inboxReducer(state: InboxState, action: InboxAction): InboxState {
  switch (action.type) {
    case 'load-start':
      return { ...state, loading: true, error: null, buffered: [], requestId: action.requestId }
    case 'load-success': {
      if (action.requestId !== state.requestId) return state
      // La respuesta puede ser anterior a eventos ya recibidos: se reaplican encima, en orden de llegada.
      const conversations = state.buffered.reduce(
        (list, event) => applyConversation(list, event.conversation, event.matches),
        [...action.conversations].sort(compareConversations),
      )
      return { ...state, conversations, loading: false, buffered: [] }
    }
    case 'load-error':
      if (action.requestId !== state.requestId) return state
      return { ...state, loading: false, error: action.error, buffered: [] }
    case 'event': {
      const conversations = applyConversation(state.conversations, action.conversation, action.matches)
      const buffered = state.loading
        ? [...state.buffered, { conversation: action.conversation, matches: action.matches }]
        : state.buffered
      return { ...state, conversations, buffered }
    }
  }
}
