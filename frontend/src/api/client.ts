import { ApiError, type ErrorResponseBody } from './types'

async function parseError(response: Response): Promise<ApiError> {
  let message = response.statusText || `HTTP ${response.status}`
  let details: string[] = []

  try {
    const body = (await response.json()) as ErrorResponseBody
    if (typeof body.message === 'string' && body.message.trim() !== '') {
      message = body.message
    }
    if (Array.isArray(body.details)) {
      details = body.details.filter((d): d is string => typeof d === 'string')
    }
  } catch {
    // Non-JSON error body; keep statusText / status message.
  }

  return new ApiError(response.status, message, details)
}

export async function apiRequest<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, {
      ...init,
      headers: {
        Accept: 'application/json',
        ...(init?.headers ?? {}),
      },
    })
  } catch (cause) {
    const reason =
      cause instanceof Error && cause.message
        ? cause.message
        : 'network request failed'
    throw new ApiError(0, `Network error: ${reason}`)
  }

  if (!response.ok) {
    throw await parseError(response)
  }

  if (response.status === 204) {
    return undefined as T
  }

  return (await response.json()) as T
}
