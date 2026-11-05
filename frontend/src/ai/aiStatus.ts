import { useSyncExternalStore } from 'react'
import { apiFetch } from '../api/http'
import type { AiStatus } from '../api/types'

const POLL_MS = 15_000

/**
 * Estado de la IA compartido por toda la app (encabezado y caja de respuesta): una sola consulta cada 15 s
 * mientras haya algún componente que lo use. null mientras no se sabe.
 */
let current: AiStatus | null = null
const listeners = new Set<() => void>()
let timer: number | undefined
let inFlight: AbortController | null = null

function emit(next: AiStatus | null) {
  current = next
  listeners.forEach((listener) => listener())
}

/** Vuelve a consultar ya (p. ej. al cambiar el modo en Ajustes). */
export function refreshAiStatus(): Promise<void> {
  inFlight?.abort()
  const controller = new AbortController()
  inFlight = controller
  return apiFetch<AiStatus>('/api/ai/status', { signal: controller.signal })
    .then((status) => emit(status))
    .catch((error: unknown) => {
      if (error instanceof DOMException && error.name === 'AbortError') return
      // Sin respuesta del backend no se puede afirmar nada de la IA: se deja el último estado conocido
    })
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  if (listeners.size === 1) {
    void refreshAiStatus()
    timer = window.setInterval(() => void refreshAiStatus(), POLL_MS)
  }
  return () => {
    listeners.delete(listener)
    if (listeners.size === 0) {
      window.clearInterval(timer)
      inFlight?.abort()
      current = null
    }
  }
}

export function useAiStatus(): AiStatus | null {
  return useSyncExternalStore(subscribe, () => current)
}

export const AI_STATE_LABELS: Record<AiStatus['state'], string> = {
  READY: 'IA lista',
  OFF: 'IA apagada',
  UNAVAILABLE: 'IA no disponible',
}
