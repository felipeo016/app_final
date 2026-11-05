// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import type { Message } from '../api/types'
import MessageList from './MessageList'

function failed(failureCode: string, failureReason: string): Message {
  return {
    id: 'm1',
    conversationId: 'c1',
    direction: 'OUTBOUND',
    type: 'TEXT',
    content: 'Tu pedido sale hoy',
    sentBy: 'u1',
    timestamp: '2026-11-04T10:00:00Z',
    deliveryStatus: 'FAILED',
    failureReason,
    failureCode,
  }
}

function renderList(message: Message) {
  const onRetry = vi.fn().mockResolvedValue(undefined)
  render(
    <MessageList
      messages={[message]}
      hasOlder={false}
      loadingOlder={false}
      onLoadOlder={vi.fn()}
      onRetry={onRetry}
      authorName={() => 'Tú'}
    />,
  )
  return onRetry
}

describe('reintentar un envío fallido', () => {
  beforeAll(() => {
    // jsdom no implementa el layout; la lista solo lee estas medidas
    Object.defineProperty(HTMLElement.prototype, 'scrollHeight', { configurable: true, get: () => 0 })
  })
  afterEach(cleanup)

  it('si el canal no confirmó el envío, pide confirmación antes de reenviar', async () => {
    const user = userEvent.setup()
    const onRetry = renderList(
      failed('CHANNEL_SEND_UNCONFIRMED', 'No se pudo confirmar el envío; puede que el cliente sí lo haya recibido'),
    )

    expect(screen.getByText('No se pudo confirmar el envío; puede que el cliente sí lo haya recibido')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Reintentar' }))

    expect(onRetry).not.toHaveBeenCalled()
    expect(screen.getByRole('alertdialog', { name: 'Confirmar reenvío' }).textContent).toContain('dos veces')

    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(onRetry).not.toHaveBeenCalled()
    expect(screen.queryByRole('alertdialog')).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Reintentar' }))
    await user.click(screen.getByRole('button', { name: 'Sí, reenviar' }))
    expect(onRetry).toHaveBeenCalledWith('m1')
  })

  it('si el envío falló con seguridad, reintenta sin preguntar', async () => {
    const user = userEvent.setup()
    const onRetry = renderList(failed('CHANNEL_SEND_FAILED', 'No se pudo enviar el mensaje por WhatsApp'))

    expect(screen.getByText('No se envió: No se pudo enviar el mensaje por WhatsApp')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Reintentar' }))

    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(onRetry).toHaveBeenCalledWith('m1')
  })
})
