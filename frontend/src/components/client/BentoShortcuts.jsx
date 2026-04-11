import React from 'react'
import { useTranslation } from 'react-i18next'
import { motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'

const BentoShortcuts = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()

  const shortcuts = [
    {
      id: 'passport',
      icon: 'book_5',
      label: t('client.vault'),
      title: t('client.pet_passport'),
      iconBg: 'bg-blue-50',
      iconColor: 'text-blue-600'
    },
    {
      id: 'log',
      icon: 'event_note',
      label: t('client.activity'),
      title: t('client.service_log'),
      iconBg: 'bg-tertiary-container/30',
      iconColor: 'text-tertiary'
    }
  ]

  return (
    <div className="grid grid-cols-2 gap-4 px-1">
      {shortcuts.map((item) => (
        <motion.div 
          key={item.id}
          whileHover={{ y: -4 }}
          whileTap={{ scale: 0.96 }}
          onClick={() => {
            if (item.id === 'log') navigate('/client/service-log/LATEST');
            if (item.id === 'passport') navigate('/client/pets');
          }}
          className="bg-surface-container-lowest p-5 rounded-2xl shadow-xl shadow-on-surface/[0.03] flex flex-col justify-between aspect-square group transition-all border border-outline-variant/10 cursor-pointer"
        >
          <div className={`w-12 h-12 ${item.iconBg} rounded-2xl flex items-center justify-center ${item.iconColor}`}>
            <span className="material-symbols-outlined text-3xl">{item.icon}</span>
          </div>
          <div className="space-y-0.5">
            <span className="block text-[10px] font-bold tracking-widest text-on-surface-variant uppercase opacity-60">
              {item.label}
            </span>
            <h3 className="font-headline font-extrabold text-base text-on-surface leading-tight tracking-tight">
              {item.title}
            </h3>
          </div>
        </motion.div>
      ))}
    </div>
  )
}

export default BentoShortcuts
