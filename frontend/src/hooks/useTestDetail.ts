import { useCallback, useEffect, useState } from 'react'
import { getTest, runTest } from '../api/testsApi'
import {
  ApiError,
  isAbortError,
  isActiveRunStatus,
  type TestResponse,
} from '../api/types'

const POLL_MS = 2000

/**
 * Loads one test by id and polls while status is QUEUED/RUNNING.
 *
 * Polling uses recursive setTimeout so the next GET is scheduled only after
 * the previous one finishes (no overlapping requests). AbortController cancels
 * in-flight GETs on unmount / id change; a cancelled flag blocks post-cleanup
 * setState. StrictMode remounts cleanly via effect cleanup.
 *
 * Remount the consumer with `key={testId}` (and bump a reload key after Run /
 * Retry) to reset loading state without synchronous setState at effect entry.
 */
export function useTestDetail(testId: string) {
  const [test, setTest] = useState<TestResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<ApiError | null>(null)
  const [pollError, setPollError] = useState<ApiError | null>(null)
  const [runError, setRunError] = useState<ApiError | null>(null)
  const [runSubmitting, setRunSubmitting] = useState(false)

  useEffect(() => {
    let cancelled = false
    let timeoutId: ReturnType<typeof setTimeout> | undefined
    let abortController: AbortController | null = null

    function clearTimer() {
      if (timeoutId !== undefined) {
        clearTimeout(timeoutId)
        timeoutId = undefined
      }
    }

    function schedulePoll() {
      clearTimer()
      timeoutId = setTimeout(() => {
        void tick(true)
      }, POLL_MS)
    }

    async function tick(isPoll: boolean) {
      if (cancelled) {
        return
      }

      abortController?.abort()
      const controller = new AbortController()
      abortController = controller

      try {
        const data = await getTest(testId, controller.signal)
        if (cancelled) {
          return
        }
        setTest(data)
        setError(null)
        setPollError(null)
        setLoading(false)

        if (isActiveRunStatus(data.status)) {
          schedulePoll()
        }
      } catch (cause) {
        if (cancelled || isAbortError(cause)) {
          return
        }
        const apiError =
          cause instanceof ApiError
            ? cause
            : new ApiError(0, 'Unexpected error loading test')

        if (isPoll) {
          setPollError(apiError)
          setLoading(false)
          return
        }

        setTest(null)
        setError(apiError)
        setLoading(false)
      }
    }

    void tick(false)

    return () => {
      cancelled = true
      clearTimer()
      abortController?.abort()
    }
  }, [testId])

  const triggerRun = useCallback(async () => {
    setRunSubmitting(true)
    setRunError(null)
    try {
      await runTest(testId)
      return true
    } catch (cause) {
      if (isAbortError(cause)) {
        return false
      }
      if (cause instanceof ApiError) {
        if (cause.status === 503) {
          setRunError(
            new ApiError(
              503,
              `Could not queue run: ${cause.message}`,
              cause.details,
            ),
          )
        } else {
          setRunError(cause)
        }
      } else {
        setRunError(new ApiError(0, 'Unexpected error starting run'))
      }
      return false
    } finally {
      setRunSubmitting(false)
    }
  }, [testId])

  return {
    test,
    loading,
    error,
    pollError,
    runError,
    runSubmitting,
    triggerRun,
  }
}
