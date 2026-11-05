import { toMillis, type ApiInstant, type DeliveryStatus, type Message } from '../api/types'

/** Orden del chat: del más antiguo al más reciente; a igual hora, por id (estable). */
export function compareMessages(a: Message, b: Message): number {
  const byTime = (toMillis(a.timestamp) ?? 0) - (toMillis(b.timestamp) ?? 0)
  return byTime !== 0 ? byTime : a.id.localeCompare(b.id)
}

/**
 * Une mensajes sin duplicar (la misma respuesta llega por HTTP y por WebSocket, y la paginación por
 * desplazamiento puede repetir mensajes cuando entran nuevos). A igual id gana la versión entrante:
 * trae el estado de entrega más reciente.
 */
export function mergeMessages(current: Message[], incoming: Message[]): Message[] {
  if (incoming.length === 0) return current
  const byId = new Map(current.map((message) => [message.id, message]))
  for (const message of incoming) {
    const existing = byId.get(message.id)
    byId.set(message.id, existing ? preferNewer(existing, message) : message)
  }
  return [...byId.values()].sort(compareMessages)
}

const DELIVERY_RANK: Record<DeliveryStatus, number> = { PENDING: 0, SENT: 1, DELIVERED: 2, READ: 3, FAILED: 1 }

/**
 * El estado de entrega solo avanza, salvo un reintento (FAILED → PENDING/SENT), que es una acción
 * explícita. Así un evento tardío no hace "retroceder" un mensaje ya leído.
 */
function preferNewer(existing: Message, incoming: Message): Message {
  const before = existing.deliveryStatus
  const after = incoming.deliveryStatus
  if (!before || !after || before === 'FAILED' || after === 'FAILED') return incoming
  return DELIVERY_RANK[after] >= DELIVERY_RANK[before] ? incoming : existing
}

export const DELIVERY_LABELS: Record<DeliveryStatus, string> = {
  PENDING: 'Enviando…',
  SENT: 'Enviado',
  DELIVERED: 'Entregado',
  READ: 'Leído',
  FAILED: 'No se envió',
}

/** Estado de la ventana de 24 h de WhatsApp para mostrar el contador. */
export interface WindowInfo {
  applies: boolean
  open: boolean
  remainingMs: number
}

export function windowInfo(
  channel: string,
  expiresAt: ApiInstant | null,
  replyWindowOpen: boolean,
  now: number,
): WindowInfo {
  if (channel !== 'WHATSAPP') return { applies: false, open: true, remainingMs: Infinity }
  const expires = toMillis(expiresAt)
  if (expires === null) return { applies: true, open: replyWindowOpen, remainingMs: 0 }
  const remainingMs = expires - now
  return { applies: true, open: remainingMs > 0, remainingMs: Math.max(remainingMs, 0) }
}

export function formatRemaining(ms: number): string {
  const totalMinutes = Math.floor(ms / 60_000)
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  if (hours === 0) return `${minutes} min`
  return minutes === 0 ? `${hours} h` : `${hours} h ${minutes} min`
}

/** Respuestas rápidas: "/" al inicio abre el selector y lo que sigue filtra por título o texto. */
export function slashQuery(text: string): string | null {
  const match = /^\/(\S*)$/.exec(text)
  return match ? match[1].toLowerCase() : null
}
