import { useCallback, useEffect, useRef, useState } from 'react'
import { apiFetch } from '../api/http'
import type { Conversation, ConversationStatus, Message, Page } from '../api/types'
import type { SubscribeToRealtime } from '../inbox/useInbox'
import { mergeMessages } from './messages'

export const PAGE_SIZE = 30

export interface ConversationData {
  conversation: Conversation | null
  messages: Message[]
  loading: boolean
  loadingOlder: boolean
  hasOlder: boolean
  error: unknown
}

const INITIAL: ConversationData = {
  conversation: null,
  messages: [],
  loading: true,
  loadingOlder: false,
  hasOlder: false,
  error: null,
}

const isAbort = (error: unknown) => error instanceof DOMException && error.name === 'AbortError'

/**
 * Datos del chat abierto (una instancia por chat: ConversationView se monta con {@code key} = id):
 * - al abrirlo y en cada reconexión del WebSocket pide la conversación y la última página de mensajes, y
 *   lo marca como leído;
 * - {@code loadOlder} trae la página anterior (scroll hacia arriba);
 * - aplica en tiempo real los eventos de ESTE chat: mensajes nuevos, estados de entrega y cambios de la
 *   conversación. Si llega un mensaje del cliente con el chat a la vista, se vuelve a marcar como leído.
 */
export function useConversation(conversationId: string, subscribe: SubscribeToRealtime, connectionSeq: number) {
  const [data, setData] = useState<ConversationData>(INITIAL)
  const pagesLoaded = useRef(0)

  const markRead = useCallback(() => {
    apiFetch<Conversation>(`/api/conversations/${conversationId}/read`, { method: 'POST' })
      .then((conversation) => setData((d) => ({ ...d, conversation })))
      .catch(() => {
        // No poder marcar como leído no debe interrumpir la atención
      })
  }, [conversationId])

  // Carga inicial y recarga en cada reconexión (sin perder las páginas antiguas ya cargadas)
  useEffect(() => {
    const controller = new AbortController()
    const signal = controller.signal
    Promise.all([
      apiFetch<Conversation>(`/api/conversations/${conversationId}`, { signal }),
      apiFetch<Page<Message>>(`/api/conversations/${conversationId}/messages?page=0&size=${PAGE_SIZE}`, { signal }),
    ])
      .then(([conversation, page]) => {
        if (pagesLoaded.current === 0) pagesLoaded.current = 1
        setData((d) => ({
          ...d,
          conversation,
          messages: mergeMessages(d.messages, page.items),
          hasOlder: d.messages.length > 0 ? d.hasOlder : page.totalPages > 1,
          loading: false,
          error: null,
        }))
        markRead()
      })
      .catch((error: unknown) => {
        if (!isAbort(error)) setData((d) => ({ ...d, loading: false, error }))
      })
    return () => controller.abort()
  }, [conversationId, connectionSeq, markRead])

  const loadOlder = useCallback(async () => {
    if (data.loadingOlder || !data.hasOlder) return
    setData((d) => ({ ...d, loadingOlder: true }))
    try {
      const page = await apiFetch<Page<Message>>(
        `/api/conversations/${conversationId}/messages?page=${pagesLoaded.current}&size=${PAGE_SIZE}`,
      )
      pagesLoaded.current += 1
      setData((d) => ({
        ...d,
        messages: mergeMessages(d.messages, page.items),
        hasOlder: pagesLoaded.current < page.totalPages,
        loadingOlder: false,
      }))
    } catch (error) {
      setData((d) => ({ ...d, loadingOlder: false, error }))
    }
  }, [conversationId, data.loadingOlder, data.hasOlder])

  // Tiempo real del chat abierto
  useEffect(
    () =>
      subscribe((event) => {
        if (event.conversationId !== conversationId) return
        setData((d) => ({
          ...d,
          conversation: event.conversation,
          messages: event.message ? mergeMessages(d.messages, [event.message]) : d.messages,
        }))
        const fromCustomer = event.type === 'MESSAGE_CREATED' && event.message?.direction === 'INBOUND'
        if (fromCustomer && document.visibilityState === 'visible') markRead()
      }),
    [subscribe, conversationId, markRead],
  )

  /** Aplica la respuesta de una acción propia (también llegará por WebSocket; se deduplica). */
  const applyMessage = useCallback((message: Message) => {
    setData((d) => ({ ...d, messages: mergeMessages(d.messages, [message]) }))
  }, [])

  const send = useCallback(
    async (text: string, asNote: boolean) => {
      const path = asNote ? 'notes' : 'messages'
      applyMessage(
        await apiFetch<Message>(`/api/conversations/${conversationId}/${path}`, { method: 'POST', body: { text } }),
      )
    },
    [conversationId, applyMessage],
  )

  const retry = useCallback(
    async (messageId: string) => {
      applyMessage(
        await apiFetch<Message>(`/api/conversations/${conversationId}/messages/${messageId}/retry`, {
          method: 'POST',
        }),
      )
    },
    [conversationId, applyMessage],
  )

  const changeStatus = useCallback(
    async (status: ConversationStatus) => {
      const conversation = await apiFetch<Conversation>(`/api/conversations/${conversationId}/status`, {
        method: 'PUT',
        body: { status },
      })
      setData((d) => ({ ...d, conversation }))
    },
    [conversationId],
  )

  /** {@code agentId} nulo = dejar sin asignar. Las reglas (OWNER / AGENT) las aplica el backend. */
  const assign = useCallback(
    async (agentId: string | null) => {
      const conversation = await apiFetch<Conversation>(`/api/conversations/${conversationId}/assignee`, {
        method: 'PUT',
        body: { agentId },
      })
      setData((d) => ({ ...d, conversation }))
    },
    [conversationId],
  )

  const markSale = useCallback(
    async (isSale: boolean, saleAmount: number | null) => {
      const conversation = await apiFetch<Conversation>(`/api/conversations/${conversationId}/sale`, {
        method: 'PATCH',
        body: { isSale, saleAmount },
      })
      setData((d) => ({ ...d, conversation }))
    },
    [conversationId],
  )

  return { ...data, loadOlder, send, retry, changeStatus, assign, markSale }
}

export type ConversationChat = ReturnType<typeof useConversation>
