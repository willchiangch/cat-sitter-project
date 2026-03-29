import React, { useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { motion, AnimatePresence } from 'framer-motion'
import { useServiceStore } from '../../store/serviceStore'
import ActiveTimer from '../../components/service/ActiveTimer'
import TaskChecklist from '../../components/service/TaskChecklist'
import ReportEditor from '../../components/service/ReportEditor'
import MomentUploader from '../../components/service/MomentUploader'

const ServicePanel = () => {
  const { id } = useParams()
  const { t } = useTranslation()
  const { activeVisit, initVisit, finishService, isFinished, isUploading, isLoading } = useServiceStore()

  useEffect(() => {
    if (id) {
       initVisit(id)
    }
  }, [id, initVisit])

  if (isLoading || !activeVisit) return <div className="p-20 text-center italic opacity-20">Syncing with Cat Dimension...</div>

  return (
    <motion.div 
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      className="space-y-8 pb-12"
    >
      {/* Header Info */}
      <section className="space-y-1 px-1">
        <h2 className="text-2xl font-extrabold font-headline tracking-tighter text-on-surface">
          {t('service_panel.active_session')}
        </h2>
        <p className="text-sm font-body text-on-surface-variant flex items-center gap-2">
          <span className="material-symbols-outlined text-sm opacity-60">pets</span>
          Visit #{activeVisit.id.substring(0,8)} • {activeVisit.status}
        </p>
      </section>

      {/* Timer Section */}
      <ActiveTimer startTime={activeVisit.visitStartTime} />

      {/* Task Checklist Section */}
      <TaskChecklist />

      {/* Moment Uploader (Realized in V29) */}
      <MomentUploader />

      {/* Active Moments Feed */}
      {activeVisit.moments && activeVisit.moments.length > 0 && (
        <section className="space-y-4 px-1">
           <h3 className="text-xs font-extrabold tracking-widest uppercase opacity-40">Shared Moments</h3>
           <div className="flex gap-3 overflow-x-auto no-scrollbar pb-2">
              {activeVisit.moments.map(m => (
                <div key={m.id} className="flex-shrink-0 w-32 h-32 rounded-2xl overflow-hidden border border-outline-variant/10 relative group">
                  <img src={m.mediaUrl} className="w-full h-full object-cover" />
                  <div className="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 flex items-center justify-center transition-opacity">
                     <span className="text-[8px] text-white font-bold p-2 text-center">{m.caption}</span>
                  </div>
                </div>
              ))}
           </div>
        </section>
      )}

      {/* Report Section */}
      <ReportEditor />

      {/* Primary Action Button (Finish Service) */}
      <div className="px-1 sticky bottom-4 z-40 h-16">
        <AnimatePresence mode="wait">
          {!isFinished ? (
            <motion.button 
              key="finish-btn"
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95 }}
              whileHover={{ scale: isUploading ? 1 : 1.02 }}
              whileTap={{ scale: 0.98 }}
              onClick={() => !isUploading && finishService()}
              disabled={isUploading}
              className={`w-full h-full bg-gradient-to-br from-primary to-primary-dim text-on-primary font-extrabold text-base rounded-full shadow-2xl shadow-primary/30 flex items-center justify-center gap-3 active:opacity-90 transition-all ${
                isUploading ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer'
              }`}
            >
              <span className="material-symbols-outlined text-xl">verified</span>
              {t('service_panel.finish_service')}
            </motion.button>
          ) : (
            <motion.div 
              key="finished-status"
              initial={{ opacity: 0, scale: 0.9 }}
              animate={{ opacity: 1, scale: 1 }}
              className="w-full h-full bg-tertiary text-on-tertiary font-extrabold text-base rounded-full shadow-xl flex items-center justify-center gap-3 opacity-90"
            >
              <span className="material-symbols-outlined text-xl">check_circle</span>
              {t('service_panel.service_finished')}
            </motion.div>
          )}
        </AnimatePresence>
      </div>
    </motion.div>
  )
}

export default ServicePanel
