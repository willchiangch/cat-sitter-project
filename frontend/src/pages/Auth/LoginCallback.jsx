import React, { useEffect, useState } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { motion } from 'framer-motion'
import { useAuthStore } from '../../store/authStore'
import { authService } from '../../services/api'

const LoginCallback = () => {
  const navigate = useNavigate()
  const location = useLocation()
  const setAuth = useAuthStore((state) => state.setAuth)
  const [error, setError] = useState(null)

  useEffect(() => {
    const processLogin = async () => {
      const params = new URLSearchParams(location.search)
      const token = params.get('token')

      if (!token) {
        setError('Login failed: Token missing')
        setTimeout(() => navigate('/login'), 3000)
        return
      }

      try {
        // Save token first
        localStorage.setItem('token', token)
        
        // Fetch user info
        const user = await authService.getMe()
        
        // Update store
        setAuth(user, token)
        
        // Redirect to main page
        navigate('/')
      } catch (err) {
        console.error('Callback error:', err)
        setError('Verification failed. Please try again.')
        setTimeout(() => navigate('/login'), 3000)
      }
    }

    processLogin()
  }, [location, navigate, setAuth])

  return (
    <div className="min-h-screen bg-surface flex items-center justify-center p-6">
      <motion.div 
        initial={{ opacity: 0, scale: 0.9 }}
        animate={{ opacity: 1, scale: 1 }}
        className="text-center space-y-8"
      >
        {!error ? (
          <>
            <div className="relative">
              <div className="w-20 h-20 rounded-full border-4 border-primary/20 border-t-primary animate-spin mx-auto" />
              <div className="absolute inset-0 flex items-center justify-center">
                <span className="material-symbols-outlined text-primary material-symbols-fill">pets</span>
              </div>
            </div>
            <div className="space-y-2">
              <h2 className="text-2xl font-bold text-on-surface">驗證身分中...</h2>
              <p className="text-on-surface-variant text-sm">正在為您開啟 WhiskerWatch 的大門</p>
            </div>
          </>
        ) : (
          <>
            <div className="w-20 h-20 rounded-full bg-red-100 flex items-center justify-center mx-auto">
              <span className="material-symbols-outlined text-red-500 text-4xl">error</span>
            </div>
            <div className="space-y-2">
              <h2 className="text-2xl font-bold text-on-surface">登入失敗</h2>
              <p className="text-red-500 text-sm">{error}</p>
              <p className="text-on-surface-variant text-xs mt-4">即將於 3 秒後回到登入頁面</p>
            </div>
          </>
        )}
      </motion.div>
    </div>
  )
}

export default LoginCallback
