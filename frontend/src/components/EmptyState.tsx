interface EmptyStateProps {
  title: string
  message?: string
}

export function EmptyState({ title, message }: EmptyStateProps) {
  return (
    <div className="empty-state">
      <p className="empty-state-title">{title}</p>
      {message ? <p className="empty-state-message">{message}</p> : null}
    </div>
  )
}
