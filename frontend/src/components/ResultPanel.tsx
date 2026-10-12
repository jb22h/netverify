import type { ApiError, TestResultResponse } from '../api/types'
import { ErrorBanner } from './ErrorBanner'
import { LoadingBlock } from './LoadingBlock'
import { StatusBadge } from './StatusBadge'

interface ResultPanelProps {
  result: TestResultResponse | null
  loading: boolean
  error: ApiError | null
  pending409: boolean
  gatedMessage?: string
  onRetry: () => void
}

export function ResultPanel({
  result,
  loading,
  error,
  pending409,
  gatedMessage,
  onRetry,
}: ResultPanelProps) {
  return (
    <section className="panel" aria-labelledby="result-heading">
      <h2 id="result-heading">Latest result</h2>
      <p className="panel-subtitle">
        {'Latest terminal outcome from GET /api/tests/{id}/results.'}
      </p>

      {gatedMessage ? <p className="panel-note">{gatedMessage}</p> : null}

      {!gatedMessage && loading ? (
        <LoadingBlock label="Loading result…" />
      ) : null}

      {!gatedMessage && !loading && pending409 ? (
        <ErrorBanner
          title="Not available yet"
          message="Result is not ready yet (HTTP 409). The run may still be finalizing."
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

      {!gatedMessage && !loading && !error && !pending409 && result ? (
        <div className="result-body">
          <div className="result-banner">
            <StatusBadge status={result.status} />
            {result.result != null ? (
              <span
                className={
                  result.result.passed ? 'pass-label' : 'fail-label'
                }
              >
                {result.result.passed ? 'PASS' : 'FAIL'}
              </span>
            ) : (
              <span className="muted-label">No parsed runner result</span>
            )}
          </div>

          <dl className="detail-grid compact">
            <div>
              <dt>Exit code</dt>
              <dd>{result.exitCode == null ? '—' : result.exitCode}</dd>
            </div>
            <div>
              <dt>Failure category</dt>
              <dd>
                {result.failureCategory == null ? '—' : result.failureCategory}
              </dd>
            </div>
            <div>
              <dt>Started</dt>
              <dd>{result.startedAt ?? '—'}</dd>
            </div>
            <div>
              <dt>Finished</dt>
              <dd>{result.finishedAt ?? '—'}</dd>
            </div>
            {result.result != null ? (
              <>
                <div>
                  <dt>Runner name</dt>
                  <dd>
                    <code>{result.result.name}</code>
                  </dd>
                </div>
                <div>
                  <dt>duration_s</dt>
                  <dd>{result.result.duration_s}</dd>
                </div>
                <div>
                  <dt>client_exit_code</dt>
                  <dd>
                    {result.result.client_exit_code == null
                      ? '—'
                      : result.result.client_exit_code}
                  </dd>
                </div>
              </>
            ) : null}
          </dl>

          {result.result != null && result.result.failures.length > 0 ? (
            <div className="failures-block">
              <h3>Failures</h3>
              <ul>
                {result.result.failures.map((failure) => (
                  <li key={failure}>{failure}</li>
                ))}
              </ul>
            </div>
          ) : null}
        </div>
      ) : null}
    </section>
  )
}
