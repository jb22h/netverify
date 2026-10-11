import { apiRequest } from './client'
import type { TestResponse } from './types'

export function listTests(): Promise<TestResponse[]> {
  return apiRequest<TestResponse[]>('/api/tests')
}
