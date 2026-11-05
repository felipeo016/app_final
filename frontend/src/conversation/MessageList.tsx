import { useLayoutEffect, useRef, useState } from 'react'
import { toMillis, UNCONFIRMED_SEND, type Message } from '../api/types'
import { DELIVERY_LABELS } from './messages'

interface Props {
  messages: Message[]
  hasOlder: boolean
  loadingOlder: boolean
  onLoadOlder: () => void
  onRetry: (messageId: string) => Promise<void>
  /** Nombre de quien escribió (agente) a partir de su id. */
  authorName: (userId: string | null) => string
}

const NEAR_TOP_PX = 80
const NEAR_BOTTOM_PX = 120

/**
 * Historial del chat. Al subir hasta arriba pide la página anterior y conserva la posición de lectura;
 * al llegar un mensaje nuevo baja al final solo si el agente ya estaba abajo (no le mueve la lectura).
 */
export default function MessageList({ messages, hasOlder, loadingOlder, onLoadOlder, onRetry, authorName }: Props) {
  const scrollRef = useRef<HTMLDivElement>(null)
  const previous = useRef({ firstId: '', lastId: '', scrollHeight: 0, nearBottom: true })

  // Ajuste de scroll después de cada cambio de la lista (antes de pintar, sin parpadeo)
  useLayoutEffect(() => {
    const element = scrollRef.current
    if (!element) return
    const firstId = messages[0]?.id ?? ''
    const lastId = messages[messages.length - 1]?.id ?? ''
    const before = previous.current
    if (before.firstId && firstId !== before.firstId && lastId === before.lastId) {
      // Se agregaron mensajes antiguos arriba: mantener a la vista el mismo mensaje
      element.scrollTop += element.scrollHeight - before.scrollHeight
    } else if (
      lastId !== before.lastId &&
      // Abajo si ya estaba abajo, en la primera carga, o si el mensaje nuevo lo escribió un agente
      (before.nearBottom || !before.lastId || messages[messages.length - 1]?.direction === 'OUTBOUND')
    ) {
      element.scrollTop = element.scrollHeight
    }
    previous.current = {
      firstId,
      lastId,
      scrollHeight: element.scrollHeight,
      nearBottom: element.scrollHeight - element.scrollTop - element.clientHeight < NEAR_BOTTOM_PX,
    }
  }, [messages])

  function handleScroll() {
    const element = scrollRef.current
    if (!element) return
    previous.current.nearBottom = element.scrollHeight - element.scrollTop - element.clientHeight < NEAR_BOTTOM_PX
    previous.current.scrollHeight = element.scrollHeight
    if (element.scrollTop < NEAR_TOP_PX && hasOlder && !loadingOlder) onLoadOlder()
  }

  return (
    <div className="message-list" ref={scrollRef} onScroll={handleScroll}>
      {hasOlder ? (
        <button type="button" className="load-older" onClick={onLoadOlder} disabled={loadingOlder}>
          {loadingOlder ? 'Cargando mensajes anteriores…' : 'Ver mensajes anteriores'}
        </button>
      ) : (
        messages.length > 0 && <p className="history-start">Inicio de la conversación</p>
      )}
      {messages.map((message) => (
        <MessageBubble key={message.id} message={message} onRetry={onRetry} authorName={authorName} />
      ))}
    </div>
  )
}

function time(message: Message): string {
  const millis = toMillis(message.timestamp)
  if (millis === null) return ''
  return new Date(millis).toLocaleString('es', { hour: '2-digit', minute: '2-digit', day: 'numeric', month: 'short' })
}

interface BubbleProps {
  message: Message
  onRetry: (messageId: string) => Promise<void>
  authorName: (userId: string | null) => string
}

function MessageBubble({ message, onRetry, authorName }: BubbleProps) {
  const [retrying, setRetrying] = useState(false)
  const [confirming, setConfirming] = useState(false)

  if (message.type === 'INTERNAL_NOTE') {
    return (
      <div className="bubble-row note-row">
        <div className="bubble note">
          <span className="note-label">Nota interna, el cliente no la ve</span>
          <p className="bubble-text">{message.content}</p>
          <span className="bubble-meta">
            {authorName(message.sentBy)} · {time(message)}
          </span>
        </div>
      </div>
    )
  }

  const outbound = message.direction === 'OUTBOUND'
  const failed = message.deliveryStatus === 'FAILED'
  // Sin confirmación del canal el cliente pudo recibirlo: reenviar podría duplicarlo, así que se pregunta antes
  const unconfirmed = failed && message.failureCode === UNCONFIRMED_SEND

  async function retry() {
    setConfirming(false)
    setRetrying(true)
    try {
      await onRetry(message.id)
    } finally {
      setRetrying(false)
    }
  }

  return (
    <div className={`bubble-row ${outbound ? 'outbound-row' : 'inbound-row'}`}>
      <div className={`bubble ${outbound ? 'outbound' : 'inbound'}${failed ? ' failed' : ''}`}>
        <p className="bubble-text">{message.content}</p>
        <span className="bubble-meta">
          {outbound && <>{authorName(message.sentBy)} · </>}
          {time(message)}
          {outbound && message.deliveryStatus && !failed && (
            <span className={`delivery delivery-${message.deliveryStatus.toLowerCase()}`}>
              {' · '}
              {DELIVERY_LABELS[message.deliveryStatus]}
              {message.deliveryStatus === 'SENT' && ' ✓'}
              {(message.deliveryStatus === 'DELIVERED' || message.deliveryStatus === 'READ') && ' ✓✓'}
            </span>
          )}
        </span>
        {failed && !confirming && (
          <div className="failure" role="alert">
            <span>
              {unconfirmed
                ? message.failureReason
                : `${DELIVERY_LABELS.FAILED}: ${message.failureReason ?? 'el canal no pudo entregarlo'}`}
            </span>
            <button
              type="button"
              className="btn-retry"
              onClick={() => (unconfirmed ? setConfirming(true) : void retry())}
              disabled={retrying}
            >
              {retrying ? 'Reintentando…' : 'Reintentar'}
            </button>
          </div>
        )}
        {failed && confirming && (
          <div className="retry-confirm" role="alertdialog" aria-label="Confirmar reenvío">
            <p>Puede que el cliente ya haya recibido este mensaje. Si lo reenvías, podría llegarle dos veces.</p>
            <div className="retry-confirm-actions">
              <button type="button" className="btn-retry" onClick={() => void retry()}>
                Sí, reenviar
              </button>
              <button type="button" className="btn-secondary" onClick={() => setConfirming(false)}>
                Cancelar
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
