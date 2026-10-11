import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { TestListPage } from './pages/TestListPage'

export default function App() {
  return (
    <BrowserRouter>
      <div className="app-shell">
        <header className="app-header">
          <p className="brand">NetVerify</p>
          <p className="brand-tag">Control plane dashboard</p>
        </header>
        <main className="app-main">
          <Routes>
            <Route path="/" element={<TestListPage />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  )
}
