import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { apiFetch, setUnauthorizedHandler } from '../api/http'
import type { AuthResponse } from '../api/types'
import { AuthContext, type AuthContextValue, type RegisterInput } from './AuthContext'
import { clearSession, loadSession, NOTICES, saveSession, sessionFromAuth, type Session } from './session'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(() => loadSession())

  const logout = useCallback((notice?: string) => {
    clearSession(notice)
    setSession(null)
  }, [])

  const start = useCallback((auth: AuthResponse) => {
    const next = sessionFromAuth(auth)
    saveSession(next)
    setSession(next)
  }, [])

  const login = useCallback(
    async (email: string, password: string) => {
      start(await apiFetch<AuthResponse>('/api/auth/login', { method: 'POST', body: { email, password }, auth: false }))
    },
    [start],
  )

  const register = useCallback(
    async (input: RegisterInput) => {
      start(await apiFetch<AuthResponse>('/api/auth/register', { method: 'POST', body: input, auth: false }))
    },
    [start],
  )

  // Cualquier 401 de una petición autenticada cierra la sesión y lleva al login con aviso.
  useEffect(() => {
    setUnauthorizedHandler(() => logout(NOTICES.unauthorized))
    return () => setUnauthorizedHandler(() => {})
  }, [logout])

  // Al llegar el vencimiento del JWT se cierra la sesión aunque no haya peticiones en curso.
  useEffect(() => {
    if (!session) return
    const remaining = session.expiresAt - Date.now()
    const timer = window.setTimeout(() => logout(NOTICES.expired), Math.max(remaining, 0))
    return () => window.clearTimeout(timer)
  }, [session, logout])

  const value = useMemo<AuthContextValue>(
    () => ({ session, login, register, logout }),
    [session, login, register, logout],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
