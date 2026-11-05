import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { friendlyError } from '../api/errors'
import { apiFetch } from '../api/http'
import type { ChannelType, Conversation, DeliveryStatus, Message, Page } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { DELIVERY_LABELS } from '../conversation/messages'
import { CHANNEL_LABELS } from '../inbox/labels'
import AppHeader from '../layout/AppHeader'
import { useSimulatorAvailable } from '../layout/useSimulatorAvailable'
import { LiveDemoPanel, RestoreDemoPanel } from './DemoPanels'

/**
 * Herramientas para probar sin canales reales (solo con el backend en perfil dev): un cliente escribe,
 * el canal informa estados de entrega y se fuerzan fallos de envío para probar "Reintentar".
 */
export default function SimulatorPage() {
  const { session, logout } = useAuth()
  const available = useSimulatorAvailable()
  if (!session) return null

  return (
    <div className="app">
      <AppHeader session={session} onLogout={() => logout()} />
      <main className="simulator">
        <h1>Simulador</h1>
        {available === null && <p>Comprobando…</p>}
        {available === false && (
          <p className="alert alert-warning">
            El simulador solo está disponible cuando el backend corre con el perfil <code>dev</code>.
          </p>
        )}
        {available && (
          <>
            <p className="simulator-intro">
              Simula lo que harían los clientes y los canales. Todo ocurre en tu empresa y llega a la{' '}
              <Link to="/">bandeja</Link> en tiempo real, igual que un mensaje real.
            </p>
            <LiveDemoPanel />
            <InboundForm />
            <DeliveryStatusPanel />
            <SendFailuresPanel />
            <RestoreDemoPanel />
          </>
        )}
      </main>
    </div>
  )
}

const CHANNELS: ChannelType[] = ['WHATSAPP', 'INSTAGRAM', 'FACEBOOK', 'SIMULATED']
const randomContact = () => `cliente-${Math.random().toString(36).slice(2, 7)}`

const WHEN_OPTIONS = [
  { value: '0', label: 'Ahora' },
  { value: '20', label: 'Hace 20 minutos (para la alerta de riesgo)' },
  { value: '1500', label: 'Hace 25 horas (ventana de WhatsApp cerrada)' },
]

function InboundForm() {
  const [channel, setChannel] = useState<ChannelType>('WHATSAPP')
  const [contactExternalId, setContactExternalId] = useState(randomContact)
  const [contactName, setContactName] = useState('María López')
  const [text, setText] = useState('Hola, ¿tienen envíos a domicilio?')
  const [minutesAgo, setMinutesAgo] = useState('0')
  const [result, setResult] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [sending, setSending] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSending(true)
    setError(null)
    setResult(null)
    try {
      const response = await apiFetch<{ newConversation: boolean; duplicate: boolean }>('/api/dev/simulate-inbound', {
        method: 'POST',
        body: { channel, contactExternalId, contactName, text, minutesAgo: Number(minutesAgo) },
      })
      setResult(
        response.newConversation
          ? `${contactName || contactExternalId} abrió una conversación nueva por ${CHANNEL_LABELS[channel]}.`
          : `Mensaje agregado a la conversación abierta de ${contactName || contactExternalId}.`,
      )
    } catch (err) {
      setError(friendlyError(err))
    } finally {
      setSending(false)
    }
  }

  return (
    <section className="sim-card">
      <h2>Un cliente escribe</h2>
      <form className="sim-form" onSubmit={submit}>
        <label>
          Canal
          <select value={channel} onChange={(e) => setChannel(e.target.value as ChannelType)}>
            {CHANNELS.map((value) => (
              <option key={value} value={value}>
                {CHANNEL_LABELS[value]}
              </option>
            ))}
          </select>
        </label>
        <label>
          Cliente (teléfono o usuario)
          <span className="sim-inline">
            <input value={contactExternalId} onChange={(e) => setContactExternalId(e.target.value)} required />
            <button type="button" className="btn-secondary" onClick={() => setContactExternalId(randomContact())}>
              Cliente nuevo
            </button>
          </span>
        </label>
        <label>
          Nombre del perfil
          <input value={contactName} onChange={(e) => setContactName(e.target.value)} />
        </label>
        <label>
          Cuándo
          <select value={minutesAgo} onChange={(e) => setMinutesAgo(e.target.value)}>
            {WHEN_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
        <label className="sim-wide">
          Mensaje
          <textarea value={text} rows={2} onChange={(e) => setText(e.target.value)} required />
        </label>
        <div className="sim-actions">
          <button type="submit" className="btn-primary" disabled={sending || !text.trim() || !contactExternalId.trim()}>
            {sending ? 'Enviando…' : 'Recibir mensaje'}
          </button>
          <span className="sim-hint">Repetir el mismo cliente agrega mensajes a su conversación abierta.</span>
        </div>
      </form>
      {result && <p className="alert alert-success">{result}</p>}
      {error && <p className="alert alert-error">{error}</p>}
    </section>
  )
}

const STATUS_ACTIONS: DeliveryStatus[] = ['DELIVERED', 'READ', 'FAILED']

