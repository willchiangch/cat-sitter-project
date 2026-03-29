import React, { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { motion, AnimatePresence } from 'framer-motion'
import OrderListItem from '../../components/sitter/OrderListItem'
import { orderService } from '../../services/api'

const Orders = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [filter, setFilter] = useState('ALL')
  const [orders, setOrders] = useState([])
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    const fetchSitterOrders = async () => {
      try {
        setIsLoading(true)
        const data = await orderService.list()
        setOrders(data)
      } catch (error) {
        console.error('Failed to get sitter orders:', error)
      } finally {
        setIsLoading(false)
      }
    }
    fetchSitterOrders()
  }, [])

  const statusFilters = ['ALL', 'PENDING', 'QUOTED', 'CONFIRMED', 'COMPLETED', 'CANCELLED']

  const filteredOrders = filter === 'ALL' 
    ? orders 
    : orders.filter(o => o.orderStatus === filter)

  return (
    <div className="min-h-screen bg-surface text-on-surface pb-32">
      {/* Sticky Top Nav */}
      <nav className="sticky top-0 z-50 bg-surface/80 backdrop-blur-md px-4 py-4 flex items-center justify-between border-b border-outline-variant/10">
        <button onClick={() => navigate(-1)} className="p-2 -ml-2 text-on-surface-variant hover:text-primary transition-colors">
          <span className="material-symbols-outlined text-2xl">arrow_back</span>
        </button>
        <h1 className="text-sm font-extrabold font-headline uppercase tracking-tighter">{t('common.orders')}</h1>
        <div className="w-10"></div>
      </nav>

      <motion.main 
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        className="px-5 pt-8 space-y-8 max-w-xl mx-auto"
      >
        {/* Horizontal Status Filters */}
        <div className="flex gap-2 overflow-x-auto no-scrollbar pb-2">
          {statusFilters.map((s) => (
            <button 
              key={s}
              onClick={() => setFilter(s)}
              className={`flex-shrink-0 px-5 py-2.5 rounded-full text-[10px] font-bold uppercase tracking-widest transition-all ${
                filter === s ? 'bg-primary text-on-primary shadow-lg shadow-primary/20' : 'bg-surface-container-low text-on-surface-variant/40 hover:text-on-surface'
              }`}
            >
              {s === 'ALL' ? 'Everything' : t(`order_status.${s.toLowerCase()}`)}
            </button>
          ))}
        </div>

        {/* Order List with Section Headers */}
        <section className="space-y-10">
          <AnimatePresence mode="popLayout">
            {filteredOrders.length > 0 ? (
              <div className="space-y-6">
                {filteredOrders.map((order) => (
                  <motion.div
                    key={order.id}
                    layout
                    initial={{ opacity: 0, scale: 0.95 }}
                    animate={{ opacity: 1, scale: 1 }}
                    exit={{ opacity: 0, scale: 0.95 }}
                  >
                    <OrderListItem order={order} />
                  </motion.div>
                ))}
              </div>
            ) : (
              <motion.div 
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                className="text-center py-20 space-y-4 opacity-30"
              >
                <span className="material-symbols-outlined text-6xl">inventory_2</span>
                <p className="text-xs font-bold tracking-widest uppercase">No matching bookings found.</p>
              </motion.div>
            )}
          </AnimatePresence>
        </section>
      </motion.main>
    </div>
  )
}

export default Orders
