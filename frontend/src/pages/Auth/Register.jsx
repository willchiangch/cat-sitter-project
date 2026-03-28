import React, { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { motion } from 'framer-motion'
import api from '../../api'

const Register = () => {
  const { t } = useTranslation()
  const [formData, setFormData] = useState({
    name: '',
    email: '',
    password: '',
    role: 'CLIENT' // Default role
  })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  
  const navigate = useNavigate()

  const handleRegister = async (e) => {
    e.preventDefault()
    setLoading(true)
    setError('')
    try {
      await api.post('/auth/register', formData)
      navigate('/login')
    } catch (err) {
      setError(err.response?.data?.message || t('auth.error_register'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-surface flex flex-col items-center justify-center p-6 bg-surface">
      <motion.div 
        initial={{ opacity: 0, scale: 0.95 }}
        animate={{ opacity: 1, scale: 1 }}
        transition={{ duration: 0.5, ease: "easeOut" }}
        className="w-full max-w-sm space-y-10"
      >
        <div className="text-center space-y-2">
          <h1 className="text-3xl font-extrabold font-headline tracking-tighter text-on-surface">
            {t('auth.register_title')}
          </h1>
          <p className="text-sm font-body text-on-surface-variant leading-relaxed">
            {t('auth.register_subtitle')}
          </p>
        </div>

        {/* Role Selection Switch */}
        <div className="flex p-1 bg-surface-container-low rounded-full shadow-inner">
          <button 
            type="button"
            onClick={() => setFormData({ ...formData, role: 'CLIENT' })}
            className={`flex-1 py-3 rounded-full text-xs font-bold uppercase tracking-widest transition-all duration-300 ${formData.role === 'CLIENT' ? 'bg-white text-blue-600 shadow-sm scale-100' : 'text-on-surface-variant opacity-60'}`}
          >
            {t('auth.role_parent')}
          </button>
          <button 
            type="button"
            onClick={() => setFormData({ ...formData, role: 'SITTER' })}
            className={`flex-1 py-3 rounded-full text-xs font-bold uppercase tracking-widest transition-all duration-300 ${formData.role === 'SITTER' ? 'bg-white text-amber-600 shadow-sm scale-100' : 'text-on-surface-variant opacity-60'}`}
          >
            {t('auth.role_sitter')}
          </button>
        </div>

        <form onSubmit={handleRegister} className="space-y-6">
          <div className="space-y-4">
            <div className="space-y-1.5 px-1">
              <label className="text-[10px] font-bold uppercase tracking-widest text-on-surface-variant">
                {t('auth.name_label')}
              </label>
              <input 
                type="text" 
                value={formData.name}
                onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                required
                className="w-full bg-surface-container-low border-none rounded-xl px-4 py-4 text-sm font-medium focus:ring-2 focus:ring-primary/20 transition-all outline-none"
                placeholder="Jane Doe"
              />
            </div>

            <div className="space-y-1.5 px-1">
              <label className="text-[10px] font-bold uppercase tracking-widest text-on-surface-variant">
                {t('auth.email_label')}
              </label>
              <input 
                type="email" 
                value={formData.email}
                onChange={(e) => setFormData({ ...formData, email: e.target.value })}
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
                value={formData.password}
                onChange={(e) => setFormData({ ...formData, password: e.target.value })}
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
            className={`w-full py-5 rounded-full bg-gradient-to-br transition-all duration-500 font-bold shadow-lg text-white text-sm uppercase tracking-widest disabled:opacity-50 active:scale-95
              ${formData.role === 'SITTER' ? 'from-amber-600 to-amber-500 shadow-amber-600/20' : 'from-blue-600 to-blue-500 shadow-blue-600/20'}
            `}
          >
            {loading ? t('common.loading') : t('auth.register_btn')}
          </button>
        </form>

        <div className="text-center">
          <p className="text-sm text-on-surface-variant font-medium">
            {t('auth.has_account')} <Link to="/login" className="text-on-surface font-bold border-b-2 border-on-surface/20 hover:border-on-surface transition-all">{t('auth.sign_in')}</Link>
          </p>
        </div>
      </motion.div>
    </div>
  )
}

export default Register
