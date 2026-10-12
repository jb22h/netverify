import type { ApiError, TestMetricsResponse } from '../api/types'
import { EmptyState } from './EmptyState'
import { ErrorBanner } from './ErrorBanner'
import { LoadingBlock } from './LoadingBlock'

function MetricValue({ value }: { value: unknown }) {
  if (value === null) {
    return <span className="metric-null">null</span>
  }
  if (typeof value === 'string') {
    return <code>{value}</code>
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return <code>{String(value)}</code>
  }
  if (Array.isArray(value)) {
    if (value.length === 0) {
      return <span className="metric-null">[]</span>
    }
    return (
      <ul className="metric-list">
        {value.map((item, index) => (
          <li key={index}>
            <MetricValue value={item} />
          </li>
        ))}
      </ul>
    )
  }
  if (typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>).sort(
      ([a], [b]) => a.localeCompare(b),
    )
    if (entries.length === 0) {
      return <span className="metric-null">{'{}'}</span>
    }
    return (
      <dl className="metric-nested">
        {entries.map(([key, nested]) => (
          <div key={key} className="metric-entry">
            <dt>
              <code>{key}</code>
            </dt>
            <dd>
              <MetricValue value={nested} />
            </dd>
          </div>
        ))}
      </dl>
    )
  }
  return <span className="metric-null">{String(value)}</span>
}

interface MetricsPanelProps {
  metrics: TestMetricsResponse | null
  loading: boolean
  error: ApiError | null
  pending409: boolean
  gatedMessage?: string
  onRetry: () => void
}

export function MetricsPanel({
  metrics,
  loading,
  error,
  pending409,
  gatedMessage,
  onRetry,
}: MetricsPanelProps) {
  const entries =
    metrics == null
      ? []
      : Object.entries(metrics.metrics).sort(([a], [b]) => a.localeCompare(b))

  return (
    <section className="panel" aria-labelledby="metrics-heading">
      <h2 id="metrics-heading">Metrics</h2>
      <p className="panel-subtitle">
        {'Heterogeneous runner metrics from GET /api/tests/{id}/metrics.'}
      </p>

      {gatedMessage ? <p className="panel-note">{gatedMessage}</p> : null}

      {!gatedMessage && loading ? (
        <LoadingBlock label="Loading metrics…" />
      ) : null}

      {!gatedMessage && !loading && pending409 ? (
        <ErrorBanner
          title="Not available yet"
          message="Metrics are not ready yet (HTTP 409). The run may still be finalizing."
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

      {!gatedMessage && !loading && !error && !pending409 && metrics ? (
        <>
          <p className="panel-meta">
            Status: <code>{metrics.status}</code>
            {metrics.failureCategory != null
              ? ` · failureCategory: ${metrics.failureCategory}`
              : null}
          </p>
          {entries.length === 0 ? (
            <EmptyState
              title="No metrics"
              message="The runner reported an empty metrics map for this run."
            />
          ) : (
            <dl className="metric-root">
              {entries.map(([key, value]) => (
                <div key={key} className="metric-entry">
                  <dt>
                    <code>{key}</code>
                  </dt>
                  <dd>
                    <MetricValue value={value} />
                  </dd>
                </div>
              ))}
            </dl>
          )}
        </>
      ) : null}
    </section>
  )
}
