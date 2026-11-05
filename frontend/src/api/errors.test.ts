import { describe, expect, it } from 'vitest'
import { friendlyError } from './errors'
import { ApiError } from './http'

describe('mensajes de error para el agente', () => {
  it('409, 422 y 501 se traducen a textos claros en español, nunca al mensaje técnico', () => {
    const technical = 'Write operation error on server. E11000 duplicate key'
    const reopen = friendlyError(new ApiError(409, 'ANOTHER_CONVERSATION_OPEN', technical))
    const window = friendlyError(new ApiError(422, 'WHATSAPP_WINDOW_CLOSED', technical))
    const channel = friendlyError(new ApiError(501, 'CHANNEL_NOT_AVAILABLE', technical))

    expect(reopen).toContain('ya tiene otra conversación abierta')
    expect(window).toContain('24 horas')
    expect(channel).toContain('todavía no está conectado')
    for (const text of [reopen, window, channel]) expect(text).not.toContain('E11000')
  })

  it('un código desconocido cae al texto del estado HTTP', () => {
    expect(friendlyError(new ApiError(409, 'OTRO', 'x'))).toContain('cambio reciente')
    expect(friendlyError(new ApiError(422, 'OTRO', 'x'))).toContain('no permite enviar')
    expect(friendlyError(new ApiError(501, 'OTRO', 'x'))).toContain('todavía no está disponible')
    expect(friendlyError(new ApiError(0, 'NETWORK_ERROR', 'x'))).toContain('No hay conexión')
  })

  it('un error que no es de la API nunca muestra su detalle', () => {
    expect(friendlyError(new TypeError('Cannot read properties of undefined'))).toBe(
      'Algo salió mal. Inténtalo de nuevo en unos segundos.',
    )
  })

  it('los errores de validación muestran el primer campo', () => {
    expect(friendlyError(new ApiError(400, 'VALIDATION_ERROR', 'x', { text: 'Máximo 4096 caracteres' }))).toBe(
      'Revisa los datos: máximo 4096 caracteres.',
    )
  })
})
