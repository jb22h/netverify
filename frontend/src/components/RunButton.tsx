import { isActiveRunStatus, type TestStatus } from '../api/types'

interface RunButtonProps {
  status: TestStatus
  submitting: boolean
  onRun: () => void
}

export function RunButton({ status, submitting, onRun }: RunButtonProps) {
  const busy = submitting || isActiveRunStatus(status)
  const label = submitting
    ? 'Queuing…'
    : isActiveRunStatus(status)
      ? 'Run in progress'
      : 'Run test'

  return (
    <button
      type="button"
      className="btn btn-primary"
      disabled={busy}
      onClick={onRun}
      aria-disabled={busy}
    >
      {label}
    </button>
  )
}
