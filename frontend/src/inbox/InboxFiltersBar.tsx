import type { AssignableUser, ChannelType, ConversationStatus } from '../api/types'
import type { InboxFilters } from './inboxState'
import { CHANNEL_LABELS, STATUS_LABELS } from './labels'

interface Props {
  filters: InboxFilters
  users: AssignableUser[]
  onChange: (filters: InboxFilters) => void
}

export default function InboxFiltersBar({ filters, users, onChange }: Props) {
  const set = <K extends keyof InboxFilters>(key: K, value: InboxFilters[K]) => onChange({ ...filters, [key]: value })

  return (
    <div className="filters">
      <select
        aria-label="Estado"
        value={filters.status}
        onChange={(e) => set('status', e.target.value as ConversationStatus | '')}
      >
        <option value="">Todos los estados</option>
        {(Object.keys(STATUS_LABELS) as ConversationStatus[]).map((status) => (
          <option key={status} value={status}>
            {STATUS_LABELS[status]}
          </option>
        ))}
      </select>
      <select
        aria-label="Canal"
        value={filters.channel}
        onChange={(e) => set('channel', e.target.value as ChannelType | '')}
      >
        <option value="">Todos los canales</option>
        {(Object.keys(CHANNEL_LABELS) as ChannelType[]).map((channel) => (
          <option key={channel} value={channel}>
            {CHANNEL_LABELS[channel]}
          </option>
        ))}
      </select>
      <select aria-label="Asignado" value={filters.assignee} onChange={(e) => set('assignee', e.target.value)}>
        <option value="">Todos los agentes</option>
        <option value="me">Mis chats</option>
        <option value="unassigned">Sin asignar</option>
        {users.map((user) => (
          <option key={user.id} value={user.id}>
            {user.name}
          </option>
        ))}
      </select>
      <label className="filter-check">
        <input type="checkbox" checked={filters.unanswered} onChange={(e) => set('unanswered', e.target.checked)} />
        Sin responder
      </label>
    </div>
  )
}
