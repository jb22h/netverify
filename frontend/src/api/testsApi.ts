import { apiRequest } from './client'
import type {
  CreateTestRequest,
  RunAcceptedResponse,
  RunSummaryDto,
  TestLogsResponse,
  TestMetricsResponse,
  TestResponse,
  TestResultResponse,
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

export function listRuns(
  testId: string,
  signal?: AbortSignal,
): Promise<RunSummaryDto[]> {
  return apiRequest<RunSummaryDto[]>(
    `/api/tests/${encodeURIComponent(testId)}/runs`,
    { signal },
  )
}

export function getResult(
  testId: string,
  signal?: AbortSignal,
): Promise<TestResultResponse> {
  return apiRequest<TestResultResponse>(
    `/api/tests/${encodeURIComponent(testId)}/results`,
    { signal },
  )
}

export function getMetrics(
  testId: string,
  signal?: AbortSignal,
): Promise<TestMetricsResponse> {
  return apiRequest<TestMetricsResponse>(
    `/api/tests/${encodeURIComponent(testId)}/metrics`,
    { signal },
  )
}

export function getLogs(
  testId: string,
  signal?: AbortSignal,
): Promise<TestLogsResponse> {
  return apiRequest<TestLogsResponse>(
    `/api/tests/${encodeURIComponent(testId)}/logs`,
    { signal },
  )
}
