// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { AiSettingsCard } from '../admin/SettingsPage'
import type { Message } from '../api/types'
import Composer, { type Suggestion } from '../conversation/Composer'
import MessageList from '../conversation/MessageList'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function renderComposer(onSuggest?: () => Promise<Suggestion>) {
  render(
    <Composer
      replyBlockedReason={null}
      quickReplies={[]}
      onOpenQuickReplies={vi.fn()}
      onSend={vi.fn().mockResolvedValue(undefined)}
      onSuggest={onSuggest}
    />,
  )
  return screen.getByRole('textbox', { name: 'Respuesta al cliente' }) as HTMLTextAreaElement
}

describe('Sugerir respuesta', () => {
  afterEach(cleanup)

  it('muestra "Pensando…" y luego rellena la caja; el agente la edita y la envía él mismo', async () => {
    const user = userEvent.setup()
    let resolve: (s: Suggestion) => void = () => {}
    const onSuggest = vi.fn(() => new Promise<Suggestion>((r) => (resolve = r)))
    const box = renderComposer(onSuggest)

    await user.click(screen.getByRole('button', { name: /Sugerir respuesta/ }))
    expect((screen.getByRole('button', { name: 'Pensando…' }) as HTMLButtonElement).disabled).toBe(true)

    resolve({ canAnswer: true, text: 'Sí, enviamos a Cali por $15.000.' })
    await waitFor(() => expect(box.value).toBe('Sí, enviamos a Cali por $15.000.'))
    expect(screen.getByText(/revísala/)).toBeTruthy()
    // No se envió nada solo
    await user.type(box, ' ¡Gracias!')
    expect(box.value).toBe('Sí, enviamos a Cali por $15.000. ¡Gracias!')
  })

  it('si la información del negocio no alcanza, no rellena nada y lo dice', async () => {
    const user = userEvent.setup()
    const box = renderComposer(() => Promise.resolve({ canAnswer: false, text: null }))

    await user.click(screen.getByRole('button', { name: /Sugerir respuesta/ }))

    expect(await screen.findByText(/no encontró en la información del negocio/)).toBeTruthy()
    expect(box.value).toBe('')
  })

  it('sin IA (apagada) no aparece el botón, y en una nota interna tampoco', async () => {
    renderComposer(undefined)
    expect(screen.queryByRole('button', { name: /Sugerir respuesta/ })).toBeNull()
    cleanup()

    const user = userEvent.setup()
    renderComposer(() => Promise.resolve({ canAnswer: true, text: 'x' }))
    await user.click(screen.getByRole('tab', { name: 'Nota interna' }))
    expect(screen.queryByRole('button', { name: /Sugerir respuesta/ })).toBeNull()
  })
})

describe('mensajes de la IA en el chat', () => {
  beforeAll(() => {
    Object.defineProperty(HTMLElement.prototype, 'scrollHeight', { configurable: true, get: () => 0 })
  })
  afterEach(cleanup)

  const automatic: Message = {
    id: 'm1',
    conversationId: 'c1',
    direction: 'OUTBOUND',
    type: 'TEXT',
    content: '¡Hola! Sí, tenemos la chaqueta en talla S.',
    sentBy: null,
    timestamp: '2026-11-05T10:00:00Z',
    deliveryStatus: 'SENT',
    failureReason: null,
    automatic: true,
  }

  function renderList(messages: Message[], aiTyping: boolean) {
    render(
      <MessageList
        messages={messages}
        hasOlder={false}
        loadingOlder={false}
        onLoadOlder={vi.fn()}
        onRetry={vi.fn()}
        authorName={() => 'Agente'}
        aiTyping={aiTyping}
      />,
    )
  }

  it('las respuestas automáticas llevan la etiqueta "Respuesta automática" y firma IA', () => {
    renderList([automatic, { ...automatic, id: 'm2', automatic: false, sentBy: 'u1', content: 'Te atiendo yo' }], false)

    expect(screen.getAllByText('Respuesta automática')).toHaveLength(1)
    expect(screen.getByText(/^IA ·/)).toBeTruthy()
    expect(screen.queryByText('La IA está escribiendo…')).toBeNull()
  })

  it('muestra "La IA está escribiendo…" mientras prepara la respuesta', () => {
    renderList([], true)
    expect(screen.getByRole('status').textContent).toBe('La IA está escribiendo…')
  })
})

describe('Ajustes de IA', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('guarda el modo y la información del negocio', async () => {
    const user = userEvent.setup()
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (url === '/api/tenant/settings/ai') {
        const body = JSON.parse(String(init?.body))
        return json({ riskAlertMinutes: 15, ...body })
      }
      return json({ state: 'READY', mode: 'AUTO', model: 'qwen2.5:3b' })
    })
    render(<AiSettingsCard initial={{ riskAlertMinutes: 15, aiMode: 'OFF', businessInfo: '' }} />)

    const save = screen.getByRole('button', { name: 'Guardar ajustes de IA' }) as HTMLButtonElement
    expect(save.disabled).toBe(true)
    await user.click(screen.getByRole('radio', { name: /Respuesta automática/ }))
    await user.type(screen.getByRole('textbox', { name: /Información del negocio/ }), 'Envíos a Cali: $15.000')
    await user.click(save)

    expect(await screen.findByText(/ya están activos/)).toBeTruthy()
    const call = fetchMock.mock.calls.find(([url]) => url === '/api/tenant/settings/ai')
    expect(JSON.parse(String(call?.[1]?.body))).toEqual({ aiMode: 'AUTO', businessInfo: 'Envíos a Cali: $15.000' })
    expect(call?.[1]?.method).toBe('PUT')
  })
})
