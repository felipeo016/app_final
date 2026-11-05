import { useCallback, useEffect, useReducer, useRef, useState } from 'react'
import type { Client } from '@stomp/stompjs'
import { ApiError, apiFetch } from '../api/http'
import type { Conversation, Page, RealtimeEvent } from '../api/types'
import type { Session } from '../auth/session'
import { connectRealtime, type ConnectionState } from '../realtime/realtimeClient'
import {
  cursorOf,
  filtersToQuery,
  INBOX_PAGE_SIZE,
  inboxReducer,
  INITIAL_INBOX,
  matchesFilters,
  type InboxFilters,
} from './inboxState'

/**
 * Bandeja en tiempo real:
 * - pide la lista al montar, al cambiar los filtros y en CADA conexión del WebSocket (también al reconectar,
 *   para recuperar lo ocurrido mientras estuvo desconectado);
 * - aplica la conversación de cada evento sin volver a pedir la lista;
 * - si el servidor cierra la sesión (token vencido, usuario desactivado) llama a {@code onSessionEnded}.
 */
export function useInbox(session: Session, filters: InboxFilters, onSessionEnded: (notice: string) => void) {
  const [state, dispatch] = useReducer(inboxReducer, INITIAL_INBOX)
  const [connection, setConnection] = useState<ConnectionState>('connecting')
  /** Aumenta en cada conexión del WebSocket: quien muestre datos debe recargarlos (pudo perder eventos). */
  const [connectionSeq, setConnectionSeq] = useState(0)
  const requestSeq = useRef(0)
  const listenersRef = useRef(new Set<RealtimeListener>())
  const abortRef = useRef<AbortController | null>(null)
  // Valores actuales para los callbacks del WebSocket, que viven más que un render
  const filtersRef = useRef(filters)
  const endRef = useRef(onSessionEnded)
  useEffect(() => {
    filtersRef.current = filters
    endRef.current = onSessionEnded
  })

  // Lo cargado ahora mismo, para "Cargar más" (cursor de la última) y para recargar sin perder páginas
  const loadedRef = useRef<Conversation[]>([])
  useEffect(() => {
    loadedRef.current = state.conversations
  }, [state.conversations])

  const startRequest = useCallback(() => {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller
    return { requestId: ++requestSeq.current, signal: controller.signal }
  }, [])

  /**
   * Pide la bandeja desde el principio. Con {@code keepLoaded} (al reconectar) trae tantas conversaciones como
   * había cargadas, recorriendo páginas por cursor si hace falta: el agente no pierde lo que ya había cargado con
   * "Cargar más". Al cambiar los filtros se vuelve a la primera página.
   */
  const reload = useCallback(
    (keepLoaded = false) => {
      const { requestId, signal } = startRequest()
      const target = keepLoaded ? Math.max(INBOX_PAGE_SIZE, loadedRef.current.length) : INBOX_PAGE_SIZE
      dispatch({ type: 'load-start', requestId })
      fetchUpTo(filtersRef.current, target, signal)
        .then(({ items, hasMore }) => dispatch({ type: 'load-success', requestId, conversations: items, hasMore }))
        .catch((error: unknown) => {
          if (isAbort(error)) return
          dispatch({ type: 'load-error', requestId, error: errorText(error) })
        })
    },
    [startRequest],
  )

  /** "Cargar más": la página que sigue a la última conversación cargada. */
  const loadMore = useCallback(() => {
    const last = loadedRef.current[loadedRef.current.length - 1]
    if (!last) return
    const { requestId, signal } = startRequest()
    dispatch({ type: 'more-start', requestId })
    apiFetch<Page<Conversation>>(
      `/api/conversations?${filtersToQuery(filtersRef.current, INBOX_PAGE_SIZE, cursorOf(last))}`,
      { signal },
    )
      .then((page) =>
        dispatch({ type: 'more-success', requestId, conversations: page.items, hasMore: Boolean(page.nextCursor) }),
      )
      .catch((error: unknown) => {
        if (isAbort(error)) return
        dispatch({ type: 'more-error', requestId, error: errorText(error) })
      })
  }, [startRequest])

  // Recarga al montar y al cambiar los filtros (vuelve a la primera página)
  const filtersKey = filtersToQuery(filters)
  useEffect(() => {
    reload()
  }, [filtersKey, reload])

  useEffect(() => () => abortRef.current?.abort(), [])

  // WebSocket del tenant
  useEffect(() => {
    const client: Client = connectRealtime({
      token: session.token,
      tenantId: session.tenant.id,
      onEvent: (event: RealtimeEvent) => {
        if (!event.conversation) return
        dispatch({
          type: 'event',
          conversation: event.conversation,
          matches: matchesFilters(event.conversation, filtersRef.current, session.user.id),
        })
        listenersRef.current.forEach((listener) => listener(event))
      },
      // En cada conexión, incluida la primera: lo que pasó antes de quedar suscritos no llegó por WebSocket.
      // Los eventos que lleguen mientras tanto se reaplican sobre la respuesta (ver inboxReducer).
      onConnected: () => {
        reload(true)
        setConnectionSeq((n) => n + 1)
      },
      onStateChange: setConnection,
      onSessionEnded: (notice) => endRef.current(notice),
    })
    return () => {
      void client.deactivate()
    }
  }, [session.token, session.tenant.id, session.user.id, reload])

  /** Otros componentes (la conversación abierta) reciben los mismos eventos sin abrir otro WebSocket. */
  const subscribe = useCallback((listener: RealtimeListener) => {
    listenersRef.current.add(listener)
    return () => {
      listenersRef.current.delete(listener)
    }
  }, [])

  return { ...state, connection, connectionSeq, reload, loadMore, subscribe }
}

const MAX_PAGE = 100 // máximo que acepta el backend por petición

/** Trae al menos {@code target} conversaciones (o todas si hay menos), página a página por cursor. */
export async function fetchUpTo(
  filters: InboxFilters,
  target: number,
  signal?: AbortSignal,
  fetchPage: (url: string, signal?: AbortSignal) => Promise<Page<Conversation>> = (url, s) =>
    apiFetch<Page<Conversation>>(url, { signal: s }),
): Promise<{ items: Conversation[]; hasMore: boolean }> {
  const items: Conversation[] = []
  let cursor: string | null = null
  let hasMore = false
  do {
    const size = Math.min(MAX_PAGE, Math.max(INBOX_PAGE_SIZE, target - items.length))
    const page: Page<Conversation> = await fetchPage(`/api/conversations?${filtersToQuery(filters, size, cursor)}`, signal)
    items.push(...page.items)
    cursor = page.nextCursor ?? null
    hasMore = cursor !== null
  } while (hasMore && items.length < target)
  return { items, hasMore }
}

const isAbort = (error: unknown) => error instanceof DOMException && error.name === 'AbortError'
const errorText = (error: unknown) => (error instanceof ApiError ? error.message : 'No se pudo cargar la bandeja')

export type RealtimeListener = (event: RealtimeEvent) => void
export type SubscribeToRealtime = (listener: RealtimeListener) => () => void
