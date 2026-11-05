import { loadSession } from '../auth/session'

/** Error de la API con el formato de ErrorResponse del backend. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors: Record<string, string>

  constructor(status: number, code: string, message: string, fieldErrors: Record<string, string> = {}) {
    super(message)
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
  }
}

type UnauthorizedHandler = () => void

let onUnauthorized: UnauthorizedHandler = () => {}

/** Lo registra AuthProvider: un 401 en una petición autenticada cierra la sesión. */
export function setUnauthorizedHandler(handler: UnauthorizedHandler): void {
  onUnauthorized = handler
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  /** false en login/registro: un 401 ahí es "credenciales incorrectas", no una sesión vencida. */
  auth?: boolean
  signal?: AbortSignal
}

export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, auth = true, signal } = options
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const token = auth ? loadSession()?.token : undefined
  if (token) headers.Authorization = `Bearer ${token}`

  let response: Response
  try {
    response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError(0, 'NETWORK_ERROR', 'No se pudo conectar con el servidor')
  }

  if (response.status === 401 && auth) {
    onUnauthorized()
  }
  if (!response.ok) {
    throw await toApiError(response)
  }
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const body = (await response.json()) as { code?: string; message?: string; fieldErrors?: Record<string, string> }
    return new ApiError(response.status, body.code ?? 'ERROR', body.message ?? 'Ocurrió un error', body.fieldErrors)
  } catch {
    return new ApiError(response.status, 'ERROR', 'Ocurrió un error inesperado')
  }
}
