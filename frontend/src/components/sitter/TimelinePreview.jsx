import React from 'react'
import { useTranslation } from 'react-i18next'
import { motion } from 'framer-motion'

const TimelinePreview = ({ events }) => {
  const { t } = useTranslation()

  return (
    <section className="space-y-4 px-1">
      <h3 className="text-lg font-bold font-headline text-on-surface">{t('dashboard.upcoming_schedule')}</h3>
      <div className="space-y-8 relative ml-2">
        {/* Timeline Path */}
        <div className="absolute left-0 top-2 bottom-2 w-[1.5px] bg-outline-variant/10"></div>
        
        {events.map((event, index) => (
          <motion.div 
            key={event.id}
            initial={{ opacity: 0, x: -10 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ delay: index * 0.1 }}
            className="relative pl-8 group"
          >
            {/* Timeline Cap */}
            <div className="absolute left-[-4.5px] top-1.5 w-2.5 h-2.5 rounded-full bg-surface-container-high border-2 border-surface group-hover:bg-primary transition-all duration-300"></div>
            
            <div className="flex justify-between items-start">
              <div className="space-y-0.5">
                <p className="text-sm font-bold text-on-surface leading-tight transition-colors group-hover:text-primary">
                  {event.title}
                </p>
                <p className="text-xs text-on-surface-variant font-body">
                  {event.subtitle}
                </p>
              </div>
              <span className="text-[11px] font-extrabold text-on-surface-variant/60 font-headline uppercase tracking-tighter">
                {event.time}
              </span>
            </div>
          </motion.div>
        ))}
      </div>
    </section>
  )
}

export default TimelinePreview
