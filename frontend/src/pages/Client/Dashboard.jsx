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
      <ClientVisitCard {...activeVisit} />

      {/* Recent Activity Teaser */}
      <section className="px-1 pb-12 space-y-4">
        <h3 className="font-headline font-extrabold text-xl text-on-surface tracking-tight">{t('client.recent_updates')}</h3>
        <motion.div 
          whileHover={{ scale: 1.01 }}
          className="bg-surface-container-low rounded-2xl p-5 flex items-start gap-4 border border-outline-variant/10 shadow-sm"
        >
          <div className="mt-1">
            <span className="material-symbols-outlined text-tertiary">check_circle</span>
          </div>
          <div className="flex-1 space-y-0.5">
            <div className="flex justify-between items-start">
              <h4 className="font-extrabold text-on-surface text-sm">{t('client.visit_completed')}</h4>
              <span className="text-[10px] font-bold text-on-surface-variant uppercase tracking-widest opacity-60">
                {t('client.yesterday')}
              </span>
            </div>
            <p className="text-xs text-on-surface-variant font-body leading-relaxed opacity-80">
              Miso enjoyed his treats and we had a good play session with the feather wand.
            </p>
          </div>
        </motion.div>
      </section>

      {/* Contextual FAB (Owner Action: Add Cat/Service) */}
      <div className="fixed bottom-28 right-6 z-40">
        <button className="w-14 h-14 bg-gradient-to-br from-blue-600 to-blue-700 text-white rounded-full shadow-2xl flex items-center justify-center active:scale-95 transition-transform duration-200 shadow-blue-500/20">
          <span className="material-symbols-outlined text-3xl">add</span>
        </button>
      </div>
    </motion.div>
  )
}

export default ClientDashboard
