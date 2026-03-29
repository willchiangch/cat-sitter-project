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
        <div className="space-y-6">
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

            {error && <p className="text-xs text-red-500 font-bold px-1">! {error}</p>}

            <button 
              type="submit"
              disabled={loading}
              className="w-full py-5 rounded-full bg-primary text-on-primary font-bold shadow-lg shadow-primary/20 active:scale-95 transition-all text-sm uppercase tracking-widest disabled:opacity-50"
            >
              {loading ? t('common.loading') : t('auth.login_btn')}
            </button>
          </form>

          {/* Divider */}
          <div className="relative py-4 flex items-center">
            <div className="flex-grow border-t border-on-surface/10"></div>
            <span className="flex-shrink mx-4 text-[10px] font-bold text-on-surface-variant uppercase tracking-widest">
              或透過社群登入
            </span>
            <div className="flex-grow border-t border-on-surface/10"></div>
          </div>

          {/* Social Buttons */}
          <div className="grid grid-cols-1 gap-3">
            <button 
              onClick={() => window.location.href = '/api/v1/oauth2/authorization/google'}
              className="flex items-center justify-center gap-3 w-full py-4 rounded-full bg-surface-container-high border border-on-surface/5 hover:bg-surface-container-highest transition-all text-sm font-bold text-on-surface"
            >
              <img src="https://www.google.com/favicon.ico" className="w-5 h-5" alt="Google" />
              使用 Google 繼續
            </button>
            <button 
              onClick={() => window.location.href = '/api/v1/oauth2/authorization/facebook'}
              className="flex items-center justify-center gap-3 w-full py-4 rounded-full bg-[#1877F2] text-white hover:opacity-90 transition-all text-sm font-bold shadow-lg shadow-[#1877F2]/20"
            >
              <svg className="w-5 h-5 fill-current" viewBox="0 0 24 24">
                <path d="M24 12.073c0-6.627-5.373-12-12-12s-12 5.373-12 12c0 5.99 4.388 10.954 10.125 11.854v-8.385H7.078v-3.47h3.047V9.43c0-3.007 1.792-4.669 4.533-4.669 1.312 0 2.686.235 2.686.235v2.953H15.83c-1.491 0-1.956.925-1.956 1.874v2.25h3.328l-.532 3.47h-2.796v8.385C19.612 23.027 24 18.062 24 12.073z"/>
              </svg>
              使用 Facebook 繼續
            </button>
            <button 
              onClick={() => window.location.href = '/api/v1/oauth2/authorization/apple'}
              className="flex items-center justify-center gap-3 w-full py-4 rounded-full bg-black text-white hover:opacity-90 transition-all text-sm font-bold shadow-lg shadow-black/20"
            >
              <svg className="w-5 h-5 fill-current" viewBox="0 0 24 24">
                <path d="M12.152 6.896c-.948 0-2.415-1.078-3.96-1.04-2.04.027-3.91 1.183-4.961 3.014-2.117 3.675-.546 9.103 1.519 12.09 1.013 1.454 2.208 3.09 3.792 3.039 1.52-.065 2.09-.987 3.935-.987 1.831 0 2.35.987 3.96.948 1.637-.026 2.676-1.48 3.676-2.948 1.156-1.688 1.636-3.325 1.662-3.415-.039-.013-3.182-1.221-3.22-4.857-.026-3.04 2.48-4.494 2.597-4.559-1.429-2.09-3.623-2.324-4.39-2.376-2-.156-3.675 1.09-4.61 1.09zM15.53 3.83c.843-1.012 1.4-2.427 1.245-3.83-1.207.052-2.662.805-3.532 1.82a3.69 3.69 0 0 0-1.297 3.687c1.35.104 2.741-.665 3.584-1.677z"/>
              </svg>
              使用 Apple 繼續
            </button>
          </div>
        </div>

        <div className="text-center pt-4">
          <p className="text-sm text-on-surface-variant font-medium">
            {t('auth.no_account')} <Link to="/register" className="text-primary font-bold border-b-2 border-primary/20 hover:border-primary transition-all">{t('common.request_access')}</Link>
          </p>
        </div>
      </motion.div>
    </div>
  )
}

export default Login
