import { useCallback, useEffect, useReducer, useRef, useState } from 'react'
import type { Client } from '@stomp/stompjs'
import { ApiError, apiFetch } from '../api/http'
import type { Conversation, Page, RealtimeEvent } from '../api/types'
import type { Session } from '../auth/session'
import { connectRealtime, type ConnectionState } from '../realtime/realtimeClient'
import { filtersToQuery, inboxReducer, INITIAL_INBOX, matchesFilters, type InboxFilters } from './inboxState'

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

  const reload = useCallback(() => {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller
    const requestId = ++requestSeq.current
    dispatch({ type: 'load-start', requestId })
    apiFetch<Page<Conversation>>(`/api/conversations?${filtersToQuery(filtersRef.current)}`, {
      signal: controller.signal,
    })
      .then((page) => dispatch({ type: 'load-success', requestId, conversations: page.items }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        const message = error instanceof ApiError ? error.message : 'No se pudo cargar la bandeja'
        dispatch({ type: 'load-error', requestId, error: message })
      })
  }, [])

  // Recarga al montar y al cambiar los filtros
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
        reload()
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

  return { ...state, connection, connectionSeq, reload, subscribe }
}

export type RealtimeListener = (event: RealtimeEvent) => void
export type SubscribeToRealtime = (listener: RealtimeListener) => () => void
