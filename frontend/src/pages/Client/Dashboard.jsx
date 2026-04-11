import React, { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { motion } from 'framer-motion'
import { useAuthStore } from '../../store/authStore'
import { visitService } from '../../services/api'
import BentoShortcuts from '../../components/client/BentoShortcuts'
import ClientVisitCard from '../../components/client/ClientVisitCard'

const ClientDashboard = () => {
  const { t } = useTranslation()
  const user = useAuthStore((state) => state.user)
  const [loading, setLoading] = useState(true)
  const [visits, setVisits] = useState([])

  useEffect(() => {
    const fetchDashboardData = async () => {
      try {
        setLoading(true)
        const data = await visitService.listClientVisits()
        setVisits(data || [])
      } catch (err) {
        console.error('Failed to fetch client dashboard visits:', err)
      } finally {
        setLoading(false)
      }
    }

    fetchDashboardData()
  }, [])

  const activeVisit = visits.length > 0 ? {
    id: visits[0].id,
    catName: visits[0].petName || 'Cat',
    breed: visits[0].serviceName || 'Standard Visit',
    timeWindow: visits[0].visitStartTime ? new Date(visits[0].visitStartTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : 'TBD',
    serviceType: visits[0].serviceName || 'Visit',
    sitterName: visits[0].clientName || 'Sitter', // In client view, clientName in DTO is actually the sitter
    sitterImageUrl: 'https://images.unsplash.com/photo-1544005313-94ddf0286df2?auto=format&fit=crop&q=80&w=200',
    catImageUrl: visits[0].petImageUrl || 'https://images.unsplash.com/photo-1533738363-b7f9aef128ce?auto=format&fit=crop&q=80&w=400'
  } : null

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-[400px]">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary"></div>
      </div>
    )
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
          {user?.name 
            ? t('client.greeting', { name: user.name }) 
            : t('client.greeting_loading')}
        </h2>
        <p className="text-on-surface-variant font-body font-medium mt-1">
          {t('client.today_is', { date: new Date().toLocaleDateString('zh-TW', { weekday: 'long', month: 'short', day: 'numeric' }) })}
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
          <p className="text-sm font-bold uppercase tracking-widest">{t('dashboard.no_visits_today')}</p>
        </div>
      )}
    </motion.div>
  )
}

export default ClientDashboard
