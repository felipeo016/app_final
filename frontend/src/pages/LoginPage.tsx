import { useEffect, useState, type FormEvent } from 'react'
import { Link, Navigate } from 'react-router-dom'
import { ApiError } from '../api/http'
import { useAuth } from '../auth/AuthContext'
import { dismissNotice, peekNotice } from '../auth/session'

export default function LoginPage() {
  const { session, login } = useAuth()
  // El aviso (sesión vencida, usuario desactivado...) se lee al montar y se borra del almacenamiento.
  const [notice] = useState(() => peekNotice())
  useEffect(() => dismissNotice(), [])
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  if (session) return <Navigate to="/" replace />

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      await login(email.trim(), password)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'No se pudo iniciar sesión')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="auth-page">
      <form className="auth-card" onSubmit={handleSubmit} noValidate>
        <h1 className="auth-brand">Unicanal</h1>
        <p className="auth-tagline">Cero mensajes perdidos</p>
        <h2>Iniciar sesión</h2>

        {notice && (
          <p className="alert alert-warning" role="status">
            {notice}
          </p>
        )}
        {error && (
          <p className="alert alert-error" role="alert">
            {error}
          </p>
        )}

        <label>
          Correo
          <input type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          Contraseña
          <input
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </label>
        <button type="submit" className="btn-primary" disabled={submitting || !email || !password}>
          {submitting ? 'Entrando…' : 'Entrar'}
        </button>
        <p className="auth-switch">
          ¿Tu empresa aún no usa Unicanal? <Link to="/registro">Regístrala</Link>
        </p>
      </form>
    </div>
  )
}
