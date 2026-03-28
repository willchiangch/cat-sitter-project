import React, { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { motion } from 'framer-motion'
import { useAuthStore } from '../../store/authStore'
import { useThemeStore } from '../../store/themeStore'
import api from '../../api'

const Login = () => {
  const { t } = useTranslation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  
  const navigate = useNavigate()
  const setAuth = useAuthStore((state) => state.setAuth)
  const { mode } = useThemeStore()

  const handleLogin = async (e) => {
    e.preventDefault()
    setLoading(true)
    setError('')
    try {
      const response = await api.post('/auth/login', { email, password })
      const { user, token } = response.data
      setAuth(user, token)
      navigate('/')
    } catch (err) {
      setError(err.response?.data?.message || t('auth.error_login'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className={`min-h-screen ${mode === 'SITTER' ? 'mode-sitter' : 'mode-client'} bg-surface flex flex-col items-center justify-center p-6`}>
      <motion.div 
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.6, ease: "easeOut" }}
        className="w-full max-w-sm space-y-12"
      >
        {/* Branding Section */}
        <div className="text-center space-y-4">
          <div className="flex justify-center">
            <div className="w-16 h-16 rounded-3xl bg-primary flex items-center justify-center shadow-lg shadow-primary/20">
              <span className="material-symbols-outlined text-4xl text-on-primary material-symbols-fill">pets</span>
            </div>
          </div>
          <div className="space-y-1">
            <h1 className="text-4xl font-extrabold font-headline tracking-tighter text-on-surface">
              {t('common.app_name')}
            </h1>
            <p className="text-sm font-body text-on-surface-variant uppercase tracking-[0.2em]">
              {t('common.tagline')}
            </p>
          </div>
        </div>

        {/* Form Section */}
        <form onSubmit={handleLogin} className="space-y-6">
          <div className="space-y-4">
            <div className="space-y-1.5 px-1">
              <label className="text-[10px] font-bold uppercase tracking-widest text-on-surface-variant">
                {t('auth.email_label')}
              </label>
              <input 
                type="email" 
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
                className="w-full bg-surface-container-low border-none rounded-xl px-4 py-4 text-sm font-medium focus:ring-2 focus:ring-primary/20 transition-all outline-none"
                placeholder="hello@whiskerwatch.com"
              />
            </div>
            
            <div className="space-y-1.5 px-1">
              <label className="text-[10px] font-bold uppercase tracking-widest text-on-surface-variant">
                {t('auth.password_label')}
              </label>
              <input 
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                className="w-full bg-surface-container-low border-none rounded-xl px-4 py-4 text-sm font-medium focus:ring-2 focus:ring-primary/20 transition-all outline-none"
                placeholder="••••••••"
              />
            </div>
          </div>

          {error && <p className="text-xs text-red-500 font-bold px-1 animate-pulse">! {error}</p>}

          <button 
            type="submit"
            disabled={loading}
            className="w-full py-5 rounded-full bg-gradient-to-br from-primary to-primary-container text-on-primary font-bold shadow-lg shadow-primary/20 active:scale-95 transition-all text-sm uppercase tracking-widest disabled:opacity-50"
          >
            {loading ? t('common.loading') : t('auth.login_btn')}
          </button>
        </form>

        <div className="text-center">
          <p className="text-sm text-on-surface-variant font-medium">
            {t('auth.no_account')} <Link to="/register" className="text-primary font-bold border-b-2 border-primary/20 hover:border-primary transition-all">{t('common.request_access')}</Link>
          </p>
        </div>
      </motion.div>
    </div>
  )
}

export default Login
