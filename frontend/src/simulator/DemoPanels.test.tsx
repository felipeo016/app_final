// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LiveDemoPanel, RestoreDemoPanel } from './DemoPanels'
import { liveDemoProgress } from './liveDemo'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('paneles de la demo', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('"Restaurar datos demo" pide confirmación y solo entonces llama al backend', async () => {
    const user = userEvent.setup()
    fetchMock.mockResolvedValue(json({ restored: true, company: 'Tienda Demo' }))
    render(
      <MemoryRouter initialEntries={['/simulador']}>
        <Routes>
          <Route path="/simulador" element={<RestoreDemoPanel />} />
          <Route path="/" element={<p>Bandeja</p>} />
        </Routes>
      </MemoryRouter>,
    )

    await user.click(screen.getByRole('button', { name: 'Restaurar datos demo' }))
    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.getByRole('alertdialog', { name: 'Confirmar restauración' }).textContent).toContain(
      'no se puede deshacer',
    )

    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(fetchMock).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Restaurar datos demo' }))
    await user.click(screen.getByRole('button', { name: 'Sí, restaurar' }))
    expect(fetchMock).toHaveBeenCalledWith('/api/dev/demo/restore', expect.objectContaining({ method: 'POST' }))
    // Al terminar lleva a la bandeja
    await waitFor(() => expect(screen.getByText('Bandeja')).toBeTruthy())
  })

  it('si la empresa no es la demo, muestra un mensaje claro', async () => {
    const user = userEvent.setup()
    fetchMock.mockResolvedValue(json({ status: 403, code: 'NOT_DEMO_COMPANY', message: 'x' }, 403))
    render(
      <MemoryRouter>
        <RestoreDemoPanel />
      </MemoryRouter>,
    )

    await user.click(screen.getByRole('button', { name: 'Restaurar datos demo' }))
    await user.click(screen.getByRole('button', { name: 'Sí, restaurar' }))

    expect((await screen.findByText(/Tienda Demo/, { selector: '.alert' })).textContent).toContain(
      'Inicia sesión con una cuenta demo',
    )
  })

  it('la demo en vivo muestra su estado y se inicia con un clic', async () => {
    const user = userEvent.setup()
    const stopped = {
      running: false,
      messagesSent: 0,
      nextMessageAt: null,
      scriptLength: 18,
      currentStep: 0,
      lastCustomer: null,
    }
    const running = {
      running: true,
      messagesSent: 3,
      nextMessageAt: new Date(Date.now() + 25_000).toISOString(),
      scriptLength: 18,
      currentStep: 5,
      lastCustomer: 'Camila Ospina',
    }
    fetchMock.mockImplementation(async (url: string) => json(url.endsWith('/start') ? running : stopped))
    render(<LiveDemoPanel />)

    const start = await screen.findByRole('button', { name: 'Iniciar demo en vivo' })
    await waitFor(() => expect((start as HTMLButtonElement).disabled).toBe(false))
    await user.click(start)

    expect(await screen.findByText('EN VIVO')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Detener' })).toBeTruthy()
    expect(screen.getByRole('status').textContent).toMatch(
      /^Demo en vivo activa: mensaje 5 de 18 \(último: Camila Ospina\) · próximo en 2\d s$/,
    )
  })

  it('recién iniciada, sin mensajes todavía, lo dice', () => {
    const status = {
      running: true,
      messagesSent: 0,
      nextMessageAt: null,
      scriptLength: 18,
      currentStep: 0,
      lastCustomer: null,
    }
    expect(liveDemoProgress(status, 31)).toBe('Demo en vivo activa: esperando el primer mensaje · próximo en 31 s')
  })
})
