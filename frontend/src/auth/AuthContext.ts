import { createContext, useContext } from 'react'
import type { Session } from './session'

export interface RegisterInput {
  companyName: string
  ownerName: string
  email: string
  password: string
}

export interface AuthContextValue {
  session: Session | null
  login: (email: string, password: string) => Promise<void>
  register: (input: RegisterInput) => Promise<void>
  /** Cierra la sesión; el aviso, si lo hay, se muestra en el login. */
  logout: (notice?: string) => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth debe usarse dentro de AuthProvider')
  return value
}
