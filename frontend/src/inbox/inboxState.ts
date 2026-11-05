import { toMillis, type ChannelType, type Conversation, type ConversationStatus } from '../api/types'

/** Conversaciones por página de la bandeja ("Cargar más" pide la siguiente). */
export const INBOX_PAGE_SIZE = 25

/**
 * Estado que muestra la bandeja: 'OPEN' (por defecto: Nuevo y En proceso), 'ALL' (también las resueltas) o un
 * estado concreto. Las resueltas siguen visibles en el historial del perfil del cliente.
 */
export type StatusFilter = 'OPEN' | 'ALL' | ConversationStatus

/** Filtros de la bandeja. {@code assignee}: '' = todos, 'me', 'unassigned' o el id de un usuario. */
export interface InboxFilters {
  status: StatusFilter
  channel: ChannelType | ''
  assignee: string
  unanswered: boolean
}

export const DEFAULT_FILTERS: InboxFilters = { status: 'OPEN', channel: '', assignee: '', unanswered: false }

/** Parámetros de GET /api/conversations para estos filtros (y, si se indica, el cursor de "Cargar más"). */
export function filtersToQuery(filters: InboxFilters, size = INBOX_PAGE_SIZE, before?: string | null): string {
  const params = new URLSearchParams()
  if (filters.status === 'OPEN') params.set('open', 'true')
  else if (filters.status !== 'ALL') params.set('status', filters.status)
  if (filters.channel) params.set('channel', filters.channel)
  if (filters.assignee) params.set('assignee', filters.assignee)
  if (filters.unanswered) params.set('unanswered', 'true')
  params.set('size', String(size))
  if (before) params.set('before', before)
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
  if (filters.status === 'OPEN' && !conversation.open) return false
  if (filters.status !== 'OPEN' && filters.status !== 'ALL' && conversation.status !== filters.status) return false
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

/**
 * Mismo orden que el backend: actividad más reciente primero y, a igual hora, el id mayor primero (orden
 * total y estable, imprescindible para paginar por cursor). Las conversaciones sin actividad van al final.
 */
export function compareConversations(a: Conversation, b: Conversation): number {
  const timeA = toMillis(a.lastMessageAt)
  const timeB = toMillis(b.lastMessageAt)
  if (timeA !== timeB) {
    if (timeA === null) return 1
    if (timeB === null) return -1
    return timeB - timeA
  }
  return a.id < b.id ? 1 : a.id > b.id ? -1 : 0
}

/** Cursor de "Cargar más": la posición de la última conversación cargada ("{ms}_{id}", como el backend). */
export function cursorOf(conversation: Conversation): string {
  const millis = toMillis(conversation.lastMessageAt)
  return `${millis === null ? '' : millis}_${conversation.id}`
}

/**
 * Aplica la conversación de un evento a la lista: la reemplaza (o la agrega) si cumple los filtros y la
 * quita si dejó de cumplirlos.
 *
 * Con páginas pendientes de cargar ({@code hasMore}), una conversación NUEVA para la lista que en el orden va
 * después de la última cargada no se inserta: quedaría separada del resto por conversaciones aún no cargadas, y
 * el cursor de "Cargar más" se saltaría ese tramo. Llegará con su página.
 */
export function applyConversation(
  list: Conversation[],
  conversation: Conversation,
  matches: boolean,
  hasMore = false,
): Conversation[] {
  const present = list.some((item) => item.id === conversation.id)
  const others = present ? list.filter((item) => item.id !== conversation.id) : list
  if (!matches) {
    return present ? others : list
  }
  const last = others[others.length - 1]
  if (!present && hasMore && last && compareConversations(conversation, last) > 0) {
    return list
  }
  return [...others, conversation].sort(compareConversations)
}

/** Agrega una página a la lista sin repetir: lo ya cargado (quizá actualizado por eventos) se conserva. */
export function appendPage(list: Conversation[], page: Conversation[]): Conversation[] {
  const known = new Set(list.map((item) => item.id))
  return [...list, ...page.filter((item) => !known.has(item.id))].sort(compareConversations)
}

// --- estado con recarga, "Cargar más" y eventos concurrentes ---

interface BufferedEvent {
  conversation: Conversation
  matches: boolean
}

export interface InboxState {
  conversations: Conversation[]
  loading: boolean
  loadingMore: boolean
  /** Hay más conversaciones en el servidor después de la última cargada. */
  hasMore: boolean
  error: string | null
  /** Eventos llegados mientras se pedía la lista o una página más: se vuelven a aplicar sobre la respuesta. */
  buffered: BufferedEvent[]
  /** Número de la petición en curso; descarta respuestas de peticiones anteriores. */
  requestId: number
}

export const INITIAL_INBOX: InboxState = {
  conversations: [],
  loading: true,
  loadingMore: false,
  hasMore: false,
  error: null,
  buffered: [],
  requestId: 0,
}

export type InboxAction =
  | { type: 'load-start'; requestId: number }
  | { type: 'load-success'; requestId: number; conversations: Conversation[]; hasMore: boolean }
  | { type: 'load-error'; requestId: number; error: string }
  | { type: 'more-start'; requestId: number }
  | { type: 'more-success'; requestId: number; conversations: Conversation[]; hasMore: boolean }
  | { type: 'more-error'; requestId: number; error: string }
  | { type: 'event'; conversation: Conversation; matches: boolean }

function replay(list: Conversation[], buffered: BufferedEvent[], hasMore: boolean): Conversation[] {
  return buffered.reduce((acc, event) => applyConversation(acc, event.conversation, event.matches, hasMore), list)
}

export function inboxReducer(state: InboxState, action: InboxAction): InboxState {
  switch (action.type) {
    case 'load-start':
      // Una recarga deja sin efecto cualquier "Cargar más" en curso (sus respuestas se descartan)
      return { ...state, loading: true, loadingMore: false, error: null, buffered: [], requestId: action.requestId }
    case 'load-success': {
      if (action.requestId !== state.requestId) return state
      // La respuesta puede ser anterior a eventos ya recibidos: se reaplican encima, en orden de llegada.
      const fresh = [...action.conversations].sort(compareConversations)
      return {
        ...state,
        conversations: replay(fresh, state.buffered, action.hasMore),
        hasMore: action.hasMore,
        loading: false,
        buffered: [],
      }
    }
    case 'load-error':
      if (action.requestId !== state.requestId) return state
      return { ...state, loading: false, error: action.error, buffered: [] }
    case 'more-start':
      if (state.loading) return state
      return { ...state, loadingMore: true, error: null, buffered: [], requestId: action.requestId }
    case 'more-success': {
      if (action.requestId !== state.requestId) return state
      const merged = appendPage(state.conversations, action.conversations)
      return {
        ...state,
        conversations: replay(merged, state.buffered, action.hasMore),
        hasMore: action.hasMore,
        loadingMore: false,
        buffered: [],
      }
    }
    case 'more-error':
      if (action.requestId !== state.requestId) return state
      return { ...state, loadingMore: false, error: action.error, buffered: [] }
    case 'event': {
      const conversations = applyConversation(state.conversations, action.conversation, action.matches, state.hasMore)
      const buffered =
        state.loading || state.loadingMore
          ? [...state.buffered, { conversation: action.conversation, matches: action.matches }]
          : state.buffered
      return { ...state, conversations, buffered }
    }
  }
}
