import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthResponse } from '../api/types'
import { clearSession, dismissNotice, loadSession, NOTICES, peekNotice, saveSession, sessionFromAuth } from './session'

class MemoryStorage implements Storage {
  private data = new Map<string, string>()
  get length() {
    return this.data.size
  }
  clear() {
    this.data.clear()
  }
  getItem(key: string) {
    return this.data.get(key) ?? null
  }
  key(index: number) {
    return [...this.data.keys()][index] ?? null
  }
  removeItem(key: string) {
    this.data.delete(key)
  }
  setItem(key: string, value: string) {
    this.data.set(key, value)
  }
}

const auth = (expiresAt: string): AuthResponse => ({
  token: 'jwt-de-prueba',
  expiresAt,
  user: { id: 'u1', tenantId: 't1', name: 'Ana', email: 'ana@test.com', role: 'OWNER', active: true, createdAt: '' },
  tenant: { id: 't1', name: 'Acme' },
})

describe('sesión', () => {
  let session: MemoryStorage
  let local: MemoryStorage

  beforeEach(() => {
    session = new MemoryStorage()
    local = new MemoryStorage()
    vi.stubGlobal('sessionStorage', session)
    vi.stubGlobal('localStorage', local)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('guarda el JWT en sessionStorage y nunca en localStorage', () => {
    saveSession(sessionFromAuth(auth('2999-01-01T00:00:00Z')))

    expect(session.length).toBe(1)
    expect(session.getItem('unicanal.session')).toContain('jwt-de-prueba')
    expect(local.length).toBe(0)
    expect(loadSession()?.token).toBe('jwt-de-prueba')
  })

  it('una sesión vencida no se carga y se borra', () => {
    saveSession(sessionFromAuth(auth('2026-11-04T10:00:00Z')))

    expect(loadSession(Date.parse('2026-11-04T10:00:00Z'))).toBeNull()
    expect(session.length).toBe(0)
  })

  it('al cerrar la sesión deja el aviso para el login, que se puede leer varias veces hasta descartarlo', () => {
    saveSession(sessionFromAuth(auth('2999-01-01T00:00:00Z')))

    clearSession(NOTICES.expired)

    expect(loadSession()).toBeNull()
    expect(peekNotice()).toBe(NOTICES.expired)
    expect(peekNotice()).toBe(NOTICES.expired) // modo estricto de React: leer no consume
    dismissNotice()
    expect(peekNotice()).toBeNull()
  })

  it('un contenido corrupto se descarta', () => {
    session.setItem('unicanal.session', '{no es json')
    expect(loadSession()).toBeNull()
    expect(session.length).toBe(0)
  })
})
