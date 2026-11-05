import { toMillis, type ChannelType, type Conversation, type ConversationStatus } from '../api/types'

export const CHANNEL_LABELS: Record<ChannelType, string> = {
  WHATSAPP: 'WhatsApp',
  INSTAGRAM: 'Instagram',
  FACEBOOK: 'Facebook',
  SIMULATED: 'Simulado',
}

export const STATUS_LABELS: Record<ConversationStatus, string> = {
  NEW: 'Nuevo',
  IN_PROGRESS: 'En proceso',
  RESOLVED: 'Resuelto',
}

/** Clase CSS de la etiqueta (los colores están en index.css como variables). */
export const channelClass = (channel: ChannelType) => `tag tag-channel-${channel.toLowerCase()}`
export const statusClass = (status: ConversationStatus) => `tag tag-status-${status.toLowerCase().replace('_', '-')}`

/** Iniciales para el avatar: dos palabras → dos iniciales; si no, los dos primeros caracteres. */
export function initials(name: string | null | undefined): string {
  const clean = (name ?? '').trim()
  if (!clean) return '?'
  const words = clean.split(/\s+/).filter(Boolean)
  const letters = words.length >= 2 ? words[0][0] + words[1][0] : clean.slice(0, 2)
  return letters.toUpperCase()
}

/** Tono estable por contacto, para que cada avatar conserve su color. */
export function avatarHue(seed: string): number {
  // FNV-1a: los ids de Mongo comparten prefijo (marca de tiempo), así que hace falta buena dispersión
  let hash = 0x811c9dc5
  for (let i = 0; i < seed.length; i++) {
    hash ^= seed.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  return (hash >>> 0) % 360
}

/** Minutos que lleva el cliente esperando respuesta (desde su último mensaje). */
export function waitingMinutes(conversation: Conversation, now: number): number {
  const since = toMillis(conversation.lastInboundAt) ?? toMillis(conversation.atRiskSince)
  if (since === null) return 0
  return Math.max(0, Math.floor((now - since) / 60_000))
}

export function waitingText(minutes: number): string {
  return `lleva ${minutes} min sin respuesta`
}

/** Hora corta de la última actividad: hoy → "14:05"; otro día → "3 nov". */
export function shortTime(value: Conversation['lastMessageAt'], now: number): string {
  const millis = toMillis(value)
  if (millis === null) return ''
  const date = new Date(millis)
  const today = new Date(now)
  if (date.toDateString() === today.toDateString()) {
    return date.toLocaleTimeString('es', { hour: '2-digit', minute: '2-digit' })
  }
  return date.toLocaleDateString('es', { day: 'numeric', month: 'short' })
}
