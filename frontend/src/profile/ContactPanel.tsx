import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { friendlyError } from '../api/errors'
import { apiFetch } from '../api/http'
import { toMillis, type AssignableUser, type ChannelType, type Conversation, type ConversationStatus } from '../api/types'
import type { Session } from '../auth/session'
import type { ConversationChat } from '../conversation/useConversation'
import { avatarHue, CHANNEL_LABELS, channelClass, initials, STATUS_LABELS, statusClass } from '../inbox/labels'
import { assignmentControl, formatCop, formatCopInput, parseCop } from './profileRules'

interface ContactProfile {
  id: string
  name: string
  identities: { channel: ChannelType; externalId: string }[]
  notes: string | null
  createdAt: string
  conversations: {
    id: string
    channel: ChannelType
    status: ConversationStatus
    open: boolean
    lastMessagePreview: string | null
    lastMessageAt: string | null
    createdAt: string
  }[]
}

interface Props {
  chat: ConversationChat
  session: Session
  users: AssignableUser[]
  onOpenConversation: (conversationId: string) => void
  onBackToChat: () => void
}

/** Columna derecha: datos del cliente, controles de la conversación (estado, asignado, venta) e historial. */
export default function ContactPanel({ chat, session, users, onOpenConversation, onBackToChat }: Props) {
  const conversation = chat.conversation
  if (!conversation) {
    return <div className="placeholder">Cargando perfil…</div>
  }
  return (
    <div className="profile">
      <button type="button" className="btn-link mobile-only back-link" onClick={onBackToChat}>
        ← Volver a la conversación
      </button>
      <ContactSection
        // Se recarga cuando cambia el estado de la conversación (el historial lo muestra)
        key={`${conversation.contact.id}:${conversation.status}`}
        contactId={conversation.contact.id}
        currentConversationId={conversation.id}
        onOpenConversation={onOpenConversation}
        // Lo más usado (estado, asignado, venta) va justo debajo de los datos del cliente
        controls={<ConversationControls chat={chat} conversation={conversation} session={session} users={users} />}
      />
    </div>
  )
}

// --- datos del cliente e historial ---

