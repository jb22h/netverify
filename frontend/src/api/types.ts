/** Mirrors control-plane TestStatus / TestResponse JSON for list views. */

export type TestStatus =
  | 'CREATED'
  | 'QUEUED'
  | 'RUNNING'
  | 'PASSED'
  | 'FAILED'
  | 'CANCELLED'

export interface TestResponse {
  id: string
  name: string
  scenario: string
  count: number
  clients: number | null
  status: TestStatus
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

/** Mirrors control-plane ErrorResponse JSON. */
export interface ErrorResponseBody {
  timestamp?: string
  status: number
  error?: string
  message?: string
  details?: string[]
}

export class ApiError extends Error {
  readonly status: number
  readonly details: string[]

  constructor(status: number, message: string, details: string[] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.details = details
  }
}
