import React from 'react'
import { useTranslation } from 'react-i18next'
import { motion } from 'framer-motion'

const ClientVisitCard = ({ catName, breed, timeWindow, serviceType, sitterName, sitterImageUrl, catImageUrl }) => {
  const { t } = useTranslation()

  return (
    <section className="space-y-4 px-1">
      <div className="flex justify-between items-end mb-4 px-1">
        <h3 className="font-headline font-extrabold text-xl text-on-surface tracking-tight">{t('client.today_visit')}</h3>
        <button className="text-blue-600 font-bold text-xs uppercase tracking-widest">{t('client.view_map')}</button>
      </div>

      <motion.div 
        initial={{ opacity: 0, y: 15 }}
        animate={{ opacity: 1, y: 0 }}
        className="bg-surface-container-lowest rounded-3xl overflow-hidden shadow-2xl shadow-on-surface/[0.04] relative group border border-outline-variant/10"
      >
        {/* Status Chip */}
        <div className="absolute top-4 right-4 z-10">
          <span className="bg-secondary-container text-on-secondary-container px-4 py-1.5 rounded-full text-[10px] font-extrabold uppercase tracking-widest shadow-sm">
            Upcoming
          </span>
        </div>

        <div className="p-0">
          <div className="h-48 relative overflow-hidden">
            <img 
              className="w-full h-full object-cover transition-transform duration-700 group-hover:scale-105" 
              src={catImageUrl || 'https://images.unsplash.com/photo-1533738363-b7f9aef128ce?auto=format&fit=crop&q=80&w=400'} 
              alt={catName} 
            />
            <div className="absolute inset-0 bg-gradient-to-t from-black/60 to-transparent"></div>
            <div className="absolute bottom-4 left-6">
              <h4 className="text-white font-headline font-extrabold text-3xl tracking-tight">{catName}</h4>
              <p className="text-white/80 font-body text-sm font-medium">{breed}</p>
            </div>
          </div>

          <div className="p-6 space-y-6">
            <div className="flex items-center justify-between">
              <div className="space-y-1">
                <p className="text-[10px] font-bold text-on-surface-variant uppercase tracking-widest opacity-60">
                  {t('client.time_window')}
                </p>
                <p className="text-lg font-extrabold text-on-surface tracking-tighter">{timeWindow}</p>
              </div>
              <div className="w-px h-8 bg-outline-variant/10"></div>
              <div className="space-y-1 text-right">
                <p className="text-[10px] font-bold text-on-surface-variant uppercase tracking-widest opacity-60">
                  {t('client.service_type')}
                </p>
                <p className="text-lg font-extrabold text-on-surface tracking-tighter">{serviceType}</p>
              </div>
            </div>

            <div className="flex items-center gap-4 bg-surface-container-low p-4 rounded-2xl">
              <img 
                className="w-12 h-12 rounded-full object-cover ring-2 ring-white border border-outline-variant/10" 
                src={sitterImageUrl || 'https://images.unsplash.com/photo-1544005313-94ddf0286df2?auto=format&fit=crop&q=80&w=200'}
                alt={sitterName}
              />
              <div className="flex-1">
                <p className="text-[10px] font-bold text-on-surface-variant uppercase tracking-widest opacity-60">
                  {t('client.your_sitter')}
                </p>
                <p className="font-extrabold text-on-surface text-sm tracking-tight">{sitterName}</p>
              </div>
              <div className="flex gap-2">
                <button className="w-10 h-10 bg-white rounded-full flex items-center justify-center text-blue-600 shadow-sm border border-outline-variant/10 active:scale-95 transition-all">
                  <span className="material-symbols-outlined text-lg">chat</span>
                </button>
                <button className="w-10 h-10 bg-white rounded-full flex items-center justify-center text-blue-600 shadow-sm border border-outline-variant/10 active:scale-95 transition-all">
                  <span className="material-symbols-outlined text-lg">call</span>
                </button>
              </div>
            </div>
          </div>
        </div>
      </motion.div>
    </section>
  )
}

export default ClientVisitCard
