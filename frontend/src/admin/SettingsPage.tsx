import { useEffect, useState, type FormEvent } from 'react'
import { friendlyError } from '../api/errors'
import { apiFetch } from '../api/http'
import { useAuth } from '../auth/AuthContext'
import AppHeader from '../layout/AppHeader'

const MIN = 1
const MAX = 1440

interface Settings {
  riskAlertMinutes: number
}

/** Ajustes de la empresa (solo OWNER). */
export default function SettingsPage() {
  const { session, logout } = useAuth()
  const [minutes, setMinutes] = useState('')
  const [saved, setSaved] = useState<number | null>(null)
  const [message, setMessage] = useState<{ kind: 'success' | 'error'; text: string } | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    apiFetch<Settings>('/api/tenant/settings')
      .then((settings) => {
        setSaved(settings.riskAlertMinutes)
        setMinutes(String(settings.riskAlertMinutes))
      })
      .catch((err: unknown) => setMessage({ kind: 'error', text: friendlyError(err) }))
  }, [])

  if (!session) return null

  const value = Number(minutes)
  const valid = /^\d+$/.test(minutes) && value >= MIN && value <= MAX

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!valid) return
    setSaving(true)
    setMessage(null)
    try {
      const settings = await apiFetch<Settings>('/api/tenant/settings', {
        method: 'PUT',
        body: { riskAlertMinutes: value },
      })
      setSaved(settings.riskAlertMinutes)
      setMessage({
        kind: 'success',
        text: `Listo: un chat se marcará en riesgo cuando el cliente lleve ${settings.riskAlertMinutes} min sin respuesta.`,
      })
    } catch (err) {
      setMessage({ kind: 'error', text: friendlyError(err) })
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="app">
      <AppHeader session={session} onLogout={() => logout()} />
      <main className="admin-page">
        <h1>Ajustes</h1>
        <section className="admin-card">
          <h2>Alerta "cero mensajes perdidos"</h2>
          <p className="admin-intro">
            Si un cliente escribe y nadie le responde en este tiempo, su chat se resalta en rojo en la bandeja de todo
            el equipo. El cambio aplica en la siguiente revisión (cada minuto).
          </p>
          <form className="settings-form" onSubmit={submit} noValidate>
            <label>
              Minutos sin respuesta para marcar un chat en riesgo
              <span className="minutes-input">
                <input
                  type="number"
                  inputMode="numeric"
                  min={MIN}
                  max={MAX}
                  value={minutes}
                  aria-invalid={minutes !== '' && !valid}
                  onChange={(e) => {
                    setMinutes(e.target.value)
                    setMessage(null)
                  }}
                />
                <span>minutos</span>
              </span>
              {minutes !== '' && !valid && (
                <span className="field-error">Debe ser un número entero entre 1 y 1440 (24 horas).</span>
              )}
            </label>
            <div className="admin-actions">
              <button type="submit" className="btn-primary" disabled={saving || !valid || value === saved}>
                {saving ? 'Guardando…' : 'Guardar'}
              </button>
            </div>
          </form>
          {message && <p className={`alert alert-${message.kind}`}>{message.text}</p>}
        </section>
      </main>
    </div>
  )
}
