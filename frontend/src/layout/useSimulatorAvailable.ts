import { useEffect, useState } from 'react'
import { apiFetch } from '../api/http'

/** true si el backend corre con el perfil dev (existe /api/dev/status); fuera de dev responde 404. */
export function useSimulatorAvailable(): boolean | null {
  const [available, setAvailable] = useState<boolean | null>(null)
  useEffect(() => {
    apiFetch<{ enabled: boolean }>('/api/dev/status')
      .then((status) => setAvailable(status.enabled))
      .catch(() => setAvailable(false))
  }, [])
  return available
}
