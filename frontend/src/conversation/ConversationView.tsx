import { useCallback, useEffect, useState } from 'react'
import { friendlyError } from '../api/errors'
import type { AssignableUser, Conversation } from '../api/types'
import { CHANNEL_LABELS, channelClass, STATUS_LABELS, statusClass } from '../inbox/labels'
import type { SubscribeToRealtime } from '../inbox/useInbox'
import Composer from './Composer'
import MessageList from './MessageList'
import { formatRemaining, windowInfo } from './messages'
import { useQuickReplies } from './quickReplies'
import { useConversation } from './useConversation'

interface Props {
  conversationId: string
  currentUserId: string
  users: AssignableUser[]
  subscribe: SubscribeToRealtime
  connectionSeq: number
}

const WINDOW_CLOSED_REASON =
  'La ventana de 24 h de WhatsApp está cerrada: pasaron más de 24 horas desde el último mensaje del cliente, y WhatsApp solo permite plantillas aprobadas hasta que vuelva a escribir.'
const RESOLVED_REASON = 'La conversación está resuelta. Reábrela para responder al cliente.'

/** Hora actual renovada cada {@code intervalMs}, para el contador de la ventana. */
function useNow(intervalMs: number): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs)
    return () => window.clearInterval(timer)
  }, [intervalMs])
  return now
}

export default function ConversationView({ conversationId, currentUserId, users, subscribe, connectionSeq }: Props) {
  const chat = useConversation(conversationId, subscribe, connectionSeq)
  const quickReplies = useQuickReplies()
  const [actionError, setActionError] = useState<string | null>(null)
  const now = useNow(15_000)

  const authorName = useCallback(
    (userId: string | null) => {
      if (!userId) return 'Agente'
      if (userId === currentUserId) return 'Tú'
      return users.find((user) => user.id === userId)?.name ?? 'Agente'
    },
    [users, currentUserId],
  )

  const runAction = useCallback(async (action: () => Promise<void>) => {
    setActionError(null)
    try {
      await action()
    } catch (error) {
      setActionError(friendlyError(error))
    }
  }, [])

  if (chat.loading && !chat.conversation) {
    return <div className="placeholder">Cargando conversación…</div>
  }
  if (!chat.conversation) {
    return (
      <div className="placeholder" role="alert">
        {friendlyError(chat.error)}
      </div>
    )
  }

  const conversation = chat.conversation
  const window24 = windowInfo(conversation.channel, conversation.replyWindowExpiresAt, conversation.replyWindowOpen, now)
  const replyBlockedReason = !conversation.open ? RESOLVED_REASON : !window24.open ? WINDOW_CLOSED_REASON : null

  return (
    <div className="conversation">
      <ConversationHeader
        conversation={conversation}
        onChangeStatus={(status) => runAction(() => chat.changeStatus(status))}
      />
      {window24.applies && <WindowBanner open={window24.open} remainingMs={window24.remainingMs} />}
      {actionError && (
        <p className="action-error" role="alert">
          {actionError}
          <button type="button" className="btn-link" onClick={() => setActionError(null)}>
            Cerrar
          </button>
        </p>
      )}
      <MessageList
        messages={chat.messages}
        hasOlder={chat.hasOlder}
        loadingOlder={chat.loadingOlder}
        onLoadOlder={() => void chat.loadOlder()}
        onRetry={(messageId) => runAction(() => chat.retry(messageId))}
        authorName={authorName}
      />
      <Composer
        replyBlockedReason={replyBlockedReason}
        quickReplies={quickReplies.replies}
        onOpenQuickReplies={quickReplies.refresh}
        onSend={chat.send}
      />
    </div>
  )
}

function ConversationHeader({
  conversation,
  onChangeStatus,
}: {
  conversation: Conversation
  onChangeStatus: (status: 'RESOLVED' | 'IN_PROGRESS') => Promise<void>
}) {
  const [busy, setBusy] = useState(false)
  async function change(status: 'RESOLVED' | 'IN_PROGRESS') {
    setBusy(true)
    try {
      await onChangeStatus(status)
    } finally {
      setBusy(false)
    }
  }

  return (
    <header className="conversation-header">
      <div className="conversation-title">
        <h2>{conversation.contact.name ?? 'Cliente'}</h2>
        <div className="item-tags">
          <span className={channelClass(conversation.channel)}>{CHANNEL_LABELS[conversation.channel]}</span>
          <span className={statusClass(conversation.status)}>{STATUS_LABELS[conversation.status]}</span>
          <span className="item-assignee">
            {conversation.assignedTo ? `Asignado a ${conversation.assignedTo.name ?? 'un agente'}` : 'Sin asignar'}
          </span>
        </div>
      </div>
      {conversation.open ? (
        <button type="button" className="btn-secondary" disabled={busy} onClick={() => void change('RESOLVED')}>
          Marcar como resuelta
        </button>
      ) : (
        <button type="button" className="btn-secondary" disabled={busy} onClick={() => void change('IN_PROGRESS')}>
          Reabrir
        </button>
      )}
    </header>
  )
}

const WARNING_MS = 60 * 60 * 1000

function WindowBanner({ open, remainingMs }: { open: boolean; remainingMs: number }) {
  if (!open) {
    return (
      <p className="window-banner window-closed" role="status">
        Ventana de 24 h cerrada: no se pueden enviar mensajes libres hasta que el cliente vuelva a escribir.
      </p>
    )
  }
  return (
    <p className={`window-banner${remainingMs < WARNING_MS ? ' window-warning' : ''}`} role="status">
      Ventana de 24 h de WhatsApp: quedan {formatRemaining(remainingMs)} para responder con texto libre.
    </p>
  )
}
