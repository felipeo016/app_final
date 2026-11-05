import { useMemo, useState, type KeyboardEvent } from 'react'
import { friendlyError } from '../api/errors'
import type { QuickReply } from './quickReplies'
import { slashQuery } from './messages'

/** Respuesta de "Sugerir respuesta": canAnswer = false si la información del negocio no alcanza. */
export interface Suggestion {
  canAnswer: boolean
  text: string | null
}

interface Props {
  /** Motivo por el que no se puede responder al cliente (ventana cerrada, chat resuelto); null si se puede. */
  replyBlockedReason: string | null
  quickReplies: QuickReply[]
  /** Se llama cada vez que se abre el selector (con el botón o con "/"). */
  onOpenQuickReplies: () => void
  onSend: (text: string, asNote: boolean) => Promise<void>
  /** "Sugerir respuesta": solo si la IA está en Sugerencias o Respuesta automática; sin ella no hay botón. */
  onSuggest?: () => Promise<Suggestion>
}

const SUGGESTION_HINT = 'Sugerencia de la IA: revísala y edítala si hace falta antes de enviarla.'
const NO_SUGGESTION_HINT =
  'La IA no encontró en la información del negocio cómo responder esto, así que no inventa nada. Respóndelo tú.'

/**
 * Caja de respuesta con dos modos: responder al cliente o nota interna. Las respuestas rápidas se abren con
 * el botón o escribiendo "/" al inicio (lo que sigue filtra); ↑/↓ eligen, Enter inserta, Esc cierra.
 */
export default function Composer({ replyBlockedReason, quickReplies, onOpenQuickReplies, onSend, onSuggest }: Props) {
  const [text, setText] = useState('')
  const [asNote, setAsNote] = useState(false)
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [pickerOpen, setPickerOpen] = useState(false)
  const [highlight, setHighlight] = useState(0)
  const [thinking, setThinking] = useState(false)
  const [aiHint, setAiHint] = useState<string | null>(null)

  const query = slashQuery(text)
  const showPicker = pickerOpen || query !== null
  const filtered = useMemo(() => {
    const term = (query ?? '').trim()
    if (!term) return quickReplies
    return quickReplies.filter(
      (reply) => reply.title.toLowerCase().includes(term) || reply.text.toLowerCase().includes(term),
    )
  }, [quickReplies, query])

  const blocked = !asNote && replyBlockedReason !== null
  const canSend = text.trim().length > 0 && !sending && !blocked

  async function suggest() {
    if (!onSuggest || thinking) return
    setThinking(true)
    setError(null)
    setAiHint(null)
    try {
      const suggestion = await onSuggest()
      if (suggestion.canAnswer && suggestion.text) {
        // Rellena la caja: el agente la revisa, la edita y la envía él mismo
        setText(suggestion.text)
        setAiHint(SUGGESTION_HINT)
      } else {
        setAiHint(NO_SUGGESTION_HINT)
      }
    } catch (err) {
      setError(friendlyError(err))
    } finally {
      setThinking(false)
    }
  }

  function choose(reply: QuickReply) {
    setText(reply.text)
    setPickerOpen(false)
  }

  async function submit() {
    if (!canSend) return
    setSending(true)
    setError(null)
    try {
      await onSend(text.trim(), asNote)
      setText('')
      setAiHint(null)
    } catch (err) {
      setError(friendlyError(err))
    } finally {
      setSending(false)
    }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (showPicker && filtered.length > 0) {
      if (event.key === 'ArrowDown') {
        event.preventDefault()
        setHighlight((h) => (h + 1) % filtered.length)
        return
      }
      if (event.key === 'ArrowUp') {
        event.preventDefault()
        setHighlight((h) => (h - 1 + filtered.length) % filtered.length)
        return
      }
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault()
        choose(filtered[highlight] ?? filtered[0])
        return
      }
    }
    if (showPicker && event.key === 'Escape') {
      event.preventDefault()
      setPickerOpen(false)
      if (query !== null) setText('')
      return
    }
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      void submit()
    }
  }

  return (
    <div className={`composer${asNote ? ' composer-note' : ''}`}>
      <div className="composer-modes" role="tablist" aria-label="Tipo de mensaje">
        <button type="button" role="tab" aria-selected={!asNote} className={!asNote ? 'active' : ''} onClick={() => setAsNote(false)}>
          Responder al cliente
        </button>
        <button type="button" role="tab" aria-selected={asNote} className={asNote ? 'active' : ''} onClick={() => setAsNote(true)}>
          Nota interna
        </button>
        <button
          type="button"
          className="quick-toggle"
          onClick={() => {
            if (!showPicker) onOpenQuickReplies()
            setPickerOpen((open) => !open)
            setHighlight(0)
          }}
          aria-expanded={showPicker}
        >
          Respuestas rápidas
        </button>
        {onSuggest && !asNote && !blocked && (
          <button type="button" className="ai-suggest" onClick={() => void suggest()} disabled={thinking || sending}>
            {thinking ? 'Pensando…' : '✨ Sugerir respuesta'}
          </button>
        )}
      </div>

      {showPicker && (
        <ul className="quick-picker" role="listbox" aria-label="Respuestas rápidas">
          {filtered.length === 0 ? (
            <li className="quick-empty">
              {quickReplies.length === 0
                ? 'Aún no hay respuestas rápidas. El dueño de la empresa puede crearlas.'
                : 'Ninguna respuesta rápida coincide.'}
            </li>
          ) : (
            filtered.map((reply, index) => (
              <li key={reply.id} role="option" aria-selected={index === highlight}>
                <button
                  type="button"
                  className={index === highlight ? 'highlighted' : ''}
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => choose(reply)}
                >
                  <strong>{reply.title}</strong>
                  <span>{reply.text}</span>
                </button>
              </li>
            ))
          )}
        </ul>
      )}

      {aiHint && !asNote && (
        <p className="composer-hint ai-hint" role="status">
          {aiHint}
        </p>
      )}
      {asNote && <p className="composer-hint note-hint">La nota solo la ven los agentes; el cliente no la recibe.</p>}
      {blocked && (
        <p className="composer-hint blocked-hint" role="status">
          {replyBlockedReason} Puedes dejar una nota interna.
        </p>
      )}
      {error && (
        <p className="composer-error" role="alert">
          {error}
        </p>
      )}

      <div className="composer-row">
        <textarea
          aria-label={asNote ? 'Nota interna' : 'Respuesta al cliente'}
          placeholder={
            asNote ? 'Escribe una nota para el equipo…' : 'Escribe una respuesta… (escribe / para respuestas rápidas)'
          }
          value={text}
          rows={2}
          maxLength={4096}
          disabled={blocked}
          onChange={(e) => {
            const value = e.target.value
            if (slashQuery(value) !== null && slashQuery(text) === null) onOpenQuickReplies()
            setText(value)
            setHighlight(0) // el filtro cambió: la selección vuelve a la primera opción
          }}
          onKeyDown={handleKeyDown}
        />
        <button type="button" className="btn-primary" onClick={() => void submit()} disabled={!canSend}>
          {sending ? 'Enviando…' : asNote ? 'Guardar nota' : 'Enviar'}
        </button>
      </div>
    </div>
  )
}
