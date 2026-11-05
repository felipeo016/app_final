import { useCallback, useEffect, useState } from 'react'
import { apiFetch } from '../api/http'

export interface QuickReply {
  id: string
  title: string
  text: string
}

/**
 * Respuestas rápidas de la empresa (todos los usuarios pueden usarlas). {@code refresh} se llama al abrir el
 * selector, para ver las que el dueño haya creado o editado mientras el chat estaba abierto.
 */
export function useQuickReplies(): { replies: QuickReply[]; refresh: () => void } {
  const [replies, setReplies] = useState<QuickReply[]>([])

  const refresh = useCallback(() => {
    apiFetch<QuickReply[]>('/api/quick-replies')
      .then(setReplies)
      .catch(() => {
        // Se conservan las que ya había
      })
  }, [])

  useEffect(() => {
    apiFetch<QuickReply[]>('/api/quick-replies')
      .then(setReplies)
      .catch(() => setReplies([]))
  }, [])

  return { replies, refresh }
}
