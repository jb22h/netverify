interface ErrorBannerProps {
  title?: string
  message: string
  details?: string[]
  onRetry?: () => void
}

export function ErrorBanner({
  title = 'Error',
  message,
  details,
  onRetry,
}: ErrorBannerProps) {
  return (
    <div className="error-banner" role="alert">
      <div className="error-banner-body">
        <strong>{title}</strong>
        <p>{message}</p>
        {details && details.length > 0 ? (
          <ul>
            {details.map((detail) => (
              <li key={detail}>{detail}</li>
            ))}
          </ul>
        ) : null}
      </div>
      {onRetry ? (
        <button type="button" className="btn" onClick={onRetry}>
          Retry
        </button>
      ) : null}
    </div>
  )
}
