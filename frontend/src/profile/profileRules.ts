import type { Conversation, Role } from '../api/types'

/** Qué control de asignación ve el usuario, según las reglas del backend (decisión aprobada 11). */
export type AssignmentControl =
  | { kind: 'select' } // OWNER: elige a cualquier usuario asignable o "Sin asignar"
  | { kind: 'take' } // AGENT con el chat sin asignar
  | { kind: 'release' } // AGENT con el chat asignado a sí mismo
  | { kind: 'readonly'; assigneeName: string } // AGENT con el chat de otro agente

export function assignmentControl(role: Role, currentUserId: string, conversation: Conversation): AssignmentControl {
  if (role === 'OWNER') return { kind: 'select' }
  const assignee = conversation.assignedTo
  if (!assignee) return { kind: 'take' }
  if (assignee.id === currentUserId) return { kind: 'release' }
  return { kind: 'readonly', assigneeName: assignee.name ?? 'otro agente' }
}

const COP = new Intl.NumberFormat('es-CO', {
  style: 'currency',
  currency: 'COP',
  minimumFractionDigits: 0,
  maximumFractionDigits: 0,
})

/** Monto en pesos colombianos sin decimales, p. ej. "$ 150.000". */
export function formatCop(amount: number): string {
  return COP.format(Math.round(amount))
}

/**
 * Lee lo que escribe el agente como pesos enteros: acepta "150000", "150.000" o "$ 150.000". Devuelve null si
 * no hay ningún dígito. Los decimales no existen en pesos, así que todo lo que no es dígito se ignora.
 */
export function parseCop(input: string): number | null {
  const digits = input.replace(/\D/g, '')
  if (!digits) return null
  const value = Number(digits)
  return Number.isSafeInteger(value) ? value : null
}

/** Mientras escribe, el agente ve el número con puntos de miles. */
export function formatCopInput(input: string): string {
  const value = parseCop(input)
  return value === null ? '' : new Intl.NumberFormat('es-CO', { maximumFractionDigits: 0 }).format(value)
}
