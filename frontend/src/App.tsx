import { Routes, Route, Navigate, useLocation } from 'react-router-dom'
import { useAuth } from './context/AuthContext'
import { useEffect } from 'react'
import LoginPage from './pages/LoginPage'
import MapPage from './pages/MapPage'
import AppsPage from './pages/AppsPage'
import StatsPage from './pages/StatsPage'
import Layout from './components/Layout'

function RequireAuth({ children }: { children: JSX.Element }) {
  const { token, loading } = useAuth()
  if (loading) return <div className="loading"><div className="spinner"></div>加载中...</div>
  if (!token) return <Navigate to="/login" replace />
  return children
}

function RequireNoAuth({ children }: { children: JSX.Element }) {
  const { token, loading } = useAuth()
  if (loading) return <div className="loading"><div className="spinner"></div>加载中...</div>
  if (token) return <Navigate to="/" replace />
  return children
}

function App() {
  const location = useLocation()

  useEffect(() => {
    document.title = '💕 情侣空间 - 实时报备系统'
  }, [location.pathname])

  return (
    <Routes>
      <Route
        path="/login"
        element={
          <RequireNoAuth>
            <LoginPage />
          </RequireNoAuth>
        }
      />
      <Route
        path="/"
        element={
          <RequireAuth>
            <Layout><MapPage /></Layout>
          </RequireAuth>
        }
      />
      <Route
        path="/apps"
        element={
          <RequireAuth>
            <Layout><AppsPage /></Layout>
          </RequireAuth>
        }
      />
      <Route
        path="/stats"
        element={
          <RequireAuth>
            <Layout><StatsPage /></Layout>
          </RequireAuth>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

export default App
