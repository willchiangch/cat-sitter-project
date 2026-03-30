import React, { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { motion } from 'framer-motion'

const CalendarSyncResult = () => {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const success = searchParams.get('success') === 'true'
  const [countdown, setCountdown] = useState(3)

  useEffect(() => {
    const timer = setInterval(() => {
      setCountdown(prev => {
        if (prev <= 1) {
          clearInterval(timer)
          navigate('/profile')
          return 0
        }
        return prev - 1
      })
    }, 1000)
    return () => clearInterval(timer)
  }, [navigate])

  return (
    <div className="min-h-screen bg-surface flex flex-col items-center justify-center p-6 text-center">
      <motion.div 
        initial={{ scale: 0, opacity: 0 }}
        animate={{ scale: 1, opacity: 1 }}
        transition={{ type: 'spring', damping: 15, stiffness: 200 }}
        className={`w-24 h-24 rounded-[32px] flex items-center justify-center mb-8 ${success ? 'bg-primary/10 text-primary' : 'bg-red-50 text-red-500'}`}
      >
        <span className="material-symbols-outlined text-5xl">
          {success ? 'calendar_apps' : 'error'}
        </span>
      </motion.div>

      <motion.h1 
        initial={{ y: 20, opacity: 0 }}
        animate={{ y: 0, opacity: 1 }}
        transition={{ delay: 0.2 }}
        className="text-3xl font-black font-headline tracking-tighter mb-4"
      >
        {success ? 'Google 行事曆連結成功！' : '行事曆連結失敗'}
      </motion.h1>

      <motion.p 
        initial={{ y: 20, opacity: 0 }}
        animate={{ y: 0, opacity: 1 }}
        transition={{ delay: 0.3 }}
        className="text-on-surface-variant/60 font-medium max-w-sm mb-12"
      >
        {success 
          ? '以後所有確認過的訂單排程都會自動同步到您的 Google 日曆。' 
          : '連結過程中發生了一些問題，請稍後再試。'}
      </motion.p>

      <motion.div 
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ delay: 0.8 }}
        className="text-xs font-bold tracking-widest opacity-30"
      >
        將在 {countdown} 秒後自動返回設定頁面
      </motion.div>

      <button 
        onClick={() => navigate('/profile')}
        className="mt-8 px-8 py-4 bg-surface-container-high rounded-full text-xs font-black tracking-widest hover:bg-surface-container-highest transition-all active:scale-95"
      >
        立即返回
      </button>
    </div>
  )
}

export default CalendarSyncResult
