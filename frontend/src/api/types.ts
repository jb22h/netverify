/** Mirrors control-plane TestStatus / TestResponse JSON. */

export type TestStatus =
  | 'CREATED'
  | 'QUEUED'
  | 'RUNNING'
  | 'PASSED'
  | 'FAILED'
  | 'CANCELLED'

/** Scenarios accepted by the HTTP control plane (not latency_injection). */
export const HTTP_SCENARIOS = [
  'basic_ping_pong',
  'connect_fail_no_server',
  'server_crash_mid_session',
  'concurrent_load',
] as const

export type HttpScenario = (typeof HTTP_SCENARIOS)[number]

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

/** Mirrors CreateTestRequest JSON. */
export interface CreateTestRequest {
  name: string
  scenario: HttpScenario
  count: number
  clients?: number
}

/** Mirrors RunAcceptedResponse JSON (id is the test id). */
export interface RunAcceptedResponse {
  id: string
  status: TestStatus
  queuedAt: string
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

export function isAbortError(cause: unknown): boolean {
  return (
    (cause instanceof DOMException && cause.name === 'AbortError') ||
    (cause instanceof Error && cause.name === 'AbortError')
  )
}

export function isActiveRunStatus(status: TestStatus): boolean {
  return status === 'QUEUED' || status === 'RUNNING'
}

export function isTerminalStatus(status: TestStatus): boolean {
  return status === 'PASSED' || status === 'FAILED' || status === 'CANCELLED'
}
