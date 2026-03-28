import React from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import MainLayout from './layouts/MainLayout'
import { useAuthStore } from './store/authStore'
import Login from './pages/Auth/Login'
import Register from './pages/Auth/Register'

// Placeholder Dashboards (to be implemented with high-fidelity)
const Dashboard = () => (
  <section className="space-y-6 pt-4 animate-in fade-in slide-in-from-bottom-4 duration-700">
    <div className="space-y-1">
      <h2 className="text-3xl font-bold font-headline tracking-tight text-on-surface">Hello, User</h2>
      <p className="text-on-surface-variant font-body">Welcome back to WhiskerWatch.</p>
    </div>
    <div className="bg-primary text-on-primary p-4 rounded-xl shadow-lg ambient-shadow">
      <p className="font-headline font-semibold">Get Started</p>
      <p className="font-body text-xs opacity-90">Please complete your profile to start using the concierge service.</p>
    </div>
  </section>
)

function App() {
  const { isAuthenticated } = useAuthStore()

  return (
    <BrowserRouter>
      <Routes>
        {/* Main Application Flow */}
        <Route path="/" element={<MainLayout />}>
          <Route index element={isAuthenticated ? <Dashboard /> : <Navigate to="/login" replace />} />
          <Route path="sitter" element={<Dashboard />} />
          <Route path="client" element={<Dashboard />} />
          <Route path="orders" element={<Dashboard />} />
          <Route path="finance" element={<Dashboard />} />
          <Route path="notifications" element={<Dashboard />} />
          <Route path="profile" element={<Dashboard />} />
        </Route>

        {/* Auth Flow */}
        <Route path="/login" element={<Login />} />
        <Route path="/register" element={<Register />} />
        
        {/* Fallback */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}

export default App