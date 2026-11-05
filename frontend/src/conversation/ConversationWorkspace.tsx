import type { AssignableUser } from '../api/types'
import type { Session } from '../auth/session'
import type { SubscribeToRealtime } from '../inbox/useInbox'
import ContactPanel from '../profile/ContactPanel'
import ConversationView from './ConversationView'
import { useConversation } from './useConversation'

export type MobileView = 'inbox' | 'chat' | 'profile'

interface Props {
  conversationId: string
  session: Session
  users: AssignableUser[]
  subscribe: SubscribeToRealtime
  connectionSeq: number
  onMobileView: (view: MobileView) => void
  onOpenConversation: (conversationId: string) => void
}

/**
 * Columnas 2 y 3 del chat abierto. Un único {@link useConversation} alimenta la conversación y el perfil:
 * un cambio hecho en un lado (o recibido por WebSocket) se ve al instante en el otro.
 * Se monta con {@code key} = id de la conversación, así que al cambiar de chat todo empieza de cero.
 */
export default function ConversationWorkspace({
  conversationId,
  session,
  users,
  subscribe,
  connectionSeq,
  onMobileView,
  onOpenConversation,
}: Props) {
  const chat = useConversation(conversationId, subscribe, connectionSeq)

  return (
    <>
      <section className="column column-conversation" aria-label="Conversación">
        <ConversationView
          chat={chat}
          currentUserId={session.user.id}
          users={users}
          onBack={() => onMobileView('inbox')}
          onShowProfile={() => onMobileView('profile')}
        />
      </section>
      <aside className="column column-profile" aria-label="Perfil del cliente">
        <ContactPanel
          chat={chat}
          session={session}
          users={users}
          onOpenConversation={onOpenConversation}
          onBackToChat={() => onMobileView('chat')}
        />
      </aside>
    </>
  )
}
