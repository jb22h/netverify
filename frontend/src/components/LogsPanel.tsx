import type { ApiError, TestLogsResponse } from '../api/types'
import { ErrorBanner } from './ErrorBanner'
import { LoadingBlock } from './LoadingBlock'

function StreamBlock({ label, text }: { label: string; text: string }) {
  const empty = text.length === 0
  return (
    <div className="log-stream">
      <h3>{label}</h3>
      <pre className="log-pre" tabIndex={0}>
        {empty ? '(empty)' : text}
      </pre>
    </div>
  )
}

interface LogsPanelProps {
  logs: TestLogsResponse | null
  loading: boolean
  error: ApiError | null
  pending409: boolean
  gatedMessage?: string
  onRetry: () => void
}

export function LogsPanel({
  logs,
  loading,
  error,
  pending409,
  gatedMessage,
  onRetry,
}: LogsPanelProps) {
  return (
    <section className="panel" aria-labelledby="logs-heading">
      <h2 id="logs-heading">Logs</h2>
      <p className="panel-subtitle">
        {'Latest stdout/stderr from GET /api/tests/{id}/logs.'}
      </p>

      {gatedMessage ? <p className="panel-note">{gatedMessage}</p> : null}

      {!gatedMessage && loading ? <LoadingBlock label="Loading logs…" /> : null}

      {!gatedMessage && !loading && pending409 ? (
        <ErrorBanner
          title="Not available yet"
          message="Logs are not ready yet (HTTP 409). The run may still be finalizing."
          onRetry={onRetry}
        />
      ) : null}

      {!gatedMessage && !loading && error ? (
        <ErrorBanner
          title={error.status > 0 ? `HTTP ${error.status}` : 'Network error'}
          message={error.message}
          details={error.details}
          onRetry={onRetry}
        />
      ) : null}

      {!gatedMessage && !loading && !error && !pending409 && logs ? (
        <div className="log-grid">
          <StreamBlock label="stdout" text={logs.stdout} />
          <StreamBlock label="stderr" text={logs.stderr} />
        </div>
      ) : null}
    </section>
  )
}
