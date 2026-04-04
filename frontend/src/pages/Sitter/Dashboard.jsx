import React from 'react'
import { useTranslation } from 'react-i18next'
import { motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '../../store/authStore'
import UpcomingVisitCard from '../../components/sitter/UpcomingVisitCard'
import TimelinePreview from '../../components/sitter/TimelinePreview'

const Dashboard = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const user = useAuthStore((state) => state.user)

  // Mock Data for the Sitter Dashboard View
  const nextVisit = {
    id: 'mock-visit-1',
    catName: 'Oliver',
    address: '422 West Oak Avenue',
    date: '週三 · 今日',
    duration: '60 min Visit',
    services: 'Feeding & Play',
    statusLabel: t('dashboard.status_active_soon'),
    imageUrl: 'https://images.unsplash.com/photo-1514888286974-6c03e2ca1dba?auto=format&fit=crop&q=80&w=200'
  }

  const schedule = [
    { id: '1', title: 'Luna (Standard Care)', subtitle: 'Cat sitting & Water plants', time: '16:30' },
    { id: '2', title: 'Milo (Evening Check-in)', subtitle: 'Medical administration', time: '19:00' }
  ]

  return (
    <motion.div 
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      className="space-y-8"
    >
      {/* Welcome Header */}
      <section className="space-y-1 px-1">
        <h2 className="text-3xl font-extrabold font-headline tracking-tighter text-on-surface">
          {t('dashboard.greeting', { name: user?.profiles?.[0]?.name || user?.name || 'Sitter' })}
        </h2>
        <p className="text-sm font-body text-on-surface-variant">
          {t('dashboard.sitter_summary', { count: 3 })}
        </p>
      </section>

      {/* Action Required Alert */}
      <motion.section 
        whileHover={{ scale: 1.02 }}
        className="bg-secondary text-on-secondary p-4 rounded-2xl flex items-start gap-3 shadow-lg shadow-secondary/20"
      >
        <div className="bg-secondary-container/20 p-2 rounded-xl">
          <span className="material-symbols-outlined text-on-secondary text-xl">assignment_turned_in</span>
        </div>
        <div className="space-y-0.5 flex-1">
          <p className="font-headline font-bold text-sm tracking-tight">{t('dashboard.action_required')}</p>
          <p className="font-body text-[11px] opacity-80 leading-snug">{t('dashboard.action_subtitle')}</p>
        </div>
        <button className="opacity-60 hover:opacity-100 transition-opacity">
          <span className="material-symbols-outlined text-xs">close</span>
        </button>
      </motion.section>

      {/* Up Next Section */}
      <UpcomingVisitCard
        {...nextVisit}
        onSopClick={() => {}}
        onPanelClick={() => navigate('/sitter/service/' + nextVisit.id)}
      />

      {/* Timeline Section */}
      <TimelinePreview events={schedule} />
    </motion.div>
  )
}

export default Dashboard
