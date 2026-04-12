import React, { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '../../store/authStore'
import { visitService } from '../../services/api'
import UpcomingVisitCard from '../../components/sitter/UpcomingVisitCard'
import TimelinePreview from '../../components/sitter/TimelinePreview'

const Dashboard = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const user = useAuthStore((state) => state.user)
  const [loading, setLoading] = useState(true)
  const [visits, setVisits] = useState([])
  const [sitterProfile, setSitterProfile] = useState(null)

  useEffect(() => {
    const fetchDashboardData = async () => {
      try {
        setLoading(true)
        const today = new Date().toISOString().split('T')[0]
        const [visitsData, profileData] = await Promise.all([
          visitService.listSitterVisits(today),
          useAuthStore.getState().user?.profiles?.find(p => p.roleType === 'SITTER')?.id 
            ? Promise.resolve(useAuthStore.getState().user.profiles.find(p => p.roleType === 'SITTER'))
            : import('../../services/api').then(m => m.profileService.getSitterMe())
        ])
        setVisits(visitsData || [])
        setSitterProfile(profileData)
      } catch (err) {
        console.error('Failed to fetch dashboard data:', err)
      } finally {
        setLoading(false)
      }
    }

    fetchDashboardData()
  }, [])

  const nextVisitData = visits.length > 0 ? {
    id: visits[0].id,
    catName: visits[0].petName || 'Cat',
    address: visits[0].address || 'TBD',
    date: visits[0].visitStartTime ? new Date(visits[0].visitStartTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : 'N/A',
    duration: '60 min Visit',
    services: visits[0].serviceType || 'Care',
    statusLabel: t('dashboard.status_active_soon'),
    imageUrl: visits[0].petImageUrl || 'https://images.unsplash.com/photo-1514888286974-6c03e2ca1dba?auto=format&fit=crop&q=80&w=200'
  } : null

  const schedule = visits.slice(1).map(v => ({
    id: v.id,
    title: `${v.petName || 'Cat'} (${v.serviceType || 'Care'})`,
    subtitle: v.address || 'Standard Visit',
    time: v.visitStartTime ? new Date(v.visitStartTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '--:--'
  }))

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
      <section className="space-y-1 px-1">
        <h2 className="text-3xl font-black font-headline tracking-tighter text-on-surface">
          {user?.name 
            ? t('dashboard.greeting', { name: user.name }) 
            : t('dashboard.loading_greeting')}
        </h2>
        <p className="text-sm font-body text-on-surface-variant">
          {visits.length > 0 
            ? t('dashboard.sitter_summary', { count: visits.length })
            : t('dashboard.no_visits_today')}
        </p>
      </section>

      {visits.length > 0 && (
        <motion.section 
          whileHover={{ scale: 1.02 }}
          className="bg-secondary text-on-secondary p-4 rounded-2xl flex items-start gap-3 shadow-lg shadow-secondary/20"
        >
          <div className="bg-secondary-container/20 p-2 rounded-xl">
            <span className="material-symbols-outlined text-on-secondary text-xl">assignment_turned_in</span>
          </div>
          <div className="space-y-0.5 flex-1">
            <p className="font-headline font-black text-sm tracking-tight">{t('dashboard.action_required')}</p>
            <p className="font-body text-xs opacity-80 leading-snug">{t('dashboard.action_subtitle')}</p>
          </div>
          <button className="opacity-60 hover:opacity-100 transition-opacity">
            <span className="material-symbols-outlined text-xs">close</span>
          </button>
        </motion.section>
      )}

      {nextVisitData ? (
        <UpcomingVisitCard
          {...nextVisitData}
          onSopClick={() => {}}
          onPanelClick={() => navigate('/sitter/service/' + nextVisitData.id)}
        />
      ) : (
        <div className="bg-surface-variant/20 border-2 border-dashed border-on-surface-variant/10 rounded-3xl p-12 text-center space-y-3">
          <div className="w-16 h-16 bg-surface-variant/30 rounded-full flex items-center justify-center mx-auto text-on-surface-variant/40">
            <span className="material-symbols-outlined text-3xl">calendar_today</span>
          </div>
          <div className="space-y-1">
            <h3 className="font-headline font-black text-on-surface-variant">今日無預約行程</h3>
            <p className="text-xs text-on-surface-variant/60">好好放鬆一下，或者去探索新的保母機會！</p>
          </div>
        </div>
      )}

      {schedule.length > 0 && <TimelinePreview events={schedule} />}
    </motion.div>
  )
}

export default Dashboard
