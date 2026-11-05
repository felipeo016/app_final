import type { ApiInstant } from '../api/types'

export interface LiveDemoStatus {
  running: boolean
  messagesSent: number
  nextMessageAt: ApiInstant | null
  scriptLength: number
  /** Número (1..scriptLength) del último mensaje del guion enviado; 0 si aún no se envió ninguno. */
  currentStep: number
  lastCustomer: string | null
}

/** "Demo en vivo activa: mensaje 5 de 18 (último: Camila Ospina) · próximo en 23 s" */
export function liveDemoProgress(status: LiveDemoStatus, secondsToNext: number | null): string {
  const step =
    status.currentStep > 0
      ? `mensaje ${status.currentStep} de ${status.scriptLength}`
      : 'esperando el primer mensaje'
  const last = status.currentStep > 0 && status.lastCustomer ? ` (último: ${status.lastCustomer})` : ''
  const next = secondsToNext !== null ? ` · próximo en ${secondsToNext} s` : ''
  return `Demo en vivo activa: ${step}${last}${next}`
}
