import { Client, type IFrame } from '@stomp/stompjs'
import type { RealtimeEvent } from '../api/types'
import { NOTICES } from '../auth/session'

/** Código de cierre 1008 (política) con el que el backend corta la sesión. */
const POLICY_VIOLATION = 1008

export const tenantTopic = (tenantId: string) => `/topic/tenants/${tenantId}/events`

/**
 * Si el cierre del WebSocket significa que la sesión terminó, devuelve el aviso para el login; null si es
 * un corte normal de red (entonces se reintenta).
 */
export function sessionEndNotice(code: number, reason: string): string | null {
  if (code !== POLICY_VIOLATION) return null
  if (reason.includes('Sesión vencida')) return NOTICES.expired
  if (reason.includes('Usuario desactivado')) return NOTICES.deactivated
  return NOTICES.unauthorized
}

/** Un frame ERROR por credenciales (CONNECT rechazado) también termina la sesión. */
export function isAuthError(frame: IFrame): boolean {
  return (frame.headers.message ?? '').startsWith('No autorizado')
}

export type ConnectionState = 'connecting' | 'connected' | 'reconnecting'

export interface RealtimeOptions {
  token: string
  tenantId: string
  onEvent: (event: RealtimeEvent) => void
  /** Cada conexión, también las reconexiones: hay que volver a pedir la bandeja completa. */
  onConnected: () => void
  onStateChange: (state: ConnectionState) => void
  onSessionEnded: (notice: string) => void
}

function brokerUrl(): string {
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${protocol}://${window.location.host}/ws`
}

/** Crea y activa el cliente STOMP. Para cerrarlo: {@code client.deactivate()}. */
export function connectRealtime(options: RealtimeOptions): Client {
  let ended = false
  const end = (notice: string) => {
    if (ended) return
    ended = true
    void client.deactivate()
    options.onSessionEnded(notice)
  }

  const client = new Client({
    brokerURL: brokerUrl(),
    connectHeaders: { Authorization: `Bearer ${options.token}` },
    reconnectDelay: 3000,
    // El broker simple no usa latidos
    heartbeatIncoming: 0,
    heartbeatOutgoing: 0,
    onConnect: () => {
      client.subscribe(tenantTopic(options.tenantId), (message) => {
        try {
          options.onEvent(JSON.parse(message.body) as RealtimeEvent)
        } catch {
          // Un evento ilegible se ignora: la próxima recarga corrige la bandeja
        }
      })
      options.onStateChange('connected')
      options.onConnected()
    },
    onStompError: (frame) => {
      if (isAuthError(frame)) end(NOTICES.unauthorized)
    },
    onWebSocketClose: (event: CloseEvent) => {
      const notice = sessionEndNotice(event.code, event.reason ?? '')
      if (notice) {
        end(notice)
      } else if (!ended) {
        options.onStateChange('reconnecting')
      }
    },
  })
  options.onStateChange('connecting')
  client.activate()
  return client
}
