import React from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import MainLayout from './layouts/MainLayout'
import { useAuthStore } from './store/authStore'
import Login from './pages/Auth/Login'
import LoginCallback from './pages/Auth/LoginCallback'
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
import Notifications from './pages/Shared/Notifications'
import BookingFlow from './pages/Client/BookingFlow'
import Pets from './pages/Client/Pets'
import ServicePackages from './pages/Sitter/ServicePackages'
import QuestionnaireEditor from './pages/Sitter/QuestionnaireEditor'
import ClientOrders from './pages/Client/Orders'

function App() {
  const { isAuthenticated } = useAuthStore()

  return (
    <BrowserRouter>
      <Routes>
        {/* Main Application Flow */}
        <Route path="/" element={<MainLayout />}>
          <Route index element={isAuthenticated ? <Navigate to="/sitter" replace /> : <Navigate to="/login" replace />} />
          <Route path="sitter" element={<SitterDashboard />} />
          <Route path="sitter/service/:id" element={<ServicePanel />} />
          <Route path="sitter/orders" element={<SitterOrders />} />
          <Route path="sitter/orders/:orderId" element={<SitterOrderDetail />} />
          <Route path="client" element={<ClientDashboard />} />
          <Route path="client/service-log/:id" element={<ServiceLogDetails />} />
          <Route path="client/cat-passport/:id" element={<CatPassport />} />
          <Route path="client/cat-passport/:id" element={<CatPassport />} />
          <Route path="client/pets" element={<Pets />} />
          <Route path="booking/sitter/:sitterId" element={<BookingFlow />} />
          <Route path="client/orders" element={<ClientOrders />} />
          <Route path="orders" element={<ClientOrders />} />
          <Route path="sitter/finance" element={<Finance />} />
          <Route path="sitter/trust-circle" element={<TrustCircle />} />
          <Route path="sitter/service-packages" element={<ServicePackages />} />
          <Route path="sitter/questionnaire" element={<QuestionnaireEditor />} />
          <Route path="finance" element={<Finance />} />
          <Route path="notifications" element={<Notifications />} />
          <Route path="profile" element={<Profile />} />
        </Route>

        {/* Auth Flow */}
        <Route path="/login" element={<Login />} />
        <Route path="/login/callback" element={<LoginCallback />} />
        <Route path="/register" element={<Register />} />
        
        {/* Fallback */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}

export default App