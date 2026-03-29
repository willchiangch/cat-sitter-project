import React from 'react'
import { motion } from 'framer-motion'

const StatCard = ({ icon, label, value, subValue, variant = 'primary' }) => {
  const iconColor = variant === 'primary' ? 'text-primary' : 'text-secondary'
  
  return (
    <motion.div 
      whileHover={{ y: -4 }}
      className="bg-surface-container-low p-4 rounded-xl space-y-2 ambient-shadow"
    >
      <span className={`material-symbols-outlined ${iconColor}`}>
        {icon}
      </span>
      <div>
        <p className="text-[10px] text-on-surface-variant font-bold uppercase tracking-widest">{label}</p>
        <p className="text-xl font-extrabold font-headline text-on-surface flex items-baseline gap-1">
          {value}
          {subValue && <span className="text-xs font-normal opacity-60 font-body">{subValue}</span>}
        </p>
      </div>
    </motion.div>
  )
}

export default StatCard
