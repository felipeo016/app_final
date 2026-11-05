import { NavLink } from 'react-router-dom'
import type { Session } from '../auth/session'
import type { ConnectionState } from '../realtime/realtimeClient'
import { useSimulatorAvailable } from './useSimulatorAvailable'

const CONNECTION_LABELS: Record<ConnectionState, string> = {
  connecting: 'Conectando…',
  connected: 'En línea',
  reconnecting: 'Reconectando…',
}

interface Props {
  session: Session
  onLogout: () => void
  connection?: ConnectionState
}

export default function AppHeader({ session, onLogout, connection }: Props) {
  const simulator = useSimulatorAvailable()
  const owner = session.user.role === 'OWNER'
  return (
    <header className="app-header">
      <div className="app-brand">
        Unicanal <span className="app-tenant">{session.tenant.name}</span>
      </div>
      <nav className="app-nav" aria-label="Secciones">
        <NavLink to="/" end>
          Bandeja
        </NavLink>
        {/* Agentes y Ajustes solo existen para el dueño (el backend también lo exige) */}
        {owner && <NavLink to="/agentes">Agentes</NavLink>}
        {owner && <NavLink to="/ajustes">Ajustes</NavLink>}
        {simulator && <NavLink to="/simulador">Simulador</NavLink>}
      </nav>
      <div className="app-user">
        {connection && <span className={`connection connection-${connection}`}>{CONNECTION_LABELS[connection]}</span>}
        <span className="app-user-name">
          {session.user.name} · {owner ? 'Dueño' : 'Agente'}
        </span>
        <button type="button" className="btn-link" onClick={onLogout}>
          Cerrar sesión
        </button>
      </div>
    </header>
  )
}
