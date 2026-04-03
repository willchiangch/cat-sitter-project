import React from 'react'
import { useTranslation } from 'react-i18next'
import { motion } from 'framer-motion'

const UpcomingVisitCard = ({ catName, address, date, duration, services, statusLabel, imageUrl, onSopClick, onPanelClick }) => {
  const { t } = useTranslation()

  return (
    <div className="space-y-4">
      <div className="flex justify-between items-end px-1">
        <h3 className="text-lg font-bold font-headline text-on-surface">{t('dashboard.up_next')}</h3>
        <span className="text-[10px] font-bold text-primary uppercase tracking-widest leading-none pt-2">{date}</span>
      </div>
      
      <motion.div 
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        className="bg-surface-container-lowest rounded-2xl p-5 space-y-6 shadow-xl shadow-on-surface/[0.03] relative overflow-hidden group border border-outline-variant/10"
      >
        {/* Status Chip */}
        {statusLabel && (
          <div className="absolute top-0 right-0 px-4 py-2 bg-primary-container text-on-primary-container text-[10px] font-bold rounded-bl-2xl uppercase tracking-tighter">
            {statusLabel}
          </div>
        )}

        <div className="flex gap-4 items-start">
          <div className="relative">
            <img 
              className="w-16 h-16 rounded-full object-cover ring-4 ring-primary-container/10" 
              src={imageUrl || 'https://images.unsplash.com/photo-1514888286974-6c03e2ca1dba?auto=format&fit=crop&q=80&w=200'} 
              alt={catName}
            />
            <div className="absolute -bottom-1 -right-1 bg-tertiary text-white p-1 rounded-full border-2 border-white shadow-sm">
              <span className="material-symbols-outlined text-[12px] block material-symbols-fill" style={{ fontVariationSettings: "'FILL' 1" }}>
                check_circle
              </span>
            </div>
          </div>
          
          <div className="space-y-1">
            <h4 className="text-xl font-extrabold font-headline text-on-surface">{catName}</h4>
            <p className="text-xs text-on-surface-variant font-body flex items-center gap-1.5 translate-y-[-2px]">
              <span className="material-symbols-outlined text-sm opacity-60">location_on</span>
              {address}
            </p>
            <p className="text-xs text-on-surface-variant font-body flex items-center gap-1.5 translate-y-[-2px]">
              <span className="material-symbols-outlined text-sm opacity-60">schedule</span>
              {duration} • {services}
            </p>
          </div>
        </div>

        {/* Tonal Divider */}
        <div className="h-[1px] bg-outline-variant/5 w-full"></div>

        <div className="grid grid-cols-2 gap-3">
          <button onClick={onSopClick} className="flex items-center justify-center gap-2 py-3 px-4 rounded-full bg-surface-container-low text-on-surface font-bold text-xs hover:bg-surface-container transition-all active:scale-95 duration-150">
            <span className="material-symbols-outlined text-lg">description</span>
            {t('dashboard.view_sop')}
          </button>
          <button onClick={onPanelClick} className="flex items-center justify-center gap-2 py-3 px-4 rounded-full bg-gradient-to-br from-primary to-primary-dim text-on-primary font-bold text-xs shadow-lg shadow-primary/20 hover:opacity-95 transition-all active:scale-95 duration-150">
            <span className="material-symbols-outlined text-lg">play_circle</span>
            {t('dashboard.service_panel')}
          </button>
        </div>
      </motion.div>
    </div>
  )
}

export default UpcomingVisitCard
