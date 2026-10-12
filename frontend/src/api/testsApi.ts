import { apiRequest } from './client'
import type {
  CreateTestRequest,
  RunAcceptedResponse,
  TestResponse,
} from './types'

export function listTests(signal?: AbortSignal): Promise<TestResponse[]> {
  return apiRequest<TestResponse[]>('/api/tests', { signal })
}

export function getTest(
  id: string,
  signal?: AbortSignal,
): Promise<TestResponse> {
  return apiRequest<TestResponse>(`/api/tests/${encodeURIComponent(id)}`, {
    signal,
  })
}

export function createTest(
  body: CreateTestRequest,
  signal?: AbortSignal,
): Promise<TestResponse> {
  return apiRequest<TestResponse>('/api/tests', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  })
}

export function runTest(
  id: string,
  signal?: AbortSignal,
): Promise<RunAcceptedResponse> {
  return apiRequest<RunAcceptedResponse>(
    `/api/tests/${encodeURIComponent(id)}/run`,
    {
      method: 'POST',
      signal,
    },
  )
}
