import React from 'react'
import { useTranslation } from 'react-i18next'
import { motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'

const OrderListItem = ({ order }) => {
  const { t } = useTranslation()
  const navigate = useNavigate()

  const statusColors = {
    PENDING: 'bg-amber-100 text-amber-700 border-amber-200',
    CONFIRMED: 'bg-primary/10 text-primary border-primary/20',
    COMPLETED: 'bg-emerald-100 text-emerald-700 border-emerald-200',
    CANCELLED: 'bg-surface-container-high text-on-surface-variant/40 border-outline-variant/10'
  }

  return (
    <motion.div 
      whileHover={{ y: -2 }}
      className="bg-surface-container-low p-5 rounded-[32px] border border-outline-variant/10 space-y-4"
    >
      <div className="flex items-start justify-between">
        <div className="flex items-center gap-4">
          <div className="w-14 h-14 rounded-2xl overflow-hidden border border-outline-variant/10">
            <img src={order.catImageUrl} alt={order.catName} className="w-full h-full object-cover" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h4 className="text-base font-extrabold tracking-tight">{order.catName}</h4>
              <span className={`px-2 py-0.5 rounded-full text-[9px] font-bold border uppercase tracking-widest ${statusColors[order.status]}`}>
                {t(`order_status.${order.status.toLowerCase()}`)}
              </span>
            </div>
            <p className="text-xs font-medium opacity-60 mt-0.5">{order.serviceType}</p>
          </div>
        </div>
        <div className="text-right">
          <p className="text-[10px] font-bold opacity-30 uppercase tracking-widest leading-none mb-1">Estimated</p>
          <p className="text-lg font-extrabold font-headline leading-none">${order.amount}</p>
        </div>
      </div>

      <div className="bg-surface-container-lowest/50 rounded-2xl p-4 space-y-3">
        <div className="flex items-center gap-3 text-xs font-medium text-on-surface-variant">
          <span className="material-symbols-outlined text-sm opacity-40">schedule</span>
          {order.timeSlot}
        </div>
        <div className="flex items-center gap-3 text-xs font-medium text-on-surface-variant">
          <span className="material-symbols-outlined text-sm opacity-40">location_on</span>
          {order.address}
        </div>
      </div>

      <div className="flex gap-2 pt-2">
        {order.status === 'CONFIRMED' && (
          <button 
            onClick={() => navigate(`/sitter/service/${order.id}`)}
            className="flex-1 py-3 bg-primary text-on-primary rounded-full text-[11px] font-extrabold uppercase shadow-lg shadow-primary/20 hover:scale-[1.02] active:scale-95 transition-all"
          >
            Start Service
          </button>
        )}
        <button 
          onClick={() => navigate(`/sitter/orders/${order.id}`)}
          className="flex-1 py-3 bg-surface-container-high text-on-surface text-[11px] font-extrabold uppercase rounded-full hover:bg-surface-container-highest transition-colors"
        >
          Details
        </button>
        <button className="w-12 h-12 flex items-center justify-center bg-surface-container text-on-surface-variant rounded-full hover:bg-surface-container-high transition-colors">
          <span className="material-symbols-outlined text-xl">chat_bubble</span>
        </button>
      </div>
    </motion.div>
  )
}

export default OrderListItem
