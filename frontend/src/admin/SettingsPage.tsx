import { useEffect, useState, type FormEvent } from 'react'
import { friendlyError } from '../api/errors'
import { apiFetch } from '../api/http'
import { useAuth } from '../auth/AuthContext'
import AppHeader from '../layout/AppHeader'
import { refreshAiStatus } from '../ai/aiStatus'
import type { AiMode } from '../api/types'

const MIN = 1
const MAX = 1440

interface Settings {
  riskAlertMinutes: number
  aiMode: AiMode
  businessInfo: string
}

const BUSINESS_INFO_MAX = 4000

const AI_MODES: { value: AiMode; label: string; help: string }[] = [
  { value: 'OFF', label: 'Apagada', help: 'Sin IA: todo lo responde el equipo.' },
  {
    value: 'SUGGEST',
    label: 'Sugerencias',
    help: 'En cada chat aparece "Sugerir respuesta": la IA propone un texto y el agente lo revisa y lo envía.',
  },
  {
    value: 'AUTO',
    label: 'Respuesta automática',
    help: 'La IA responde sola los chats que ningún agente ha respondido todavía, si la respuesta está en la información del negocio. Si no, el chat queda para el equipo.',
  },
]

/** Ajustes de la empresa (solo OWNER). */
export default function SettingsPage() {
  const { session, logout } = useAuth()
  const [minutes, setMinutes] = useState('')
  const [saved, setSaved] = useState<number | null>(null)
  const [message, setMessage] = useState<{ kind: 'success' | 'error'; text: string } | null>(null)
  const [saving, setSaving] = useState(false)
  const [aiSettings, setAiSettings] = useState<Settings | null>(null)

  useEffect(() => {
    apiFetch<Settings>('/api/tenant/settings')
      .then((settings) => {
        setSaved(settings.riskAlertMinutes)
        setMinutes(String(settings.riskAlertMinutes))
        setAiSettings(settings)
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
        {/* Se monta con los datos ya cargados: su formulario parte de lo guardado */}
        {aiSettings && <AiSettingsCard initial={aiSettings} />}
      </main>
    </div>
  )
}

/** Modo de la IA e "Información del negocio" (la única fuente de verdad de la IA, junto a las respuestas rápidas). */
export function AiSettingsCard({ initial }: { initial: Settings }) {
  const [mode, setMode] = useState<AiMode>(initial.aiMode)
  const [info, setInfo] = useState(initial.businessInfo)
  const [saved, setSaved] = useState({ mode: initial.aiMode, info: initial.businessInfo })
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<{ kind: 'success' | 'error'; text: string } | null>(null)

  const changed = mode !== saved.mode || info.trim() !== saved.info
  const tooLong = info.length > BUSINESS_INFO_MAX

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!changed || tooLong) return
    setSaving(true)
    setMessage(null)
    try {
      const settings = await apiFetch<Settings>('/api/tenant/settings/ai', {
        method: 'PUT',
        body: { aiMode: mode, businessInfo: info },
      })
      setSaved({ mode: settings.aiMode, info: settings.businessInfo })
      setInfo(settings.businessInfo)
      setMessage({ kind: 'success', text: 'Listo: los cambios de la IA ya están activos.' })
      void refreshAiStatus()
    } catch (err) {
      setMessage({ kind: 'error', text: friendlyError(err) })
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="admin-card">
      <h2>Inteligencia artificial</h2>
      <p className="admin-intro">
        La IA corre en este equipo (Ollama), sin internet. Solo responde con lo que escribas en "Información del
        negocio" y en las respuestas rápidas: nunca inventa precios, disponibilidad ni fechas.
      </p>
      <form className="settings-form ai-settings" onSubmit={submit} noValidate>
        <fieldset className="ai-modes">
          <legend>Modo</legend>
          {AI_MODES.map((option) => (
            <label key={option.value} className={`ai-mode${mode === option.value ? ' selected' : ''}`}>
              <input
                type="radio"
                name="aiMode"
                value={option.value}
                checked={mode === option.value}
                onChange={() => {
                  setMode(option.value)
                  setMessage(null)
                }}
              />
              <span>
                <strong>{option.label}</strong>
                <small>{option.help}</small>
              </span>
            </label>
          ))}
        </fieldset>
        <label>
          Información del negocio
          <textarea
            rows={12}
            value={info}
            aria-invalid={tooLong}
            placeholder="Horarios, envíos (ciudades, costos y tiempos), medios de pago, productos con precios y tallas…"
            onChange={(e) => {
              setInfo(e.target.value)
              setMessage(null)
            }}
          />
          <span className={tooLong ? 'field-error' : 'field-help'}>
            {info.length} / {BUSINESS_INFO_MAX} caracteres. Lo que no esté aquí, la IA no lo responde.
          </span>
        </label>
        <div className="admin-actions">
          <button type="submit" className="btn-primary" disabled={saving || !changed || tooLong}>
            {saving ? 'Guardando…' : 'Guardar ajustes de IA'}
          </button>
        </div>
      </form>
      {message && <p className={`alert alert-${message.kind}`}>{message.text}</p>}
    </section>
  )
}
