import { describe, expect, it } from 'vitest'
import type { Conversation } from '../api/types'
import { channelClass, initials, statusClass, waitingMinutes, waitingText } from './labels'

describe('etiquetas', () => {
  it('iniciales del avatar', () => {
    expect(initials('Ana María Pérez')).toBe('AM')
    expect(initials('luis')).toBe('LU')
    expect(initials('  ')).toBe('?')
    expect(initials(null)).toBe('?')
  })

  it('clases de canal y estado', () => {
    expect(channelClass('WHATSAPP')).toBe('tag tag-channel-whatsapp')
    expect(statusClass('IN_PROGRESS')).toBe('tag tag-status-in-progress')
  })

  it('"lleva X min sin respuesta" cuenta desde el último mensaje del cliente', () => {
    const conversation = { lastInboundAt: '2026-11-04T10:00:00Z', atRiskSince: '2026-11-04T10:15:00Z' } as Conversation
    const minutes = waitingMinutes(conversation, Date.parse('2026-11-04T10:17:40Z'))
    expect(minutes).toBe(17)
    expect(waitingText(minutes)).toBe('lleva 17 min sin respuesta')
    expect(waitingText(95)).toBe('lleva 95 min sin respuesta')
  })
})
