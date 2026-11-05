import { toMillis, type AuthResponse, type Tenant, type User } from '../api/types'

/**
 * Sesión del usuario. Se guarda en sessionStorage (NO en localStorage): vive solo en esta pestaña y se
 * borra al cerrarla, así el JWT no queda en el disco del equipo compartido de la tienda.
 */
export interface Session {
  token: string
  expiresAt: number
  user: User
  tenant: Tenant
}

const SESSION_KEY = 'unicanal.session'
const NOTICE_KEY = 'unicanal.notice'

export const NOTICES = {
  expired: 'Tu sesión venció. Inicia sesión de nuevo.',
  unauthorized: 'Tu sesión ya no es válida. Inicia sesión de nuevo.',
  deactivated: 'Tu usuario fue desactivado. Habla con el dueño de la empresa.',
} as const

function storage(): Storage | null {
  try {
    return globalThis.sessionStorage ?? null
  } catch {
    return null
  }
}

export function sessionFromAuth(auth: AuthResponse): Session {
  const expiresAt = toMillis(auth.expiresAt) ?? Date.now()
  return { token: auth.token, expiresAt, user: auth.user, tenant: auth.tenant }
}

/** Sesión guardada, o null si no hay o ya venció (en ese caso se borra). */
export function loadSession(now: number = Date.now()): Session | null {
  const raw = storage()?.getItem(SESSION_KEY)
  if (!raw) return null
  try {
    const session = JSON.parse(raw) as Session
    if (!session.token || !session.user || !session.tenant || session.expiresAt <= now) {
      storage()?.removeItem(SESSION_KEY)
      return null
    }
    return session
  } catch {
    storage()?.removeItem(SESSION_KEY)
    return null
  }
}

export function saveSession(session: Session): void {
  storage()?.setItem(SESSION_KEY, JSON.stringify(session))
}

/** Borra la sesión y, si se indica, deja un aviso para mostrarlo en el login. */
export function clearSession(notice?: string): void {
  const store = storage()
  store?.removeItem(SESSION_KEY)
  if (notice) store?.setItem(NOTICE_KEY, notice)
}

/** Aviso pendiente para el login, sin borrarlo (puro: seguro en el modo estricto de React). */
export function peekNotice(): string | null {
  return storage()?.getItem(NOTICE_KEY) ?? null
}

/** Borra el aviso una vez mostrado, para que no reaparezca al recargar. */
export function dismissNotice(): void {
  storage()?.removeItem(NOTICE_KEY)
}
