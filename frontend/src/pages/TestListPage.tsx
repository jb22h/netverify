import { useEffect, useState } from 'react'
import { ApiError } from '../api/types'
import type { TestResponse } from '../api/types'
import { listTests } from '../api/testsApi'
import { EmptyState } from '../components/EmptyState'
import { ErrorBanner } from '../components/ErrorBanner'
import { LoadingBlock } from '../components/LoadingBlock'
import { StatusBadge } from '../components/StatusBadge'

export function TestListPage() {
  const [tests, setTests] = useState<TestResponse[] | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [loading, setLoading] = useState(true)
  const [reloadToken, setReloadToken] = useState(0)

  useEffect(() => {
    let cancelled = false

    void (async () => {
      try {
        const data = await listTests()
        if (cancelled) {
          return
        }
        setTests(data)
        setError(null)
      } catch (cause) {
        if (cancelled) {
          return
        }
        setTests(null)
        if (cause instanceof ApiError) {
          setError(cause)
        } else {
          setError(new ApiError(0, 'Unexpected error loading tests'))
        }
      } finally {
        if (!cancelled) {
          setLoading(false)
        }
      }
    })()

    return () => {
      cancelled = true
    }
  }, [reloadToken])

  function retry() {
    setLoading(true)
    setError(null)
    setReloadToken((token) => token + 1)
  }

  return (
    <section className="page">
      <header className="page-header">
        <h1>Tests</h1>
        <p className="page-subtitle">
          Definitions from the NetVerify control plane (latest status per test).
        </p>
      </header>

      {loading ? <LoadingBlock label="Loading tests…" /> : null}

      {!loading && error ? (
        <ErrorBanner
          title={error.status > 0 ? `HTTP ${error.status}` : 'Network error'}
          message={error.message}
          details={error.details}
          onRetry={retry}
        />
      ) : null}

      {!loading && !error && tests && tests.length === 0 ? (
        <EmptyState
          title="No tests yet"
          message="Create a test through the API; the list will appear here."
        />
      ) : null}

      {!loading && !error && tests && tests.length > 0 ? (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Scenario</th>
                <th scope="col">Count</th>
                <th scope="col">Clients</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {tests.map((test) => (
                <tr key={test.id}>
                  <td data-label="Name">{test.name}</td>
                  <td data-label="Scenario">
                    <code>{test.scenario}</code>
                  </td>
                  <td data-label="Count">{test.count}</td>
                  <td data-label="Clients">
                    {test.clients == null ? '—' : test.clients}
                  </td>
                  <td data-label="Status">
                    <StatusBadge status={test.status} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </section>
  )
}
