import type { TestStatus } from '../api/types'

const LABELS: Record<TestStatus, string> = {
  CREATED: 'CREATED',
  QUEUED: 'QUEUED',
  RUNNING: 'RUNNING',
  PASSED: 'PASSED',
  FAILED: 'FAILED',
  CANCELLED: 'CANCELLED',
}

interface StatusBadgeProps {
  status: TestStatus
}

export function StatusBadge({ status }: StatusBadgeProps) {
  const label = LABELS[status] ?? status
  return (
    <span className={`status-badge status-${status.toLowerCase()}`}>
      <span className="status-badge-text">{label}</span>
    </span>
  )
}
