import React from 'react'
import { useTranslation } from 'react-i18next'
import { motion } from 'framer-motion'
import { useServiceStore } from '../../store/serviceStore'

const TaskChecklist = () => {
  const { t } = useTranslation()
  const { activeVisit, toggleTask } = useServiceStore()

  if (!activeVisit || !activeVisit.items) return null

  return (
    <section className="space-y-4 px-1">
      <div className="flex items-center gap-2 mb-4">
        <span className="material-symbols-outlined text-primary text-xl">checklist</span>
        <h3 className="font-headline font-extrabold text-xl text-on-surface tracking-tight">
          {t('service_panel.tasks_header')}
        </h3>
      </div>

      <div className="space-y-3">
        {activeVisit.items.map((item) => (
          <motion.div 
            key={item.id}
            whileTap={{ scale: 0.98 }}
            onClick={() => toggleTask(item.id, item.isCompleted)}
            className={`p-4 rounded-2xl flex items-center justify-between cursor-pointer transition-all border ${
              item.isCompleted 
                ? 'bg-tertiary-container/20 border-tertiary/20' 
                : 'bg-surface-container-lowest border-outline-variant/10 shadow-sm'
            }`}
          >
            <span className={`text-sm font-bold tracking-tight ${item.isCompleted ? 'text-on-tertiary-container opacity-60' : 'text-on-surface'}`}>
              {item.description || item.serviceType}
            </span>
            <div className={`w-6 h-6 rounded-full flex items-center justify-center border-2 transition-all ${
              item.isCompleted 
                ? 'bg-tertiary border-tertiary text-white shadow-lg' 
                : 'bg-transparent border-outline-variant/30 text-transparent'
            }`}>
              <span className="material-symbols-outlined text-sm font-bold">check</span>
            </div>
          </motion.div>
        ))}
      </div>
    </section>
  )
}

export default TaskChecklist
