import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { createTest } from '../api/testsApi'
import {
  ApiError,
  HTTP_SCENARIOS,
  type HttpScenario,
} from '../api/types'
import { ErrorBanner } from './ErrorBanner'

export function TestCreateForm() {
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [scenario, setScenario] = useState<HttpScenario>('basic_ping_pong')
  const [count, setCount] = useState(10)
  const [clients, setClients] = useState(10)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)

  const showClients = scenario === 'concurrent_load'

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setError(null)

    const body = {
      name: name.trim(),
      scenario,
      count,
      ...(showClients ? { clients } : {}),
    }

    try {
      const created = await createTest(body)
      navigate(`/tests/${created.id}`)
    } catch (cause) {
      if (cause instanceof ApiError) {
        setError(cause)
      } else {
        setError(new ApiError(0, 'Unexpected error creating test'))
      }
      setSubmitting(false)
    }
  }

  return (
    <section className="create-panel" aria-labelledby="create-test-heading">
      <h2 id="create-test-heading">Create test</h2>
      <p className="page-subtitle">
        Uses the existing HTTP control-plane create contract. Validation errors
        come from the API.
      </p>

      {error ? (
        <ErrorBanner
          title={error.status > 0 ? `HTTP ${error.status}` : 'Network error'}
          message={error.message}
          details={error.details}
        />
      ) : null}

      <form className="create-form" onSubmit={(e) => void onSubmit(e)}>
        <div className="form-field">
          <label htmlFor="test-name">Name</label>
          <input
            id="test-name"
            name="name"
            type="text"
            required
            maxLength={200}
            value={name}
            onChange={(e) => setName(e.target.value)}
            disabled={submitting}
            autoComplete="off"
          />
        </div>

        <div className="form-field">
          <label htmlFor="test-scenario">Scenario</label>
          <select
            id="test-scenario"
            name="scenario"
            value={scenario}
            onChange={(e) => setScenario(e.target.value as HttpScenario)}
            disabled={submitting}
          >
            {HTTP_SCENARIOS.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </div>

        <div className="form-field">
          <label htmlFor="test-count">Count</label>
          <input
            id="test-count"
            name="count"
            type="number"
            required
            min={1}
            max={1_000_000}
            value={count}
            onChange={(e) => setCount(Number(e.target.value))}
            disabled={submitting}
          />
        </div>

        {showClients ? (
          <div className="form-field">
            <label htmlFor="test-clients">Clients</label>
            <input
              id="test-clients"
              name="clients"
              type="number"
              required
              min={1}
              max={1000}
              value={clients}
              onChange={(e) => setClients(Number(e.target.value))}
              disabled={submitting}
            />
            <p className="field-hint">
              Used for concurrent_load (API default is 10 if omitted).
            </p>
          </div>
        ) : null}

        <div className="form-actions">
          <button type="submit" className="btn btn-primary" disabled={submitting}>
            {submitting ? 'Creating…' : 'Create test'}
          </button>
        </div>
      </form>
    </section>
  )
}
