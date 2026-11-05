import { useState, type FormEvent } from 'react'
import { Link, Navigate } from 'react-router-dom'
import { ApiError } from '../api/http'
import { useAuth, type RegisterInput } from '../auth/AuthContext'

const EMPTY: RegisterInput = { companyName: '', ownerName: '', email: '', password: '' }

export default function RegisterPage() {
  const { session, register } = useAuth()
  const [form, setForm] = useState<RegisterInput>(EMPTY)
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [submitting, setSubmitting] = useState(false)

  if (session) return <Navigate to="/" replace />

  function update(field: keyof RegisterInput, value: string) {
    setForm((current) => ({ ...current, [field]: value }))
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setFieldErrors({})
    if (form.password.length < 8) {
      setFieldErrors({ password: 'Debe tener al menos 8 caracteres' })
      return
    }
    setSubmitting(true)
    try {
      await register({
        companyName: form.companyName.trim(),
        ownerName: form.ownerName.trim(),
        email: form.email.trim(),
        password: form.password,
      })
    } catch (err) {
      if (err instanceof ApiError) {
        setFieldErrors(err.fieldErrors)
        setError(err.message)
      } else {
        setError('No se pudo registrar la empresa')
      }
    } finally {
      setSubmitting(false)
    }
  }

  const field = (name: keyof RegisterInput, label: string, type: string, autoComplete: string) => (
    <label>
      {label}
      <input
        type={type}
        autoComplete={autoComplete}
        value={form[name]}
        onChange={(e) => update(name, e.target.value)}
        aria-invalid={Boolean(fieldErrors[name])}
        required
      />
      {fieldErrors[name] && <span className="field-error">{fieldErrors[name]}</span>}
    </label>
  )

  const complete = form.companyName && form.ownerName && form.email && form.password

  return (
    <div className="auth-page">
      <form className="auth-card" onSubmit={handleSubmit} noValidate>
        <h1 className="auth-brand">Unicanal</h1>
        <p className="auth-tagline">Cero mensajes perdidos</p>
        <h2>Registra tu empresa</h2>
        {error && (
          <p className="alert alert-error" role="alert">
            {error}
          </p>
        )}
        {field('companyName', 'Nombre de la empresa', 'text', 'organization')}
        {field('ownerName', 'Tu nombre', 'text', 'name')}
        {field('email', 'Correo', 'email', 'email')}
        {field('password', 'Contraseña (mínimo 8 caracteres)', 'password', 'new-password')}
        <button type="submit" className="btn-primary" disabled={submitting || !complete}>
          {submitting ? 'Creando…' : 'Crear empresa'}
        </button>
        <p className="auth-switch">
          ¿Ya tienes cuenta? <Link to="/login">Inicia sesión</Link>
        </p>
      </form>
    </div>
  )
}
