import { useEffect, useState } from 'react'
import { apiFetch } from '../api/http'
import type { AssignableUser } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import type { Session } from '../auth/session'
import ConversationList from './ConversationList'
import InboxFiltersBar from './InboxFiltersBar'
import { DEFAULT_FILTERS, type InboxFilters } from './inboxState'
import { useInbox } from './useInbox'

const CONNECTION_LABELS = {
  connecting: 'Conectando…',
  connected: 'En línea',
  reconnecting: 'Reconectando…',
} as const

/** Pantalla principal: bandeja unificada | conversación | perfil del cliente. */
export default function InboxPage() {
  const { session, logout } = useAuth()
  // RequireSession garantiza la sesión; el componente interno recibe una no nula
  return session ? <Inbox session={session} logout={logout} /> : null
}

function Inbox({ session, logout }: { session: Session; logout: (notice?: string) => void }) {
  const [filters, setFilters] = useState<InboxFilters>(DEFAULT_FILTERS)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [users, setUsers] = useState<AssignableUser[]>([])
  const inbox = useInbox(session, filters, logout)

  useEffect(() => {
    apiFetch<AssignableUser[]>('/api/users/assignable')
      .then(setUsers)
      .catch(() => setUsers([]))
  }, [])

  const selected = inbox.conversations.find((conversation) => conversation.id === selectedId) ?? null

  return (
    <div className="app">
      <header className="app-header">
        <div className="app-brand">
          Unicanal <span className="app-tenant">{session.tenant.name}</span>
        </div>
        <div className="app-user">
          <span className={`connection connection-${inbox.connection}`}>{CONNECTION_LABELS[inbox.connection]}</span>
          <span>
            {session.user.name} · {session.user.role === 'OWNER' ? 'Dueño' : 'Agente'}
          </span>
          <button type="button" className="btn-link" onClick={() => logout()}>
            Cerrar sesión
          </button>
        </div>
      </header>

      <main className="columns">
        <section className="column column-inbox" aria-label="Bandeja">
          <InboxFiltersBar filters={filters} users={users} onChange={setFilters} />
          <ConversationList
            conversations={inbox.conversations}
            loading={inbox.loading}
            error={inbox.error}
            selectedId={selectedId}
            onSelect={setSelectedId}
            onRetry={inbox.reload}
          />
        </section>

        <section className="column column-conversation" aria-label="Conversación">
          <div className="placeholder">
            {selected ? (
              <>
                <h2>{selected.contact.name ?? 'Cliente'}</h2>
                <p>El historial y la respuesta llegan en el paso 8b.</p>
              </>
            ) : (
              <p>Selecciona una conversación de la bandeja.</p>
            )}
          </div>
        </section>

        <aside className="column column-profile" aria-label="Perfil del cliente">
          <div className="placeholder">
            <p>{selected ? 'El perfil del cliente llega en el paso 8c.' : 'Perfil del cliente'}</p>
          </div>
        </aside>
      </main>
    </div>
  )
}