function ContactSection({
  contactId,
  currentConversationId,
  onOpenConversation,
  controls,
}: {
  contactId: string
  currentConversationId: string
  onOpenConversation: (id: string) => void
  controls: ReactNode
}) {
  const [profile, setProfile] = useState<ContactProfile | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    apiFetch<ContactProfile>(`/api/contacts/${contactId}`, { signal: controller.signal })
      .then(setProfile)
      .catch((err: unknown) => {
        if (!(err instanceof DOMException && err.name === 'AbortError')) setError(friendlyError(err))
      })
    return () => controller.abort()
  }, [contactId])

  const save = useCallback(
    async (changes: { name?: string; notes?: string }) => {
      setProfile(await apiFetch<ContactProfile>(`/api/contacts/${contactId}`, { method: 'PATCH', body: changes }))
    },
    [contactId],
  )

  if (error) {
    return (
      <>
        <p className="alert alert-error">{error}</p>
        {controls}
      </>
    )
  }
  if (!profile) {
    return (
      <>
        <p className="profile-loading">Cargando cliente…</p>
        {controls}
      </>
    )
  }

  const history = profile.conversations.filter((item) => item.id !== currentConversationId)

  return (
    <>
      <section className="profile-card profile-contact">
        <span className="avatar avatar-lg" style={{ backgroundColor: `hsl(${avatarHue(profile.id)} 45% 45%)` }}>
          {initials(profile.name)}
        </span>
        <EditableName name={profile.name} onSave={(name) => save({ name })} />
        <ul className="identities" aria-label="Canales del cliente">
          {profile.identities.map((identity) => (
            <li key={`${identity.channel}:${identity.externalId}`}>
              <span className={channelClass(identity.channel)}>{CHANNEL_LABELS[identity.channel]}</span>
              <span className="identity-id">{identity.externalId}</span>
            </li>
          ))}
        </ul>
      </section>

      {controls}

      <section className="profile-card">
        <h3>Notas del cliente</h3>
        <EditableNotes notes={profile.notes ?? ''} onSave={(notes) => save({ notes })} />
      </section>

      <section className="profile-card">
        <h3>Conversaciones anteriores</h3>
        {history.length === 0 ? (
          <p className="profile-muted">Es la primera conversación con este cliente.</p>
        ) : (
          <ul className="history">
            {history.map((item) => (
              <li key={item.id}>
                <button type="button" onClick={() => onOpenConversation(item.id)}>
                  <span className="item-tags">
                    <span className={channelClass(item.channel)}>{CHANNEL_LABELS[item.channel]}</span>
                    <span className={statusClass(item.status)}>{STATUS_LABELS[item.status]}</span>
                    <span className="history-date">{shortDate(item.lastMessageAt ?? item.createdAt)}</span>
                  </span>
                  <span className="history-preview">{item.lastMessagePreview ?? 'Sin mensajes'}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  )
}

function shortDate(value: string): string {
  const millis = toMillis(value)
  return millis === null ? '' : new Date(millis).toLocaleDateString('es-CO', { day: 'numeric', month: 'short', year: 'numeric' })
}

function EditableName({ name, onSave }: { name: string; onSave: (name: string) => Promise<void> }) {
  const [editing, setEditing] = useState(false)
  const [value, setValue] = useState(name)
  const [error, setError] = useState<string | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!value.trim()) {
      setError('El nombre no puede quedar vacío')
      return
    }
    try {
      await onSave(value.trim())
      setEditing(false)
      setError(null)
    } catch (err) {
      setError(friendlyError(err))
    }
  }

  if (!editing) {
    return (
      <div className="contact-name">
        <h2>{name}</h2>
        <button
          type="button"
          className="btn-link"
          onClick={() => {
            setValue(name)
            setEditing(true)
          }}
        >
          Editar nombre
        </button>
      </div>
    )
  }
  return (
    <form className="inline-form" onSubmit={submit}>
      <input aria-label="Nombre del cliente" value={value} maxLength={100} onChange={(e) => setValue(e.target.value)} autoFocus />
      <div className="inline-actions">
        <button type="submit" className="btn-primary btn-small">
          Guardar
        </button>
        <button type="button" className="btn-secondary btn-small" onClick={() => setEditing(false)}>
          Cancelar
        </button>
      </div>
      {error && <p className="field-error">{error}</p>}
    </form>
  )
}

function EditableNotes({ notes, onSave }: { notes: string; onSave: (notes: string) => Promise<void> }) {
  const [value, setValue] = useState(notes)
  const [state, setState] = useState<'idle' | 'saving' | 'saved'>('idle')
  const [error, setError] = useState<string | null>(null)
  const dirty = value.trim() !== notes.trim()

  async function save() {
    setState('saving')
    setError(null)
    try {
      await onSave(value.trim())
      setState('saved')
    } catch (err) {
      setError(friendlyError(err))
      setState('idle')
    }
  }

  return (
    <div className="notes">
      <textarea
        aria-label="Notas del cliente"
        placeholder="Preferencias, datos útiles para el equipo…"
        value={value}
        maxLength={2000}
        rows={3}
        onChange={(e) => {
          setValue(e.target.value)
          setState('idle')
        }}
      />
      <div className="inline-actions">
        <button type="button" className="btn-secondary btn-small" disabled={!dirty || state === 'saving'} onClick={() => void save()}>
          {state === 'saving' ? 'Guardando…' : 'Guardar notas'}
        </button>
        {state === 'saved' && !dirty && <span className="profile-muted">Guardado</span>}
      </div>
      {error && <p className="field-error">{error}</p>}
    </div>
  )
}

// --- estado, asignación y venta de la conversación abierta ---

function ConversationControls({
  chat,
  conversation,
  session,
  users,
}: {
  chat: ConversationChat
  conversation: Conversation
  session: Session
  users: AssignableUser[]
}) {
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function run(action: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (err) {
      setError(friendlyError(err))
    } finally {
      setBusy(false)
    }
  }

  const control = assignmentControl(session.user.role, session.user.id, conversation)

  return (
    <section className="profile-card">
      <h3>Esta conversación</h3>
      {error && (
        <p className="alert alert-error" role="alert">
          {error}
        </p>
      )}

      <div className="control-row">
        <span className="control-label">Estado</span>
        {conversation.open ? (
          // Resolver y reabrir están solo en el encabezado del chat
          <select
            aria-label="Estado de la conversación"
            value={conversation.status}
            disabled={busy}
            onChange={(e) => void run(() => chat.changeStatus(e.target.value as ConversationStatus))}
          >
            <option value="NEW">{STATUS_LABELS.NEW}</option>
            <option value="IN_PROGRESS">{STATUS_LABELS.IN_PROGRESS}</option>
          </select>
        ) : (
          <span className={statusClass(conversation.status)}>{STATUS_LABELS[conversation.status]}</span>
        )}
      </div>

      <div className="control-row">
        <span className="control-label">Asignado</span>
        {control.kind === 'select' && (
          <select
            aria-label="Agente asignado"
            value={conversation.assignedTo?.id ?? ''}
            disabled={busy}
            onChange={(e) => void run(() => chat.assign(e.target.value || null))}
          >
            <option value="">Sin asignar</option>
            {users.map((user) => (
              <option key={user.id} value={user.id}>
                {user.name}
                {user.id === session.user.id ? ' (tú)' : ''}
              </option>
            ))}
            {conversation.assignedTo && !users.some((user) => user.id === conversation.assignedTo?.id) && (
              <option value={conversation.assignedTo.id}>{conversation.assignedTo.name ?? 'Agente'}</option>
            )}
          </select>
        )}
        {control.kind === 'take' && (
          <button type="button" className="btn-secondary" disabled={busy} onClick={() => void run(() => chat.assign(session.user.id))}>
            Tomar este chat
          </button>
        )}
        {control.kind === 'release' && (
          <span className="control-inline">
            <span>Tú</span>
            <button type="button" className="btn-secondary" disabled={busy} onClick={() => void run(() => chat.assign(null))}>
              Soltar este chat
            </button>
          </span>
        )}
        {control.kind === 'readonly' && <span>{control.assigneeName}</span>}
      </div>

      <SaleControl conversation={conversation} busy={busy} onSave={(isSale, amount) => run(() => chat.markSale(isSale, amount))} />
    </section>
  )
}

function SaleControl({
  conversation,
  busy,
  onSave,
}: {
  conversation: Conversation
  busy: boolean
  onSave: (isSale: boolean, amount: number | null) => Promise<void>
}) {
  const [editing, setEditing] = useState(false)
  const [amount, setAmount] = useState('')
  const [error, setError] = useState<string | null>(null)

  function startEditing() {
    setAmount(conversation.saleAmount !== null ? formatCopInput(String(Math.round(conversation.saleAmount))) : '')
    setError(null)
    setEditing(true)
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    const value = parseCop(amount)
    if (value === null) {
      setError('Escribe el monto de la venta en pesos')
      return
    }
    await onSave(true, value)
    setEditing(false)
  }

  if (editing) {
    return (
      <form className="sale-form" onSubmit={submit}>
        <label>
          Monto de la venta (COP)
          <span className="money-input">
            <span aria-hidden="true">$</span>
            <input
              inputMode="numeric"
              aria-label="Monto de la venta en pesos"
              placeholder="0"
              value={amount}
              onChange={(e) => setAmount(formatCopInput(e.target.value))}
              autoFocus
            />
          </span>
        </label>
        {error && <p className="field-error">{error}</p>}
        <div className="inline-actions">
          <button type="submit" className="btn-primary btn-small" disabled={busy}>
            Guardar venta
          </button>
          <button type="button" className="btn-secondary btn-small" onClick={() => setEditing(false)}>
            Cancelar
          </button>
        </div>
      </form>
    )
  }

  if (conversation.isSale) {
    return (
      <div className="sale-box">
        <span className="sale-label">Venta</span>
        <strong className="sale-amount">{formatCop(conversation.saleAmount ?? 0)}</strong>
        <div className="inline-actions">
          <button type="button" className="btn-link" onClick={startEditing}>
            Cambiar monto
          </button>
          <button type="button" className="btn-link danger-link" disabled={busy} onClick={() => void onSave(false, null)}>
            Quitar marca de venta
          </button>
        </div>
      </div>
    )
  }

  return (
    <button type="button" className="btn-secondary btn-full" disabled={busy} onClick={startEditing}>
      Marcar como venta
    </button>
  )
}
