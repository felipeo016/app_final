import { describe, expect, it } from 'vitest'
import type { Conversation } from '../api/types'
import { assignmentControl, formatCop, formatCopInput, parseCop } from './profileRules'

const conversation = (assignedTo: Conversation['assignedTo']) => ({ assignedTo }) as Conversation

describe('control de asignación', () => {
  it('el OWNER siempre elige de la lista', () => {
    expect(assignmentControl('OWNER', 'yo', conversation(null)).kind).toBe('select')
    expect(assignmentControl('OWNER', 'yo', conversation({ id: 'otro', name: 'Luis' })).kind).toBe('select')
  })

  it('un AGENT solo puede tomar un chat libre o soltar el suyo', () => {
    expect(assignmentControl('AGENT', 'yo', conversation(null))).toEqual({ kind: 'take' })
    expect(assignmentControl('AGENT', 'yo', conversation({ id: 'yo', name: 'Ana' }))).toEqual({ kind: 'release' })
    expect(assignmentControl('AGENT', 'yo', conversation({ id: 'otro', name: 'Luis' }))).toEqual({
      kind: 'readonly',
      assigneeName: 'Luis',
    })
  })
})

describe('montos en pesos colombianos', () => {
  // Intl separa "$" y la cifra con un espacio duro (U+00A0)
  const normalize = (text: string) => text.replace(/ /g, ' ')

  it('formato es-CO sin decimales', () => {
    expect(normalize(formatCop(150000))).toBe('$ 150.000')
    expect(normalize(formatCop(1234567.6))).toBe('$ 1.234.568')
    expect(normalize(formatCop(0))).toBe('$ 0')
  })

  it('lee lo que escribe el agente como pesos enteros', () => {
    expect(parseCop('150000')).toBe(150000)
    expect(parseCop('150.000')).toBe(150000)
    expect(parseCop('$ 1.250.000')).toBe(1250000)
    expect(parseCop('')).toBeNull()
    expect(parseCop('abc')).toBeNull()
    expect(formatCopInput('1250000')).toBe('1.250.000')
  })
})
