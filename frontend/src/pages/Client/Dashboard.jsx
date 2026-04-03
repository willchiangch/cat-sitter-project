import React from 'react'
import { useTranslation } from 'react-i18next'
import { motion } from 'framer-motion'
import { useAuthStore } from '../../store/authStore'
import BentoShortcuts from '../../components/client/BentoShortcuts'
import ClientVisitCard from '../../components/client/ClientVisitCard'

const ClientDashboard = () => {
  const { t } = useTranslation()
  const user = useAuthStore((state) => state.user)

  // Mock data for Client Dashboard
  const activeVisit = {
    catName: 'Miso',
    breed: 'British Shorthair',
    timeWindow: '10:00 - 11:00 AM',
    serviceType: 'Standard Visit',
    sitterName: 'Will Wife',
    sitterImageUrl: 'https://images.unsplash.com/photo-1544005313-94ddf0286df2?auto=format&fit=crop&q=80&w=200',
    catImageUrl: 'https://images.unsplash.com/photo-1533738363-b7f9aef128ce?auto=format&fit=crop&q=80&w=400'
  }

  return (
    <motion.div 
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      className="space-y-8"
    >
      {/* Welcome Section */}
      <section className="mt-4 px-1">
        <h2 className="font-headline font-extrabold text-3xl tracking-tight text-on-surface">
          {t('client.greeting', { name: user?.name || 'James' })}
        </h2>
        <p className="text-on-surface-variant font-body font-medium mt-1">
          {t('client.today_is', { date: 'Tuesday, Oct 24' })}
        </p>
      </section>

      {/* Bento Shortcuts */}
      <BentoShortcuts />

      {/* Today's Visit Card */}
      {activeVisit ? (
        <ClientVisitCard {...activeVisit} />
      ) : (
        <div className="py-16 text-center space-y-3 opacity-40">
          <span className="material-symbols-outlined text-5xl">event_available</span>
          <p className="text-sm font-bold uppercase tracking-widest">今日沒有預約行程</p>
        </div>
      )}
    </motion.div>
  )
}

export default ClientDashboard
