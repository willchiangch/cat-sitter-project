import React, { useState, useEffect } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { useAuthStore } from '../../store/authStore'
import { authService } from '../../services/api'

const CommunicationVerify = () => {
  const { user, setEmailVerified } = useAuthStore()
  const [showModal, setShowModal] = useState(false)
  const [code, setCode] = useState('')
  const [loading, setLoading] = useState(false)
  const [sent, setSent] = useState(false)
  const [error, setError] = useState('')

  if (!user || user.emailVerified) return null

  const handleRequestCode = async () => {
    setLoading(true)
    setError('')
    try {
      await authService.requestVerification()
      setSent(true)
    } catch (err) {
      setError('發送失敗，請稍後再試')
    } finally {
      setLoading(false)
    }
  }

  const handleVerify = async (e) => {
    e.preventDefault()
    setLoading(true)
    setError('')
    try {
      const updatedUser = await authService.verifyEmail(code)
      setEmailVerified(true)
      setShowModal(false)
      // Show success toast here if available
    } catch (err) {
      setError('驗證碼不正確或已失效')
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      {/* Top Banner */}
      <motion.div 
        initial={{ y: -50, opacity: 0 }}
        animate={{ y: 0, opacity: 1 }}
        className="bg-surface-container-low border-b border-blue-200/30 px-6 py-3 flex items-center justify-between sticky top-0 z-[60] backdrop-blur-md"
      >
        <div className="flex items-center gap-3">
          <span className="material-symbols-outlined text-navy text-base">mail</span>
          <span className="text-[11px] font-extrabold text-navy/70 uppercase tracking-widest leading-none">信箱尚未驗證</span>
        </div>
        <button 
          onClick={() => setShowModal(true)}
          className="px-4 py-1.5 bg-navy text-white text-[10px] font-black rounded-full shadow-lg shadow-navy/20 active:scale-95 transition-all"
        >
          立即驗證
        </button>
      </motion.div>

      {/* Verification Modal */}
      <AnimatePresence>
        {showModal && (
          <div className="fixed inset-0 z-[60] flex items-end sm:items-center justify-center p-0 sm:p-4">
            <motion.div 
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setShowModal(false)}
              className="absolute inset-0 bg-black/60 backdrop-blur-sm"
            />
            
            <motion.div 
              initial={{ y: "100%" }}
              animate={{ y: 0 }}
              exit={{ y: "100%" }}
              transition={{ type: "spring", damping: 25, stiffness: 200 }}
              className="relative w-full max-w-sm bg-surface rounded-t-[40px] sm:rounded-[40px] shadow-2xl p-8 pb-12 sm:pb-8"
            >
              <div className="w-12 h-1 bg-on-surface/10 rounded-full mx-auto mb-8 sm:hidden" />
              
              <div className="space-y-6">
                <div className="text-center space-y-2">
                  <div className="w-16 h-16 rounded-3xl bg-primary/10 flex items-center justify-center mx-auto mb-4">
                    <span className="material-symbols-outlined text-3xl text-primary material-symbols-fill">mark_email_read</span>
                  </div>
                  <h3 className="text-2xl font-black text-on-surface">通訊信箱驗證</h3>
                  <p className="text-on-surface-variant text-sm">
                    為了確保您能收到即時的媒合與服務通知，我們需要驗證您的電子郵件：<br/>
                    <span className="font-bold text-on-surface">{user.email}</span>
                  </p>
                </div>

                {!sent ? (
                  <button 
                    onClick={handleRequestCode}
                    disabled={loading}
                    className="w-full py-5 rounded-full bg-primary text-on-primary font-bold shadow-lg shadow-primary/20 disabled:opacity-50 transition-all flex items-center justify-center gap-2"
                  >
                    {loading ? (
                      <div className="w-5 h-5 border-2 border-on-primary/30 border-t-on-primary animate-spin rounded-full" />
                    ) : (
                      <>
                        <span className="material-symbols-outlined text-sm">send</span>
                        發送驗證碼
                      </>
                    )}
                  </button>
                ) : (
                  <form onSubmit={handleVerify} className="space-y-6">
                    <div className="space-y-2">
                      <label className="text-[10px] font-bold text-on-surface-variant uppercase tracking-widest px-1">
                        輸入 6 位數驗證碼
                      </label>
                      <input 
                        type="text"
                        value={code}
                        onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                        className="w-full bg-surface-container-low border-2 border-primary/20 focus:border-primary rounded-2xl px-4 py-5 text-center text-3xl font-black tracking-[10px] outline-none transition-all"
                        placeholder="000000"
                        required
                      />
                    </div>

                    {error && <p className="text-xs text-red-500 font-bold text-center">! {error}</p>}

                    <div className="grid grid-cols-2 gap-3">
                      <button 
                        type="button"
                        onClick={() => setSent(false)}
                        className="py-4 rounded-full bg-surface-container-high text-on-surface font-bold text-sm"
                      >
                        重新發送
                      </button>
                      <button 
                        type="submit"
                        disabled={loading || code.length < 6}
                        className="py-4 rounded-full bg-primary text-on-primary font-bold text-sm shadow-lg shadow-primary/20 disabled:opacity-50"
                      >
                        {loading ? '驗證中...' : '確認驗證'}
                      </button>
                    </div>
                  </form>
                )}

                <button 
                  onClick={() => setShowModal(false)}
                  className="w-full py-4 text-on-surface-variant text-xs font-bold uppercase tracking-widest"
                >
                  稍後再說
                </button>
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>
    </>
  )
}

export default CommunicationVerify
