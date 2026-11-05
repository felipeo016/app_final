// Tipos de la API del backend (mismos nombres de campo que los DTO de Spring).

export type ChannelType = 'WHATSAPP' | 'INSTAGRAM' | 'FACEBOOK' | 'SIMULATED'
export type ConversationStatus = 'NEW' | 'IN_PROGRESS' | 'RESOLVED'
export type Role = 'OWNER' | 'AGENT'
export type DeliveryStatus = 'PENDING' | 'SENT' | 'DELIVERED' | 'READ' | 'FAILED'

/** Instante tal como lo serializa el backend (ISO-8601; se aceptan también segundos epoch). */
export type ApiInstant = string | number

export interface User {
  id: string
  tenantId: string
  name: string
  email: string
  role: Role
  active: boolean
  createdAt: ApiInstant
}

export interface Tenant {
  id: string
  name: string
}

export interface AuthResponse {
  token: string
  expiresAt: ApiInstant
  user: User
  tenant: Tenant
}

export interface Ref {
  id: string
  name: string | null
}

export interface Conversation {
  id: string
  contact: Ref
  channel: ChannelType
  status: ConversationStatus
  open: boolean
  assignedTo: Ref | null
  lastMessagePreview: string | null
  lastMessageAt: ApiInstant | null
  lastInboundAt: ApiInstant | null
  lastOutboundAt: ApiInstant | null
  firstResponseAt: ApiInstant | null
  unreadCount: number
  atRisk: boolean
  atRiskSince: ApiInstant | null
  replyWindowOpen: boolean
  replyWindowExpiresAt: ApiInstant | null
  isSale: boolean
  saleAmount: number | null
  tags: string[]
  createdAt: ApiInstant
}

export interface Message {
  id: string
  conversationId: string
  direction: 'INBOUND' | 'OUTBOUND'
  type: 'TEXT' | 'IMAGE' | 'INTERNAL_NOTE'
  content: string
  sentBy: string | null
  timestamp: ApiInstant
  deliveryStatus: DeliveryStatus | null
  failureReason: string | null
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export interface AssignableUser {
  id: string
  name: string
  role: Role
}

export type RealtimeEventType = 'MESSAGE_CREATED' | 'CONVERSATION_UPDATED' | 'ASSIGNED'

export interface RealtimeEvent {
  type: RealtimeEventType
  conversationId: string
  conversation: Conversation
  message?: Message
  occurredAt: ApiInstant
}

/** Convierte un instante de la API a milisegundos epoch (null si falta o no es válido). */
export function toMillis(value: ApiInstant | null | undefined): number | null {
  if (value === null || value === undefined) return null
  if (typeof value === 'number') {
    // Jackson puede escribir segundos con decimales; un valor tan pequeño no son milisegundos.
    return value < 1e11 ? Math.round(value * 1000) : value
  }
  const millis = Date.parse(value)
  return Number.isNaN(millis) ? null : millis
}
