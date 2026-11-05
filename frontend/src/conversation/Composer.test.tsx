// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Composer from './Composer'
import type { QuickReply } from './quickReplies'

const REPLIES: QuickReply[] = [
  { id: 'q1', title: 'Saludo', text: '¡Hola! ¿En qué te ayudamos?' },
  { id: 'q2', title: 'Consulta de pedido', text: 'Con gusto revisamos tu pedido. ¿Nos compartes el número?' },
]

function renderComposer(onSend = vi.fn().mockResolvedValue(undefined)) {
  render(
    <Composer replyBlockedReason={null} quickReplies={REPLIES} onOpenQuickReplies={vi.fn()} onSend={onSend} />,
  )
  return { box: screen.getByRole('textbox', { name: 'Respuesta al cliente' }) as HTMLTextAreaElement, onSend }
}

describe('respuestas rápidas en la caja de respuesta', () => {
  afterEach(cleanup)

  it('al elegir con "/consulta" + Enter el atajo se reemplaza por completo', async () => {
    const user = userEvent.setup()
    const { box } = renderComposer()

    await user.type(box, '/consulta')
    expect(screen.getByRole('listbox', { name: 'Respuestas rápidas' })).toBeTruthy()
    await user.keyboard('{Enter}')

    expect(box.value).toBe('Con gusto revisamos tu pedido. ¿Nos compartes el número?')
    expect(box.value).not.toContain('/')
    expect(box.value).not.toContain('consulta')
    expect(screen.queryByRole('listbox')).toBeNull()
  })

  it('al elegir con el ratón tampoco queda nada del atajo, y se envía solo el texto de la respuesta', async () => {
    const user = userEvent.setup()
    const { box, onSend } = renderComposer()

    await user.type(box, '/consulta')
    await user.click(screen.getByRole('button', { name: /Consulta de pedido/ }))
    expect(box.value).toBe('Con gusto revisamos tu pedido. ¿Nos compartes el número?')

    await user.keyboard('{Enter}')
    expect(onSend).toHaveBeenCalledWith('Con gusto revisamos tu pedido. ¿Nos compartes el número?', false)
  })

  it('Esc cierra el selector y borra el atajo a medio escribir', async () => {
    const user = userEvent.setup()
    const { box, onSend } = renderComposer()

    await user.type(box, '/consulta')
    await user.keyboard('{Escape}')

    expect(box.value).toBe('')
    expect(screen.queryByRole('listbox')).toBeNull()
    expect(onSend).not.toHaveBeenCalled()
  })
})