function DeliveryStatusPanel() {
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [conversationId, setConversationId] = useState('')
  const [messages, setMessages] = useState<Message[]>([])
  const [feedback, setFeedback] = useState<string | null>(null)

  useEffect(() => {
    apiFetch<Page<Conversation>>('/api/conversations?size=100')
      .then((page) => setConversations(page.items))
      .catch(() => setConversations([]))
  }, [])

  const loadMessages = useCallback(async (id: string) => {
    if (!id) {
      setMessages([])
      return
    }
    const page = await apiFetch<Page<Message>>(`/api/conversations/${id}/messages?size=50`)
    setMessages(page.items.filter((m) => m.direction === 'OUTBOUND' && m.type === 'TEXT'))
  }, [])

  function selectConversation(id: string) {
    setConversationId(id)
    setFeedback(null)
    loadMessages(id).catch(() => setMessages([]))
  }

  async function apply(message: Message, status: DeliveryStatus) {
    setFeedback(null)
    try {
      const result = await apiFetch<{ applied: boolean }>('/api/dev/simulate-status', {
        method: 'POST',
        body: { messageId: message.id, status },
      })
      setFeedback(
        result.applied
          ? `El canal informó "${DELIVERY_LABELS[status]}".`
          : `Sin cambios: el estado de entrega solo avanza (ya estaba en "${DELIVERY_LABELS[message.deliveryStatus ?? 'SENT']}").`,
      )
      await loadMessages(conversationId)
    } catch (err) {
      setFeedback(friendlyError(err))
    }
  }

  return (
    <section className="sim-card">
      <h2>El canal informa el estado de una respuesta</h2>
      <label className="sim-select">
        Conversación
        <select value={conversationId} onChange={(e) => selectConversation(e.target.value)}>
          <option value="">Elige una conversación…</option>
          {conversations.map((conversation) => (
            <option key={conversation.id} value={conversation.id}>
              {conversation.contact.name ?? 'Cliente'} · {CHANNEL_LABELS[conversation.channel]}
            </option>
          ))}
        </select>
      </label>
      {conversationId && messages.length === 0 && <p className="sim-hint">Esta conversación aún no tiene respuestas.</p>}
      <ul className="sim-messages">
        {messages.map((message) => (
          <li key={message.id}>
            <span className="sim-message-text">{message.content}</span>
            <span className="sim-message-status">
              {message.deliveryStatus ? DELIVERY_LABELS[message.deliveryStatus] : ''}
            </span>
            <span className="sim-message-actions">
              {STATUS_ACTIONS.map((status) => (
                <button
                  key={status}
                  type="button"
                  className="btn-secondary"
                  disabled={!message.deliveryStatus || message.deliveryStatus === 'PENDING'}
                  onClick={() => void apply(message, status)}
                >
                  {DELIVERY_LABELS[status]}
                </button>
              ))}
            </span>
          </li>
        ))}
      </ul>
      {feedback && <p className="sim-hint">{feedback}</p>}
    </section>
  )
}

function SendFailuresPanel() {
  const [count, setCount] = useState(1)
  const [unconfirmed, setUnconfirmed] = useState(false)
  const [pending, setPending] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    apiFetch<{ pendingSendFailures: number }>('/api/dev/status')
      .then((status) => setPending(status.pendingSendFailures))
      .catch(() => setPending(null))
  }, [])

  async function apply(value: number) {
    setError(null)
    try {
      const status = await apiFetch<{ pendingSendFailures: number }>('/api/dev/simulate-send-failures', {
        method: 'POST',
        body: { count: value, unconfirmed },
      })
      setPending(status.pendingSendFailures)
    } catch (err) {
      setError(friendlyError(err))
    }
  }

  return (
    <section className="sim-card">
      <h2>Fallos de envío</h2>
      <p className="sim-hint">
        Hace que las próximas respuestas de tu empresa fallen, para ver el estado "No se envió" y probar el botón
        "Reintentar".
      </p>
      <div className="sim-inline">
        <input
          type="number"
          min={1}
          max={10}
          value={count}
          aria-label="Cantidad de envíos que fallarán"
          onChange={(e) => setCount(Math.min(10, Math.max(1, Number(e.target.value) || 1)))}
        />
        <button type="button" className="btn-primary" onClick={() => void apply(count)}>
          Hacer fallar los próximos envíos
        </button>
        <button type="button" className="btn-secondary" onClick={() => void apply(0)}>
          Cancelar fallos
        </button>
      </div>
      <label className="filter-check">
        <input type="checkbox" checked={unconfirmed} onChange={(e) => setUnconfirmed(e.target.checked)} />
        Simular que el canal no responde (envío sin confirmar: al reintentar se pide confirmación)
      </label>
      {pending !== null && (
        <p className="sim-hint">
          {pending === 0 ? 'Los envíos funcionan con normalidad.' : `Fallarán los próximos ${pending} envíos.`}
        </p>
      )}
      {error && <p className="alert alert-error">{error}</p>}
    </section>
  )
}
