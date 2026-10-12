import { useCallback, useEffect, useState } from 'react'
import {
  getLogs,
  getMetrics,
  getResult,
  listRuns,
} from '../api/testsApi'
import {
  ApiError,
  isAbortError,
  isActiveRunStatus,
  isTerminalStatus,
  type RunSummaryDto,
  type TestLogsResponse,
  type TestMetricsResponse,
  type TestResultResponse,
  type TestStatus,
} from '../api/types'

export type ArtifactGate =
  | 'waiting-status'
  | 'created'
  | 'active-run'
  | 'terminal'

type SectionError = ApiError | null

function toArtifactGate(status: TestStatus | undefined): ArtifactGate {
  if (status == null) {
    return 'waiting-status'
  }
  if (status === 'CREATED') {
    return 'created'
  }
  if (isActiveRunStatus(status)) {
    return 'active-run'
  }
  if (isTerminalStatus(status)) {
    return 'terminal'
  }
  return 'waiting-status'
}

/**
 * Loads run history whenever status is known, and fetches latest
 * result/metrics/logs only when status is terminal.
 *
 * While QUEUED/RUNNING or CREATED, the detail page should ignore any leftover
 * artifact state (gate !== 'terminal') so a prior run's result is not shown
 * as current. 409 on terminal endpoints is soft-pending with Retry.
 */
export function useTestArtifacts(
  testId: string,
  status: TestStatus | undefined,
) {
  const gate = toArtifactGate(status)

  const [runs, setRuns] = useState<RunSummaryDto[] | null>(null)
  const [historyLoading, setHistoryLoading] = useState(true)
  const [historyError, setHistoryError] = useState<SectionError>(null)
  const [historyRetry, setHistoryRetry] = useState(0)

  const [result, setResult] = useState<TestResultResponse | null>(null)
  const [metrics, setMetrics] = useState<TestMetricsResponse | null>(null)
  const [logs, setLogs] = useState<TestLogsResponse | null>(null)

  const [resultError, setResultError] = useState<SectionError>(null)
  const [metricsError, setMetricsError] = useState<SectionError>(null)
  const [logsError, setLogsError] = useState<SectionError>(null)

  const [resultPending, setResultPending] = useState(false)
  const [metricsPending, setMetricsPending] = useState(false)
  const [logsPending, setLogsPending] = useState(false)

  const [artifactsLoading, setArtifactsLoading] = useState(false)
  const [artifactRetry, setArtifactRetry] = useState(0)

  useEffect(() => {
    if (status == null) {
      return
    }

    let cancelled = false
    const controller = new AbortController()

    void (async () => {
      try {
        const data = await listRuns(testId, controller.signal)
        if (cancelled) {
          return
        }
        setRuns(data)
        setHistoryError(null)
      } catch (cause) {
        if (cancelled || isAbortError(cause)) {
          return
        }
        setRuns(null)
        setHistoryError(
          cause instanceof ApiError
            ? cause
            : new ApiError(0, 'Unexpected error loading run history'),
        )
      } finally {
        if (!cancelled) {
          setHistoryLoading(false)
        }
      }
    })()

    return () => {
      cancelled = true
      controller.abort()
    }
  }, [testId, status, historyRetry])

  useEffect(() => {
    if (status == null || !isTerminalStatus(status)) {
      return
    }

    let cancelled = false
    const controller = new AbortController()

    async function loadSection<T>(
      fetchFn: (id: string, signal?: AbortSignal) => Promise<T>,
      onData: (data: T) => void,
      onError: (error: ApiError) => void,
      onPending409: () => void,
    ) {
      try {
        const data = await fetchFn(testId, controller.signal)
        if (cancelled) {
          return
        }
        onData(data)
      } catch (cause) {
        if (cancelled || isAbortError(cause)) {
          return
        }
        if (cause instanceof ApiError && cause.status === 409) {
          onPending409()
          return
        }
        onError(
          cause instanceof ApiError
            ? cause
            : new ApiError(0, 'Unexpected error loading section'),
        )
      }
    }

    void (async () => {
      setArtifactsLoading(true)
      setResultPending(false)
      setMetricsPending(false)
      setLogsPending(false)

      await Promise.all([
        loadSection(
          getResult,
          (data) => {
            setResult(data)
            setResultError(null)
            setResultPending(false)
          },
          (error) => {
            setResult(null)
            setResultError(error)
            setResultPending(false)
          },
          () => {
            setResult(null)
            setResultError(null)
            setResultPending(true)
          },
        ),
        loadSection(
          getMetrics,
          (data) => {
            setMetrics(data)
            setMetricsError(null)
            setMetricsPending(false)
          },
          (error) => {
            setMetrics(null)
            setMetricsError(error)
            setMetricsPending(false)
          },
          () => {
            setMetrics(null)
            setMetricsError(null)
            setMetricsPending(true)
          },
        ),
        loadSection(
          getLogs,
          (data) => {
            setLogs(data)
            setLogsError(null)
            setLogsPending(false)
          },
          (error) => {
            setLogs(null)
            setLogsError(error)
            setLogsPending(false)
          },
          () => {
            setLogs(null)
            setLogsError(null)
            setLogsPending(true)
          },
        ),
      ])

      if (!cancelled) {
        setArtifactsLoading(false)
      }
    })()

    return () => {
      cancelled = true
      controller.abort()
    }
  }, [testId, status, artifactRetry])

  const retryHistory = useCallback(() => {
    setHistoryLoading(true)
    setHistoryError(null)
    setHistoryRetry((token) => token + 1)
  }, [])

  const retryArtifacts = useCallback(() => {
    setArtifactRetry((token) => token + 1)
  }, [])

  return {
    gate,
    runs,
    historyLoading,
    historyError,
    retryHistory,
    result: gate === 'terminal' ? result : null,
    metrics: gate === 'terminal' ? metrics : null,
    logs: gate === 'terminal' ? logs : null,
    resultError: gate === 'terminal' ? resultError : null,
    metricsError: gate === 'terminal' ? metricsError : null,
    logsError: gate === 'terminal' ? logsError : null,
    resultPending: gate === 'terminal' ? resultPending : false,
    metricsPending: gate === 'terminal' ? metricsPending : false,
    logsPending: gate === 'terminal' ? logsPending : false,
    artifactsLoading: gate === 'terminal' ? artifactsLoading : false,
    retryArtifacts,
  }
}
