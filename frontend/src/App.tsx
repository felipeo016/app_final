import type { ReactNode } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import AgentsPage from './admin/AgentsPage'
import SettingsPage from './admin/SettingsPage'
import { useAuth } from './auth/AuthContext'
import { AuthProvider } from './auth/AuthProvider'
import InboxPage from './inbox/InboxPage'
import LoginPage from './pages/LoginPage'
import RegisterPage from './pages/RegisterPage'
import SimulatorPage from './simulator/SimulatorPage'

function RequireSession({ children }: { children: ReactNode }) {
  const { session } = useAuth()
  return session ? children : <Navigate to="/login" replace />
}

/** Páginas de administración: un AGENT que llegue por URL vuelve a la bandeja. */
function RequireOwner({ children }: { children: ReactNode }) {
  const { session } = useAuth()
  if (!session) return <Navigate to="/login" replace />
  return session.user.role === 'OWNER' ? children : <Navigate to="/" replace />
}

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/registro" element={<RegisterPage />} />
          <Route
            path="/"
            element={
              <RequireSession>
                <InboxPage />
              </RequireSession>
            }
          />
          <Route
            path="/agentes"
            element={
              <RequireOwner>
                <AgentsPage />
              </RequireOwner>
            }
          />
          <Route
            path="/ajustes"
            element={
              <RequireOwner>
                <SettingsPage />
              </RequireOwner>
            }
          />
          <Route
            path="/simulador"
            element={
              <RequireSession>
                <SimulatorPage />
              </RequireSession>
            }
          />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  )
}
