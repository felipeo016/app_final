import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { friendlyError } from '../api/errors'
import { apiFetch } from '../api/http'
import { toMillis } from '../api/types'
import { liveDemoProgress, type LiveDemoStatus } from './liveDemo'

const STATUS_POLL_MS = 3000

/**
 * Demo en vivo: el backend hace que escriban clientes del guion cada 20 a 40 s. Sigue funcionando aunque se
 * cambie de página o se cierre la pestaña; aquí solo se muestra su estado y se inicia o detiene.
 */
export function LiveDemoPanel() {
  const [status, setStatus] = useState<LiveDemoStatus | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())

  const refresh = useCallback(() => {
    apiFetch<LiveDemoStatus>('/api/dev/live-demo')
      .then(setStatus)
      .catch(() => {
        // El siguiente sondeo lo vuelve a intentar
      })
  }, [])

  // Estado inicial y sondeo periódico (el contador de mensajes avanza en el backend)
  useEffect(() => {
    const timer = window.setInterval(refresh, STATUS_POLL_MS)
    const first = window.setTimeout(refresh, 0)
    return () => {
      window.clearInterval(timer)
      window.clearTimeout(first)
    }
  }, [refresh])

  // Cuenta regresiva hasta el próximo mensaje
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [])

  async function toggle() {
    if (!status) return
    setBusy(true)
    setError(null)
    try {
      const path = status.running ? '/api/dev/live-demo/stop' : '/api/dev/live-demo/start'
      setStatus(await apiFetch<LiveDemoStatus>(path, { method: 'POST' }))
    } catch (err) {
      setError(friendlyError(err))
    } finally {
      setBusy(false)
    }
  }

  const nextAt = toMillis(status?.nextMessageAt ?? null)
  const seconds = nextAt === null ? null : Math.max(0, Math.ceil((nextAt - now) / 1000))

  return (
    <section className={`sim-card live-demo${status?.running ? ' live-demo-on' : ''}`}>
      <div className="live-demo-head">
        <h2>Demo en vivo</h2>
        {status?.running && <span className="live-badge">EN VIVO</span>}
      </div>
      <p className="sim-hint">
        Mientras está activa, cada 20 a 40 segundos escribe un cliente por WhatsApp, Instagram o Facebook (guion de{' '}
        {status?.scriptLength ?? 18} mensajes de una tienda de ropa, con clientes distintos en cada demo). Si la
        detienes y la vuelves a iniciar, sigue donde iba. Sigue funcionando aunque cambies de página: ve a la bandeja
        y responde en vivo.
      </p>
      <div className="sim-inline">
        <button
          type="button"
          className={status?.running ? 'btn-danger' : 'btn-primary'}
          disabled={busy || !status}
          onClick={() => void toggle()}
        >
          {status?.running ? 'Detener' : 'Iniciar demo en vivo'}
        </button>
        {status?.running && (
          <span className="sim-hint" role="status">
            {liveDemoProgress(status, seconds)}
          </span>
        )}
      </div>
      {error && <p className="alert alert-error">{error}</p>}
    </section>
  )
}

/** Deja la empresa demo como recién creada (detiene la demo en vivo). Pide confirmación antes. */
export function RestoreDemoPanel() {
  const navigate = useNavigate()
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function restore() {
    setBusy(true)
    setError(null)
    try {
      await apiFetch<{ restored: boolean }>('/api/dev/demo/restore', { method: 'POST' })
      // Los demás navegadores de la empresa se reconectan y recargan solos
      navigate('/')
    } catch (err) {
      setError(friendlyError(err))
      setBusy(false)
      setConfirming(false)
    }
  }

  return (
    <section className="sim-card">
      <h2>Restaurar datos demo</h2>
      <p className="sim-hint">
        Vuelve "Tienda Demo" a como estaba al empezar: borra los chats, mensajes y respuestas rápidas de la demo,
        quita los agentes agregados y reactiva a Laura y Pedro. Úsalo entre presentaciones.
      </p>
      {!confirming ? (
        <div className="sim-inline">
          <button type="button" className="btn-secondary danger" onClick={() => setConfirming(true)}>
            Restaurar datos demo
          </button>
        </div>
      ) : (
        <div className="confirm-box" role="alertdialog" aria-label="Confirmar restauración">
          <p>
            ¿Restaurar los datos demo? Se detiene la demo en vivo y se pierden todos los cambios hechos en "Tienda
            Demo" (respuestas, chats nuevos, ventas, agentes agregados). Esta acción no se puede deshacer.
          </p>
          <div className="inline-actions">
            <button type="button" className="btn-danger" disabled={busy} onClick={() => void restore()}>
              {busy ? 'Restaurando…' : 'Sí, restaurar'}
            </button>
            <button type="button" className="btn-secondary" disabled={busy} onClick={() => setConfirming(false)}>
              Cancelar
            </button>
          </div>
        </div>
      )}
      {error && <p className="alert alert-error">{error}</p>}
    </section>
  )
}
