import React, { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { motion, AnimatePresence } from 'framer-motion'
import { useAuthStore } from '../../store/authStore'
import { authService } from '../../services/api'

const Onboarding = () => {
  const navigate = useNavigate()
  const { user, setAuth } = useAuthStore()
  const [step, setStep] = useState(1)
  const [role, setRole] = useState(null)
  const [displayName, setDisplayName] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const [nameAvailable, setNameAvailable] = useState(true)

  useEffect(() => {
    // If user already has a role, redirect to home
    if (user?.lastActiveRole) {
      navigate('/')
    }
    // Set initial display name from OAuth2 info if available
    if (user?.email && !displayName) {
      const defaultName = user.email.split('@')[0]
      setDisplayName(defaultName)
    }
  }, [user, navigate, displayName])

  const handleRoleSelect = (selectedRole) => {
    setRole(selectedRole)
    setStep(2)
  }

  const handleComplete = async () => {
    if (!displayName.trim()) {
      setError('請輸入顯示名稱')
      return
    }

    setLoading(true)
    setError(null)
    try {
      const updatedUser = await authService.completeOnboarding({
        roleType: role,
        displayName: displayName.trim()
      })
      
      // Update store with new user info (which now has role and profile)
      const token = localStorage.getItem('token')
      setAuth(updatedUser, token)
      
      // Navigate to corresponding dashboard
      navigate('/')
    } catch (err) {
      console.error('Onboarding failed:', err)
      setError(err.response?.data?.message || '設定失敗，可能名稱已被使用')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-surface flex items-center justify-center p-6 font-body overflow-hidden">
      {/* Decorative Background Elements */}
      <div className="absolute top-0 right-0 w-96 h-96 bg-primary/5 rounded-full blur-3xl -mr-48 -mt-48 transition-colors duration-700" 
           style={{ backgroundColor: role === 'SITTER' ? 'rgba(118, 86, 0, 0.05)' : role === 'CLIENT' ? 'rgba(0, 94, 159, 0.05)' : '' }} />
      <div className="absolute bottom-0 left-0 w-96 h-96 bg-secondary/5 rounded-full blur-3xl -ml-48 -mb-48" />

      <motion.div 
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        className="w-full max-w-xl relative z-10"
      >
        <AnimatePresence mode="wait">
          {step === 1 ? (
            <motion.div
              key="step1"
              initial={{ opacity: 0, x: 20 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -20 }}
              className="space-y-12 text-center"
            >
              <div className="space-y-4">
                <h1 className="text-4xl font-headline font-bold text-on-surface tracking-tight">
                  歡迎來到 <span className="text-primary italic">WhiskerWatch</span>
                </h1>
                <p className="text-on-surface-variant text-lg">告訴我們，您今天想以什麼身分加入？</p>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                {/* Sitter Card */}
                <motion.button
                  whileHover={{ y: -8, scale: 1.02 }}
                  whileTap={{ scale: 0.98 }}
                  onClick={() => handleRoleSelect('SITTER')}
                  className="glass-effect p-8 rounded-lg ambient-shadow border border-white/40 flex flex-col items-center gap-6 group"
                >
                  <div className="w-20 h-20 rounded-full bg-primary/10 flex items-center justify-center group-hover:bg-primary/20 transition-colors">
                    <span className="material-symbols-outlined text-4xl text-primary material-symbols-fill">medical_services</span>
                  </div>
                  <div className="text-center">
                    <h3 className="text-xl font-bold text-on-surface mb-2">我是冒險者 (保母)</h3>
                    <p className="text-sm text-on-surface-variant leading-relaxed">
                      我想提供專業的貓咪照護服務，利用空檔賺取報酬。
                    </p>
                  </div>
                </motion.button>

                {/* Client Card */}
                <motion.button
                  whileHover={{ y: -8, scale: 1.02 }}
                  whileTap={{ scale: 0.98 }}
                  onClick={() => handleRoleSelect('CLIENT')}
                  className="glass-effect p-8 rounded-lg ambient-shadow border border-white/40 flex flex-col items-center gap-6 group"
                >
                  <div className="w-20 h-20 rounded-full bg-secondary/10 flex items-center justify-center group-hover:bg-secondary/20 transition-colors">
                    <span className="material-symbols-outlined text-4xl text-secondary material-symbols-fill">person</span>
                  </div>
                  <div className="text-center">
                    <h3 className="text-xl font-bold text-on-surface mb-2">我是召喚師 (家長)</h3>
                    <p className="text-sm text-on-surface-variant leading-relaxed">
                      我正在尋找值得信賴的保母，幫我照顧心愛的貓主子。
                    </p>
                  </div>
                </motion.button>
              </div>
            </motion.div>
          ) : (
            <motion.div
              key="step2"
              initial={{ opacity: 0, x: 20 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -20 }}
              className="space-y-10"
            >
              <div className="text-center space-y-4">
                <button 
                  onClick={() => setStep(1)}
                  className="inline-flex items-center text-sm text-on-surface-variant hover:text-primary transition-colors mb-4"
                >
                  <span className="material-symbols-outlined text-sm mr-1">arrow_back</span>
                  重新選擇身分
                </button>
                <h2 className="text-3xl font-headline font-bold text-on-surface">最後一步：設定暱稱</h2>
                <p className="text-on-surface-variant">這將是大家在社區中看到您的名字，必須唯一喔！</p>
              </div>

              <div className="glass-effect p-8 rounded-lg ambient-shadow border border-white/40 space-y-6">
                <div className="space-y-2">
                  <label className="text-xs font-bold uppercase tracking-wider text-on-surface-variant ml-1">顯示名稱</label>
                  <div className="relative">
                    <input 
                      type="text"
                      value={displayName}
                      onChange={(e) => setDisplayName(e.target.value)}
                      placeholder="例如：貓咪守護者 小明"
                      className="w-full h-14 bg-surface-container-low border-none rounded-default px-4 focus:ring-2 focus:ring-primary/20 transition-all text-on-surface"
                      disabled={loading}
                    />
                    {displayName && (
                      <div className="absolute right-4 top-1/2 -translate-y-1/2">
                        <span className={`material-symbols-outlined ${nameAvailable ? 'text-green-500' : 'text-red-500'}`}>
                          {nameAvailable ? 'check_circle' : 'error'}
                        </span>
                      </div>
                    )}
                  </div>
                  {error && <p className="text-red-500 text-xs ml-1">{error}</p>}
                </div>

                <motion.button
                  whileHover={{ scale: 1.02 }}
                  whileTap={{ scale: 0.98 }}
                  onClick={handleComplete}
                  disabled={loading || !displayName.trim()}
                  className={`w-full h-14 rounded-default font-bold flex items-center justify-center gap-2 transition-all ${
                    loading ? 'bg-primary/50 cursor-not-allowed' : 'bg-primary text-on-primary hover:shadow-lg'
                  }`}
                >
                  {loading ? (
                    <div className="w-5 h-5 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                  ) : (
                    <>
                      <span>開啟冒險旅程</span>
                      <span className="material-symbols-outlined">rocket_launch</span>
                    </>
                  )}
                </motion.button>
              </div>

              <div className="text-center">
                <p className="text-xs text-on-surface-variant px-12">
                  點擊確認即代表您同意 WhiskerWatch 的服務條款與隱私權政策。
                  您隨時可以於個人首頁切換或新增其他身分。
                </p>
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </motion.div>
    </div>
  )
}

export default Onboarding
