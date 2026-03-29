import React from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { motion, AnimatePresence } from 'framer-motion'
import { useNotificationStore } from '../../store/notificationStore'

const Notifications = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { notifications, markAsRead, markAllAsRead } = useNotificationStore()

  const handleNotificationClick = (notif) => {
    markAsRead(notif.id)
    if (notif.link) navigate(notif.link)
  }

  const getTypeStyles = (type) => {
    switch (type) {
      case 'SERVICE': return { icon: 'pets', color: 'text-primary', bg: 'bg-primary/10' }
      case 'ORDER': return { icon: 'receipt_long', color: 'text-tertiary', bg: 'bg-tertiary/10' }
      case 'SYSTEM': return { icon: 'security', color: 'text-error', bg: 'bg-error/10' }
      default: return { icon: 'notifications', color: 'text-on-surface-variant', bg: 'bg-surface-container' }
    }
  }

  return (
    <div className="min-h-screen bg-surface text-on-surface pb-32">
      {/* Sticky Header with Bulk Action */}
      <nav className="sticky top-0 z-50 bg-surface/80 backdrop-blur-md px-4 py-4 flex items-center justify-between border-b border-outline-variant/5">
        <button onClick={() => navigate(-1)} className="p-2 -ml-2 text-on-surface-variant hover:text-primary transition-colors">
          <span className="material-symbols-outlined text-2xl">arrow_back</span>
        </button>
        <h1 className="text-sm font-extrabold font-headline uppercase tracking-widest">{t('common.alerts')}</h1>
        <button 
          onClick={markAllAsRead} 
          className="text-[10px] font-bold text-primary uppercase tracking-widest hover:opacity-70 transition-opacity"
        >
          {t('notifications.read_all', 'Read All')}
        </button>
      </nav>

      <motion.main 
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        className="px-5 pt-8 space-y-8 max-w-xl mx-auto"
      >
        <div className="flex items-center justify-between px-1">
          <h2 className="text-xs font-extrabold tracking-[0.2em] text-on-surface-variant/40 uppercase">Recent Updates</h2>
          <span className="text-[10px] font-bold opacity-20">{notifications.length} MESSAGES</span>
        </div>

        <div className="space-y-4">
          <AnimatePresence initial={false}>
            {notifications.map((notif) => {
              const styles = getTypeStyles(notif.type)
              return (
                <motion.div 
                  key={notif.id}
                  layout
                  initial={{ opacity: 0, y: 10 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, scale: 0.95 }}
                  onClick={() => handleNotificationClick(notif)}
                  className={`relative p-5 rounded-[32px] border transition-all cursor-pointer group flex items-start gap-4 ${
                    notif.read ? 'bg-surface-container-low border-outline-variant/10 opacity-60' : 'bg-surface-container-high border-primary/20 shadow-lg shadow-primary/5'
                  }`}
                >
                  {/* Category Icon */}
                  <div className={`w-12 h-12 rounded-2xl ${styles.bg} ${styles.color} flex items-center justify-center flex-shrink-0 transition-transform group-active:scale-95`}>
                    <span className="material-symbols-outlined text-2xl">{styles.icon}</span>
                  </div>

                  {/* Message Content */}
                  <div className="flex-1 space-y-1 pt-1">
                    <div className="flex justify-between items-start">
                      <h3 className={`text-sm font-extrabold tracking-tight ${notif.read ? 'text-on-surface' : 'text-primary'}`}>
                        {notif.title}
                      </h3>
                      <span className="text-[10px] font-bold opacity-30 uppercase tracking-tighter">{notif.time}</span>
                    </div>
                    <p className="text-xs font-medium leading-relaxed text-on-surface-variant opacity-80">
                      {notif.message}
                    </p>
                  </div>

                  {/* Status Indicator */}
                  {!notif.read && (
                    <div className="absolute top-5 right-5 w-2 h-2 bg-primary rounded-full animate-pulse" />
                  )}
                </motion.div>
              )
            })}
          </AnimatePresence>

          {notifications.length === 0 && (
            <div className="text-center py-20 space-y-4 opacity-30">
              <span className="material-symbols-outlined text-6xl">notifications_off</span>
              <p className="text-xs font-bold tracking-widest uppercase">{t('notifications.empty', 'Quiet at the moment.')}</p>
            </div>
          )}
        </div>
      </motion.main>
    </div>
  )
}

export default Notifications
