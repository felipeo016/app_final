import { useEffect, useState } from 'react'
import { apiFetch } from '../api/http'
import type { AssignableUser } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import type { Session } from '../auth/session'
import ConversationView from '../conversation/ConversationView'
import AppHeader from '../layout/AppHeader'
import ConversationList from './ConversationList'
import InboxFiltersBar from './InboxFiltersBar'
import { DEFAULT_FILTERS, type InboxFilters } from './inboxState'
import { useInbox } from './useInbox'

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

  return (
    <div className="app">
      <AppHeader session={session} onLogout={() => logout()} connection={inbox.connection} />

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
          {selectedId ? (
            <ConversationView
              // Otro chat = otra instancia: todo su estado (mensajes, errores, borrador) empieza de cero
              key={selectedId}
              conversationId={selectedId}
              currentUserId={session.user.id}
              users={users}
              subscribe={inbox.subscribe}
              connectionSeq={inbox.connectionSeq}
            />
          ) : (
            <div className="placeholder">
              <p>Selecciona una conversación de la bandeja.</p>
            </div>
          )}
        </section>

        <aside className="column column-profile" aria-label="Perfil del cliente">
          <div className="placeholder">
            <p>{selectedId ? 'El perfil del cliente llega en el paso 8c.' : 'Perfil del cliente'}</p>
          </div>
        </aside>
      </main>
    </div>
  )
}
