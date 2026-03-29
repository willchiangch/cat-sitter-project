import React, { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { motion } from 'framer-motion'

const ActiveTimer = ({ startTime }) => {
  const { t } = useTranslation()
  const [elapsed, setElapsed] = useState(0)

  useEffect(() => {
    if (!startTime) return

    const tick = () => {
      const start = new Date(startTime).getTime()
      const now = new Date().getTime()
      setElapsed(Math.floor((now - start) / 1000))
    }

    tick()
    const interval = setInterval(tick, 1000)
    return () => clearInterval(interval)
  }, [startTime])

  const formatTime = (seconds) => {
    const h = Math.floor(seconds / 3600)
    const m = Math.floor((seconds % 3600) / 60)
    const s = seconds % 60
    return [h, m, s].map(v => v.toString().padStart(2, '0')).join(':')
  }

  return (
    <motion.div 
      initial={{ opacity: 0, scale: 0.95 }}
      animate={{ opacity: 1, scale: 1 }}
      className="bg-surface-container-low p-6 rounded-3xl flex flex-col items-center justify-center space-y-2 border border-outline-variant/5 shadow-inner"
    >
      <div className="flex items-center gap-2 text-primary opacity-60">
        <span className="material-symbols-outlined text-sm">schedule</span>
        <span className="text-[10px] font-bold uppercase tracking-widest">{t('service_panel.timer_label')}</span>
      </div>
      <p className="text-4xl font-extrabold font-headline tabular-nums text-on-surface tracking-tighter">
        {formatTime(elapsed)}
      </p>
    </motion.div>
  )
}

export default ActiveTimer
