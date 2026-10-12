import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ErrorBanner } from '../components/ErrorBanner'
import { LoadingBlock } from '../components/LoadingBlock'
import { LogsPanel } from '../components/LogsPanel'
import { MetricsPanel } from '../components/MetricsPanel'
import { ResultPanel } from '../components/ResultPanel'
import { RunButton } from '../components/RunButton'
import { RunHistoryTable } from '../components/RunHistoryTable'
import { StatusBadge } from '../components/StatusBadge'
import { useTestArtifacts } from '../hooks/useTestArtifacts'
import { useTestDetail } from '../hooks/useTestDetail'
import { isActiveRunStatus } from '../api/types'

function formatInstant(value: string | null): string {
  if (!value) {
    return '—'
  }
  return value
}

function artifactGateMessage(
  gate: ReturnType<typeof useTestArtifacts>['gate'],
): string | undefined {
  if (gate === 'created') {
    return 'No terminal artifacts yet. Trigger a run to produce result, metrics, and logs.'
  }
  if (gate === 'active-run') {
    return 'A run is in progress. Latest result, metrics, and logs will load when the run reaches a terminal status.'
  }
  return undefined
}

function TestDetailBody({
  testId,
  onReload,
}: {
  testId: string
  onReload: () => void
}) {
  const {
    test,
    loading,
    error,
    pollError,
    runError,
    runSubmitting,
    triggerRun,
  } = useTestDetail(testId)

  const artifacts = useTestArtifacts(testId, test?.status)
  const gatedMessage = artifactGateMessage(artifacts.gate)

  if (loading) {
    return <LoadingBlock label="Loading test…" />
  }

  if (error) {
    const notFound = error.status === 404
    return (
      <>
        <ErrorBanner
          title={
            notFound
              ? 'Test not found'
              : error.status > 0
                ? `HTTP ${error.status}`
                : 'Network error'
          }
          message={
            notFound
              ? 'No test exists for this id. It may have been removed or the URL is wrong.'
              : error.message
          }
          details={notFound ? undefined : error.details}
          onRetry={notFound ? undefined : onReload}
        />
        {notFound ? (
          <p className="detail-nav">
            <Link className="btn" to="/">
              Back to test list
            </Link>
          </p>
        ) : null}
      </>
    )
  }

  if (!test) {
    return (
      <ErrorBanner
        title="Error"
        message="Test data unavailable."
        onRetry={onReload}
      />
    )
  }

  const polling = isActiveRunStatus(test.status) && pollError == null

  return (
    <>
      <header className="page-header detail-header">
        <div>
          <h1>{test.name}</h1>
          <p className="page-subtitle mono-id">id: {test.id}</p>
        </div>
        <StatusBadge status={test.status} />
      </header>

      <dl className="detail-grid">
        <div>
          <dt>Scenario</dt>
          <dd>
            <code>{test.scenario}</code>
          </dd>
        </div>
        <div>
          <dt>Count</dt>
          <dd>{test.count}</dd>
        </div>
        <div>
          <dt>Clients</dt>
          <dd>{test.clients == null ? '—' : test.clients}</dd>
        </div>
        <div>
          <dt>Status</dt>
          <dd>
            <StatusBadge status={test.status} />
          </dd>
        </div>
        <div>
          <dt>Created</dt>
          <dd>{formatInstant(test.createdAt)}</dd>
        </div>
        <div>
          <dt>Started</dt>
          <dd>{formatInstant(test.startedAt)}</dd>
        </div>
        <div>
          <dt>Finished</dt>
          <dd>{formatInstant(test.finishedAt)}</dd>
        </div>
      </dl>

      <div className="detail-actions">
        <RunButton
          status={test.status}
          submitting={runSubmitting}
          onRun={() => {
            void triggerRun().then((ok) => {
              if (ok) {
                onReload()
              }
            })
          }}
        />
        {polling ? (
          <p className="poll-note" role="status" aria-live="polite">
            Polling status every 2s while QUEUED or RUNNING…
          </p>
        ) : null}
      </div>

      {runError ? (
        <ErrorBanner
          title={
            runError.status === 503
              ? 'Dispatch unavailable'
              : runError.status === 409
                ? 'Conflict'
                : runError.status > 0
                  ? `HTTP ${runError.status}`
                  : 'Network error'
          }
          message={runError.message}
          details={runError.details}
        />
      ) : null}

      {pollError ? (
        <ErrorBanner
          title={
            pollError.status > 0
              ? `Poll failed (HTTP ${pollError.status})`
              : 'Poll failed'
          }
          message={pollError.message}
          details={pollError.details}
          onRetry={onReload}
        />
      ) : null}

      <RunHistoryTable
        runs={artifacts.runs}
        loading={artifacts.historyLoading}
        error={artifacts.historyError}
        onRetry={artifacts.retryHistory}
      />

      <ResultPanel
        result={artifacts.result}
        loading={artifacts.artifactsLoading}
        error={artifacts.resultError}
        pending409={artifacts.resultPending}
        gatedMessage={gatedMessage}
        onRetry={artifacts.retryArtifacts}
      />

      <MetricsPanel
        metrics={artifacts.metrics}
        loading={artifacts.artifactsLoading}
        error={artifacts.metricsError}
        pending409={artifacts.metricsPending}
        gatedMessage={gatedMessage}
        onRetry={artifacts.retryArtifacts}
      />

      <LogsPanel
        logs={artifacts.logs}
        loading={artifacts.artifactsLoading}
        error={artifacts.logsError}
        pending409={artifacts.logsPending}
        gatedMessage={gatedMessage}
        onRetry={artifacts.retryArtifacts}
      />
    </>
  )
}

export function TestDetailPage() {
  const { id } = useParams<{ id: string }>()
  const [reloadToken, setReloadToken] = useState(0)

  if (!id) {
    return (
      <section className="page">
        <ErrorBanner
          title="Test not found"
          message="Missing test id in the URL."
        />
        <p className="detail-nav">
          <Link className="btn" to="/">
            Back to test list
          </Link>
        </p>
      </section>
    )
  }

  return (
    <section className="page">
      <p className="back-link">
        <Link to="/">← All tests</Link>
      </p>
      <TestDetailBody
        key={`${id}:${reloadToken}`}
        testId={id}
        onReload={() => setReloadToken((token) => token + 1)}
      />
    </section>
  )
}
