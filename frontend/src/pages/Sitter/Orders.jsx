import React, { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { motion, AnimatePresence } from 'framer-motion'
import OrderListItem from '../../components/sitter/OrderListItem'
import { orderService } from '../../services/api'

const Orders = () => {
  const { t } = useTranslation()
  const [activeTab, setActiveTab] = useState('EVALUATING')
  const [orders, setOrders] = useState([])

  useEffect(() => {
    const fetchSitterOrders = async () => {
      try {
        const data = await orderService.list()
        setOrders(data)
      } catch (error) {
        console.error('Failed to get sitter orders:', error)
      }
    }
    fetchSitterOrders()
  }, [])

  const tabs = [
    { id: 'EVALUATING', label: '評估中', statuses: ['PENDING', 'QUOTED'] },
    { id: 'ONGOING', label: '進行中', statuses: ['CONFIRMED'] },
    { id: 'HISTORY', label: '歷史訂單', statuses: ['COMPLETED', 'CANCELLED'] }
  ]

  const filteredOrders = orders.filter(o =>
    tabs.find(tab => tab.id === activeTab).statuses.includes(o.orderStatus)
  )

  const emptyLabels = {
    EVALUATING: '目前沒有待評估的訂單',
    ONGOING: '目前沒有進行中的預約',
    HISTORY: '尚無歷史訂單紀錄'
  }

  return (
    <div className="min-h-screen bg-surface text-on-surface pb-32">
      {/* Header */}
      <nav className="sticky top-0 z-50 bg-surface/80 backdrop-blur-md px-6 py-4 flex items-center justify-between border-b border-outline-variant/10">
        <h1 className="text-sm font-black font-headline uppercase tracking-widest">{t('common.orders')}</h1>
        <div className="w-10"></div>
      </nav>

      {/* Segmented Control Tabs */}
      <div className="px-5 pt-8">
        <div className="flex p-1 bg-surface-container-low rounded-full ambient-shadow">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`flex-1 py-3 px-2 rounded-full text-sm font-black uppercase tracking-widest transition-all ${
                activeTab === tab.id ? 'bg-surface-container-lowest text-primary shadow-sm' : 'text-on-surface-variant/40'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      <motion.main
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        className="px-5 pt-10 space-y-6 max-w-xl mx-auto"
      >
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
              <p className="text-sm font-black tracking-widest uppercase">{emptyLabels[activeTab]}</p>
            </motion.div>
          )}
        </AnimatePresence>
      </motion.main>
    </div>
  )
}

export default Orders
