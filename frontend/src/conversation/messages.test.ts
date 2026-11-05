import { describe, expect, it } from 'vitest'
import type { Message } from '../api/types'
import { formatRemaining, mergeMessages, slashQuery, windowInfo } from './messages'

function message(id: string, timestamp: string, overrides: Partial<Message> = {}): Message {
  return {
    id,
    conversationId: 'c1',
    direction: 'OUTBOUND',
    type: 'TEXT',
    content: id,
    sentBy: 'u1',
    timestamp,
    deliveryStatus: 'SENT',
    failureReason: null,
    ...overrides,
  }
}

describe('mensajes del chat', () => {
  it('une páginas y eventos sin duplicar, del más antiguo al más reciente', () => {
    const page0 = [message('m3', '2026-11-04T10:03:00Z'), message('m2', '2026-11-04T10:02:00Z')]
    const page1 = [message('m2', '2026-11-04T10:02:00Z'), message('m1', '2026-11-04T10:01:00Z')]

    const merged = mergeMessages(mergeMessages([], page0), page1)

    expect(merged.map((m) => m.id)).toEqual(['m1', 'm2', 'm3'])
  })

  it('el estado de entrega solo avanza: un evento tardío no hace retroceder un leído', () => {
    const read = [message('m1', '2026-11-04T10:00:00Z', { deliveryStatus: 'READ' })]
    const late = message('m1', '2026-11-04T10:00:00Z', { deliveryStatus: 'DELIVERED' })

    expect(mergeMessages(read, [late])[0].deliveryStatus).toBe('READ')
  })

  it('un reintento sí reemplaza al FAILED', () => {
    const failed = [message('m1', '2026-11-04T10:00:00Z', { deliveryStatus: 'FAILED', failureReason: 'caído' })]
    const retried = message('m1', '2026-11-04T10:00:00Z', { deliveryStatus: 'SENT' })

    const merged = mergeMessages(failed, [retried])
    expect(merged[0].deliveryStatus).toBe('SENT')
    expect(merged[0].failureReason).toBeNull()
  })
})

describe('ventana de 24 h', () => {
  const now = Date.parse('2026-11-04T12:00:00Z')

  it('solo aplica a WhatsApp', () => {
    expect(windowInfo('INSTAGRAM', null, true, now)).toEqual({ applies: false, open: true, remainingMs: Infinity })
  })

  it('cuenta lo que queda y se cierra al vencer', () => {
    const open = windowInfo('WHATSAPP', '2026-11-04T14:30:00Z', true, now)
    expect(open.open).toBe(true)
    expect(formatRemaining(open.remainingMs)).toBe('2 h 30 min')

    const closed = windowInfo('WHATSAPP', '2026-11-04T11:59:00Z', true, now)
    expect(closed).toEqual({ applies: true, open: false, remainingMs: 0 })
  })

  it('sin mensajes del cliente la ventana está cerrada', () => {
    expect(windowInfo('WHATSAPP', null, false, now).open).toBe(false)
  })
})

describe('atajo de respuestas rápidas', () => {
  it('"/" al inicio abre el selector y lo que sigue filtra', () => {
    expect(slashQuery('/')).toBe('')
    expect(slashQuery('/Salu')).toBe('salu')
    expect(slashQuery('hola /saludo')).toBeNull()
    expect(slashQuery('/saludo y más')).toBeNull()
  })
})
