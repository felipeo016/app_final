import { useCallback, useEffect, useState } from 'react'
import { useAiStatus } from '../ai/aiStatus'
import { friendlyError } from '../api/errors'
import type { AssignableUser, Conversation } from '../api/types'
import { CHANNEL_LABELS, channelClass, STATUS_LABELS, statusClass } from '../inbox/labels'
import Composer from './Composer'
import MessageList from './MessageList'
import { formatRemaining, windowInfo } from './messages'
import { useQuickReplies } from './quickReplies'
import type { ConversationChat } from './useConversation'

interface Props {
  chat: ConversationChat
  currentUserId: string
  users: AssignableUser[]
  /** Vista de celular: volver a la bandeja y ver el perfil del cliente. */
  onBack: () => void
  onShowProfile: () => void
}

const WINDOW_CLOSED_REASON =
  'La ventana de 24 h de WhatsApp está cerrada: pasaron más de 24 horas desde el último mensaje del cliente, y WhatsApp solo permite plantillas aprobadas hasta que vuelva a escribir.'

/** Hora actual renovada cada {@code intervalMs}, para el contador de la ventana. */
function useNow(intervalMs: number): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs)
    return () => window.clearInterval(timer)
  }, [intervalMs])
  return now
}

export default function ConversationView({ chat, currentUserId, users, onBack, onShowProfile }: Props) {
  const quickReplies = useQuickReplies()
  const ai = useAiStatus()
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
        <button type="button" className="btn-link mobile-only" onClick={onBack}>
          ← Volver a la bandeja
        </button>
      </div>
    )
  }

  const conversation = chat.conversation
  const window24 = windowInfo(conversation.channel, conversation.replyWindowExpiresAt, conversation.replyWindowOpen, now)
  const replyBlockedReason = !window24.open ? WINDOW_CLOSED_REASON : null

  return (
    <div className="conversation">
      <div className="mobile-bar mobile-only">
        <button type="button" className="btn-link" onClick={onBack}>
          ← Bandeja
        </button>
        <button type="button" className="btn-link" onClick={onShowProfile}>
          Perfil del cliente →
        </button>
      </div>
      <ConversationHeader
        conversation={conversation}
        onChangeStatus={(status) => runAction(() => chat.changeStatus(status))}
      />
      {conversation.open && window24.applies && (
        <WindowBanner open={window24.open} remainingMs={window24.remainingMs} />
      )}
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
        aiTyping={chat.aiTyping}
      />
      {conversation.open ? (
        <Composer
          replyBlockedReason={replyBlockedReason}
          quickReplies={quickReplies.replies}
          onOpenQuickReplies={quickReplies.refresh}
          onSend={chat.send}
          onSuggest={ai && ai.mode !== 'OFF' ? chat.suggest : undefined}
        />
      ) : (
        // Conversación resuelta (también al abrirla desde el historial): solo lectura
        <p className="read-only-bar" role="status">
          Conversación resuelta · solo lectura. Usa <strong>Reabrir</strong> si necesitas continuarla.
        </p>
      )}
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
