import React, { useEffect } from 'react'
import { authService } from './services/api'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import MainLayout from './layouts/MainLayout'
import { useAuthStore } from './store/authStore'
import { useThemeStore } from './store/themeStore'
import Login from './pages/Auth/Login'
import LoginCallback from './pages/Auth/LoginCallback'
import Onboarding from './pages/Auth/Onboarding'
import Register from './pages/Auth/Register'
import SitterDashboard from './pages/Sitter/Dashboard'
import ClientDashboard from './pages/Client/Dashboard'
import ServicePanel from './pages/Sitter/ServicePanel'
import ServiceLogDetails from './pages/Client/ServiceLogDetails'
import CatPassport from './pages/Client/CatPassport'
import Profile from './pages/Auth/Profile'
import Finance from './pages/Sitter/Finance'
import SitterOrders from './pages/Sitter/Orders'
import SitterOrderDetail from './pages/Sitter/OrderDetail'
import TrustCircle from './pages/Sitter/TrustCircle'
import ClientGate from './pages/Sitter/ClientGate'
import SubscriptionManagement from './pages/Sitter/SubscriptionManagement'
import Notifications from './pages/Shared/Notifications'
import BookingFlow from './pages/Client/BookingFlow'
import Pets from './pages/Client/Pets'
import ClientSitters from './pages/Client/Sitters'
import ServicePackages from './pages/Sitter/ServicePackages'
import QuestionnaireEditor from './pages/Sitter/QuestionnaireEditor'
import ClientOrders from './pages/Client/Orders'
import CalendarSyncResult from './pages/Sitter/CalendarSyncResult'
import SitterPublicPage from './pages/Public/SitterPublicPage'
import ProtectedRoute from './components/auth/ProtectedRoute'
import { useLocation } from 'react-router-dom'

const OnboardingRedirect = () => {
  const { pathname } = useLocation()
  const publicPaths = ['/onboarding', '/login', '/register', '/login/callback']
  
  if (!publicPaths.includes(pathname)) {
    return <Navigate to="/onboarding" replace />
  }
  return null
}

function App() {
  const { isAuthenticated, user, updateUser } = useAuthStore()
  const setMode = useThemeStore((state) => state.setMode)

  // Fetch latest user data on mount to sync persistent store with backend
  useEffect(() => {
    if (isAuthenticated) {
      authService.getMe()
        .then(updatedUser => {
          updateUser(updatedUser)
        })
        .catch(err => {
          console.error('Failed to sync user data:', err)
        })
    }
  }, [isAuthenticated, updateUser])

  // Sync theme mode with user lastActiveRole whenever it changes
  useEffect(() => {
    if (isAuthenticated && user?.lastActiveRole) {
      setMode(user.lastActiveRole)
    }
  }, [isAuthenticated, user?.lastActiveRole, setMode])

  // Onboarding awareness: if authenticated but no profiles OR no lastActiveRole, redirect to onboarding
  // except if already on onboarding/login/register paths
  const needsOnboarding = isAuthenticated && (!user?.profiles || user.profiles.length === 0 || !user?.lastActiveRole)

  return (
    <BrowserRouter>
      {needsOnboarding && <OnboardingRedirect />}
      <Routes>
        {/* Main Application Flow - PROTECTED */}
        <Route path="/" element={
          <ProtectedRoute>
            <MainLayout />
          </ProtectedRoute>
        }>
          <Route index element={user?.lastActiveRole === 'SITTER' ? <Navigate to="/sitter" replace /> : <Navigate to="/client" replace />} />
          <Route path="sitter" element={<SitterDashboard />} />
          <Route path="sitter/service/:id" element={<ServicePanel />} />
          <Route path="sitter/orders" element={<SitterOrders />} />
          <Route path="sitter/orders/:orderId" element={<SitterOrderDetail />} />
          <Route path="client" element={<ClientDashboard />} />
          <Route path="client/service-log/:id" element={<ServiceLogDetails />} />
          <Route path="client/cat-passport/:id" element={<CatPassport />} />
          <Route path="client/pets" element={<Pets />} />
          <Route path="client/sitters" element={<ClientSitters />} />
          <Route path="booking/sitter/:sitterId" element={<BookingFlow />} />
          <Route path="client/orders" element={<ClientOrders />} />
          <Route path="orders" element={<ClientOrders />} />
          <Route path="sitter/finance" element={<Finance />} />
          <Route path="sitter/trust-circle" element={<TrustCircle />} />
          <Route path="sitter/client-gate" element={<ClientGate />} />
          <Route path="sitter/subscription" element={<SubscriptionManagement />} />
          <Route path="sitter/service-packages" element={<ServicePackages />} />
          <Route path="sitter/questionnaire" element={<QuestionnaireEditor />} />
          <Route path="finance" element={<Finance />} />
          <Route path="notifications" element={<Notifications />} />
          <Route path="profile" element={<Profile />} />
          <Route path="sitter/calendar/callback" element={<CalendarSyncResult />} />
        </Route>

        {/* Public Sitter Page */}
        <Route path="/s/:slug" element={<SitterPublicPage />} />

        {/* Auth Flow */}
        <Route path="/login" element={<Login />} />
        <Route path="/login/callback" element={<LoginCallback />} />
        <Route path="/register" element={<Register />} />
        <Route path="/onboarding" element={<Onboarding />} />
        
        {/* Fallback */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}

export default App