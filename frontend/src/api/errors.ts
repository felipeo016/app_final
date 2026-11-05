import { ApiError } from './http'

/**
 * Mensaje para el agente, siempre en español y sin detalles técnicos. Se decide por el código estable del
 * backend y, si no se reconoce, por el estado HTTP. Nunca se muestra el mensaje crudo del servidor.
 */
const BY_CODE: Record<string, string> = {
  CONVERSATION_RESOLVED: 'Esta conversación está resuelta. Reábrela para poder responder.',
  ANOTHER_CONVERSATION_OPEN:
    'No se puede reabrir: el cliente ya tiene otra conversación abierta en este canal. Continúa la atención en esa conversación.',
  MESSAGE_NOT_RETRYABLE: 'Este mensaje ya no se puede reintentar. Puede que otro agente ya lo haya reenviado.',
  WHATSAPP_WINDOW_CLOSED:
    'Pasaron más de 24 horas desde el último mensaje del cliente. WhatsApp no permite mensajes libres hasta que el cliente vuelva a escribir.',
  CHANNEL_NOT_AVAILABLE: 'Este canal todavía no está conectado, así que no se puede enviar el mensaje. Pide al dueño de la empresa que lo configure.',
  CHANNEL_NOT_CONFIGURED: 'Este canal todavía no está conectado, así que no se puede enviar el mensaje. Pide al dueño de la empresa que lo configure.',
  CONTACT_WITHOUT_IDENTITY: 'No sabemos a dónde enviar el mensaje: el cliente no tiene un usuario en este canal.',
  CONVERSATION_NOT_FOUND: 'Esta conversación ya no existe.',
  MESSAGE_NOT_FOUND: 'Ese mensaje ya no existe.',
  AGENT_INACTIVE: 'Ese agente está desactivado.',
  ASSIGNMENT_FORBIDDEN: 'No puedes cambiar la asignación de este chat.',
  FORBIDDEN: 'No tienes permiso para hacer esto.',
}

const BY_STATUS: Record<number, string> = {
  0: 'No hay conexión con el servidor. Revisa tu internet e inténtalo de nuevo.',
  403: 'No tienes permiso para hacer esto.',
  404: 'No se encontró lo que buscabas. Puede que se haya eliminado.',
  409: 'La acción choca con un cambio reciente. Revisa la conversación e inténtalo de nuevo.',
  422: 'El canal no permite enviar este mensaje en este momento.',
  501: 'Este canal todavía no está disponible para enviar mensajes.',
}

const GENERIC = 'Algo salió mal. Inténtalo de nuevo en unos segundos.'

export function friendlyError(error: unknown): string {
  if (!(error instanceof ApiError)) return GENERIC
  if (error.code === 'VALIDATION_ERROR') {
    const first = Object.values(error.fieldErrors)[0]
    return first ? `Revisa los datos: ${first.toLowerCase()}.` : 'Revisa los datos ingresados.'
  }
  return BY_CODE[error.code] ?? BY_STATUS[error.status] ?? GENERIC
}
