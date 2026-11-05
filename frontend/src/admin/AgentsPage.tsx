import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { friendlyError } from '../api/errors'
import { ApiError, apiFetch } from '../api/http'
import { toMillis, type User } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import AppHeader from '../layout/AppHeader'

/** Gestión del equipo (solo OWNER): listar, crear y desactivar agentes. */
export default function AgentsPage() {
  const { session, logout } = useAuth()
  const [agents, setAgents] = useState<User[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  // Cada creación o desactivación incrementa la versión y la lista se vuelve a pedir
  const [version, setVersion] = useState(0)
  const load = useCallback(() => setVersion((v) => v + 1), [])

  useEffect(() => {
    apiFetch<User[]>('/api/agents')
      .then((list) => {
        setAgents(list)
        setError(null)
      })
      .catch((err: unknown) => setError(friendlyError(err)))
  }, [version])

  if (!session) return null

  return (
    <div className="app">
      <AppHeader session={session} onLogout={() => logout()} />
      <main className="admin-page">
        <h1>Agentes</h1>
        <p className="admin-intro">
          Las personas de tu equipo que atienden los chats. Un agente desactivado no puede volver a entrar y sus
          chats abiertos quedan sin asignar.
        </p>
        <CreateAgentForm onCreated={load} />
        <section className="admin-card">
          <h2>Equipo</h2>
          {error && <p className="alert alert-error">{error}</p>}
          {agents === null && !error && <p className="profile-muted">Cargando…</p>}
          {agents?.length === 0 && <p className="profile-muted">Todavía no hay agentes. Crea el primero arriba.</p>}
          {agents && agents.length > 0 && (
            <ul className="agent-list">
              {agents.map((agent) => (
                <AgentRow key={agent.id} agent={agent} onChanged={load} />
              ))}
            </ul>
          )}
        </section>
      </main>
    </div>
  )
}

const EMPTY = { name: '', email: '', password: '' }

function CreateAgentForm({ onCreated }: { onCreated: () => void }) {
  const [form, setForm] = useState(EMPTY)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [created, setCreated] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setCreated(null)
    setFieldErrors({})
    if (form.password.length < 8) {
      setFieldErrors({ password: 'Debe tener al menos 8 caracteres' })
      return
    }
    setSaving(true)
    try {
      const agent = await apiFetch<User>('/api/agents', {
        method: 'POST',
        body: { name: form.name.trim(), email: form.email.trim(), password: form.password },
      })
      setCreated(`${agent.name} ya puede iniciar sesión con ${agent.email}.`)
      setForm(EMPTY)
      onCreated()
    } catch (err) {
      if (err instanceof ApiError && err.code === 'VALIDATION_ERROR') setFieldErrors(err.fieldErrors)
      setError(friendlyError(err))
    } finally {
      setSaving(false)
    }
  }

  const update = (field: keyof typeof EMPTY) => (value: string) => setForm((f) => ({ ...f, [field]: value }))

  return (
    <section className="admin-card">
      <h2>Agregar agente</h2>
      <form className="admin-form" onSubmit={submit} noValidate>
        <Field label="Nombre" value={form.name} onChange={update('name')} error={fieldErrors.name} autoComplete="off" />
        <Field
          label="Correo"
          type="email"
          value={form.email}
          onChange={update('email')}
          error={fieldErrors.email}
          autoComplete="off"
        />
        <Field
          label="Contraseña inicial (mínimo 8 caracteres)"
          type="password"
          value={form.password}
          onChange={update('password')}
          error={fieldErrors.password}
          autoComplete="new-password"
        />
        <div className="admin-actions">
          <button
            type="submit"
            className="btn-primary"
            disabled={saving || !form.name.trim() || !form.email.trim() || !form.password}
          >
            {saving ? 'Creando…' : 'Crear agente'}
          </button>
        </div>
      </form>
      {created && <p className="alert alert-success">{created}</p>}
      {error && <p className="alert alert-error">{error}</p>}
    </section>
  )
}

function Field({
  label,
  value,
  onChange,
  error,
  type = 'text',
  autoComplete,
}: {
  label: string
  value: string
  onChange: (value: string) => void
  error?: string
  type?: string
  autoComplete?: string
}) {
  return (
    <label>
      {label}
      <input
        type={type}
        value={value}
        autoComplete={autoComplete}
        aria-invalid={Boolean(error)}
        onChange={(e) => onChange(e.target.value)}
      />
      {error && <span className="field-error">{error}</span>}
    </label>
  )
}

function AgentRow({ agent, onChanged }: { agent: User; onChanged: () => void }) {
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function deactivate() {
    setBusy(true)
    setError(null)
    try {
      await apiFetch<User>(`/api/agents/${agent.id}/deactivate`, { method: 'PATCH' })
      setConfirming(false)
      onChanged()
    } catch (err) {
      setError(friendlyError(err))
    } finally {
      setBusy(false)
    }
  }

  const since = toMillis(agent.createdAt)

  return (
    <li className={`agent-row${agent.active ? '' : ' inactive'}`}>
      <div className="agent-info">
        <strong>{agent.name}</strong>
        <span>{agent.email}</span>
        {since !== null && (
          <span className="profile-muted">
            Desde el {new Date(since).toLocaleDateString('es-CO', { day: 'numeric', month: 'long', year: 'numeric' })}
          </span>
        )}
      </div>
      <div className="agent-actions">
        {!agent.active && <span className="tag tag-inactive">Desactivado</span>}
        {agent.active && !confirming && (
          <button type="button" className="btn-secondary danger" onClick={() => setConfirming(true)}>
            Desactivar
          </button>
        )}
      </div>
      {confirming && (
        <div className="confirm-box" role="alertdialog" aria-label={`Confirmar desactivación de ${agent.name}`}>
          <p>
            ¿Desactivar a <strong>{agent.name}</strong>? Perderá el acceso de inmediato (se cerrará su sesión) y sus
            chats abiertos quedarán sin asignar. Esta acción no se puede deshacer desde aquí.
          </p>
          <div className="inline-actions">
            <button type="button" className="btn-danger" disabled={busy} onClick={() => void deactivate()}>
              {busy ? 'Desactivando…' : 'Sí, desactivar'}
            </button>
            <button type="button" className="btn-secondary" disabled={busy} onClick={() => setConfirming(false)}>
              Cancelar
            </button>
          </div>
        </div>
      )}
      {error && <p className="alert alert-error agent-error">{error}</p>}
    </li>
  )
}
