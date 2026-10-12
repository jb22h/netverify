import type { ApiError, RunSummaryDto } from '../api/types'
import { EmptyState } from './EmptyState'
import { ErrorBanner } from './ErrorBanner'
import { LoadingBlock } from './LoadingBlock'
import { StatusBadge } from './StatusBadge'

function cell(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  return String(value)
}

interface RunHistoryTableProps {
  runs: RunSummaryDto[] | null
  loading: boolean
  error: ApiError | null
  onRetry: () => void
}

export function RunHistoryTable({
  runs,
  loading,
  error,
  onRetry,
}: RunHistoryTableProps) {
  return (
    <section className="panel" aria-labelledby="run-history-heading">
      <h2 id="run-history-heading">Run history</h2>
      <p className="panel-subtitle">
        {'Compact newest-first history from GET /api/tests/{id}/runs.'}
      </p>

      {loading ? <LoadingBlock label="Loading run history…" /> : null}

      {!loading && error ? (
        <ErrorBanner
          title={error.status > 0 ? `HTTP ${error.status}` : 'Network error'}
          message={error.message}
          details={error.details}
          onRetry={onRetry}
        />
      ) : null}

      {!loading && !error && runs && runs.length === 0 ? (
        <EmptyState
          title="No runs yet"
          message="Trigger a run to populate history."
        />
      ) : null}

      {!loading && !error && runs && runs.length > 0 ? (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">Run id</th>
                <th scope="col">Status</th>
                <th scope="col">Queued</th>
                <th scope="col">Started</th>
                <th scope="col">Finished</th>
                <th scope="col">Exit</th>
                <th scope="col">Failure</th>
              </tr>
            </thead>
            <tbody>
              {runs.map((run) => (
                <tr key={run.id}>
                  <td data-label="Run id">
                    <code className="wrap-code">{run.id}</code>
                  </td>
                  <td data-label="Status">
                    <StatusBadge status={run.status} />
                  </td>
                  <td data-label="Queued">{cell(run.queuedAt)}</td>
                  <td data-label="Started">{cell(run.startedAt)}</td>
                  <td data-label="Finished">{cell(run.finishedAt)}</td>
                  <td data-label="Exit">{cell(run.exitCode)}</td>
                  <td data-label="Failure">{cell(run.failureCategory)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </section>
  )
}
