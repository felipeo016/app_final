import { describe, expect, it } from 'vitest'
import { NOTICES } from '../auth/session'
import { isAuthError, sessionEndNotice, tenantTopic } from './realtimeClient'
import type { IFrame } from '@stomp/stompjs'

describe('WebSocket', () => {
  it('usa el tópico de la empresa', () => {
    expect(tenantTopic('t1')).toBe('/topic/tenants/t1/events')
  })

  it('el cierre por "Sesión vencida" termina la sesión con su aviso', () => {
    expect(sessionEndNotice(1008, 'Sesión vencida: inicia sesión de nuevo')).toBe(NOTICES.expired)
    expect(sessionEndNotice(1008, 'Usuario desactivado')).toBe(NOTICES.deactivated)
  })

  it('un corte de red no termina la sesión: se reconecta', () => {
    expect(sessionEndNotice(1006, '')).toBeNull()
    expect(sessionEndNotice(1000, '')).toBeNull()
    expect(sessionEndNotice(1011, 'Sesión vencida')).toBeNull()
  })

  it('reconoce el ERROR de CONNECT rechazado', () => {
    const frame = (message?: string) => ({ headers: message ? { message } : {} }) as unknown as IFrame
    expect(isAuthError(frame('No autorizado: inicia sesión de nuevo'))).toBe(true)
    expect(isAuthError(frame('Solo puedes suscribirte a los eventos de tu empresa'))).toBe(false)
    expect(isAuthError(frame())).toBe(false)
  })
})
