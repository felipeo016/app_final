import { useEffect, useState } from 'react'
import type { Conversation } from '../api/types'
import {
  avatarHue,
  CHANNEL_LABELS,
  channelClass,
  initials,
  shortTime,
  STATUS_LABELS,
  statusClass,
  waitingMinutes,
  waitingText,
} from './labels'

interface Props {
  conversations: Conversation[]
  loading: boolean
  error: string | null
  selectedId: string | null
  onSelect: (id: string) => void
  onRetry: () => void
}

/** Hora actual que se renueva cada 30 s, para que "lleva X min" avance sin eventos. */
function useNow(intervalMs = 30_000): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs)
    return () => window.clearInterval(timer)
  }, [intervalMs])
  return now
}

export default function ConversationList({ conversations, loading, error, selectedId, onSelect, onRetry }: Props) {
  const now = useNow()

  if (error && conversations.length === 0) {
    return (
      <div className="list-message">
        <p>{error}</p>
        <button type="button" className="btn-link" onClick={onRetry}>
          Reintentar
        </button>
      </div>
    )
  }
  if (!loading && conversations.length === 0) {
    return <p className="list-message">No hay conversaciones con estos filtros.</p>
  }

  return (
    <ul className="conversation-list" aria-busy={loading}>
      {conversations.map((conversation) => (
        <li key={conversation.id}>
          <ConversationItem
            conversation={conversation}
            now={now}
            selected={conversation.id === selectedId}
            onSelect={() => onSelect(conversation.id)}
          />
        </li>
      ))}
    </ul>
  )
}

interface ItemProps {
  conversation: Conversation
  now: number
  selected: boolean
  onSelect: () => void
}

function ConversationItem({ conversation, now, selected, onSelect }: ItemProps) {
  const name = conversation.contact.name ?? 'Cliente'
  const classes = ['conversation-item']
  if (selected) classes.push('selected')
  if (conversation.atRisk) classes.push('at-risk')

  return (
    <button type="button" className={classes.join(' ')} onClick={onSelect} aria-pressed={selected}>
      <span className="avatar" style={{ backgroundColor: `hsl(${avatarHue(conversation.contact.id)} 45% 45%)` }}>
        {initials(name)}
      </span>
      <span className="item-body">
        <span className="item-top">
          <span className="item-name">{name}</span>
          <span className="item-time">{shortTime(conversation.lastMessageAt, now)}</span>
        </span>
        <span className="item-preview">{conversation.lastMessagePreview ?? ''}</span>
        <span className="item-tags">
          <span className={channelClass(conversation.channel)}>{CHANNEL_LABELS[conversation.channel]}</span>
          <span className={statusClass(conversation.status)}>{STATUS_LABELS[conversation.status]}</span>
          {conversation.assignedTo && (
            <span className="item-assignee">{conversation.assignedTo.name ?? 'Agente'}</span>
          )}
          {conversation.unreadCount > 0 && (
            <span className="unread" aria-label={`${conversation.unreadCount} sin leer`}>
              {conversation.unreadCount}
            </span>
          )}
        </span>
        {conversation.atRisk && (
          <span className="risk-text" role="status">
            {waitingText(waitingMinutes(conversation, now))}
          </span>
        )}
      </span>
    </button>
  )
}
