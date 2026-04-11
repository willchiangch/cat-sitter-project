import React, { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { motion, AnimatePresence } from 'framer-motion'
import { orderService } from '../../services/api'

const ClientOrders = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [activeTab, setActiveTab] = useState('EVALUATING')
  const [orders, setOrders] = useState([])
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    const fetchOrders = async () => {
      try {
        setIsLoading(true)
        const data = await orderService.list()
        setOrders(data)
      } catch (error) {
        console.error('Failed to fetch orders:', error)
      } finally {
        setIsLoading(false)
      }
    }
    fetchOrders()
  }, [])

  const tabs = [
    { id: 'EVALUATING', label: '評估中', statuses: ['PENDING', 'QUOTED'] },
    { id: 'ONGOING', label: '進行中', statuses: ['CONFIRMED', 'PENDING_VERIFICATION'] },
    { id: 'HISTORY', label: '歷史訂單', statuses: ['COMPLETED', 'CANCELLED'] }
  ]

  const filteredOrders = orders.filter(o => 
    tabs.find(tab => tab.id === activeTab).statuses.includes(o.orderStatus)
  )

  return (
    <div className="min-h-screen bg-surface text-on-surface pb-32">
      {/* Header */}
      <nav className="sticky top-0 z-50 bg-surface/80 backdrop-blur-md px-6 py-4 flex items-center justify-between border-b border-outline-variant/10">
        <h1 className="text-sm font-black font-headline uppercase tracking-widest">{t('common.orders')}</h1>
        <button className="p-2 -mr-2 text-on-surface-variant">
          <span className="material-symbols-outlined text-2xl">search</span>
        </button>
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
            <div className="space-y-4">
              {filteredOrders.map((order) => (
                <motion.div
                  key={order.id}
                  layout
                  initial={{ opacity: 0, scale: 0.95 }}
                  animate={{ opacity: 1, scale: 1 }}
                  exit={{ opacity: 0, scale: 0.95 }}
                  className="bg-surface-container-low p-5 rounded-[40px] border border-outline-variant/10 flex flex-col gap-5 hover:border-primary/20 transition-colors group"
                >
                  <div className="flex items-center gap-4">
                    <div className="w-16 h-16 rounded-[24px] overflow-hidden">
                       <img src={order.img} className="w-full h-full object-cover" />
                    </div>
                    <div className="flex-1">
                       <div className="flex justify-between items-start">
                          <h4 className="text-sm font-black tracking-tight">{order.sitterName}</h4>
                          <span className="text-xs font-black text-primary">${order.amount}</span>
                       </div>
                       <p className="text-xs font-black opacity-30 mt-1 uppercase tracking-widest">{order.catNames}</p>
                       <p className="text-xs font-black opacity-30 mt-0.5">{order.date}</p>
                    </div>
                  </div>

                  <div className="flex gap-2">
                    {order.orderStatus === 'QUOTED' && (
                       <button className="flex-1 py-3 bg-primary text-on-primary rounded-full text-sm font-black uppercase shadow-lg shadow-primary/20 hover:scale-[1.02] active:scale-95 transition-all">
                          前往付款 (憑證上傳)
                       </button>
                    )}
                    {order.orderStatus === 'CONFIRMED' && (
                       <button 
                         onClick={() => navigate(`/client/service-log/${order.id}`)}
                         className="flex-1 py-3 bg-primary text-on-primary rounded-full text-sm font-black uppercase shadow-lg shadow-primary/20 hover:scale-[1.02] active:scale-95 transition-all"
                       >
                          查看即時日誌
                       </button>
                    )}
                    <button 
                      onClick={() => navigate(`/client/orders/${order.id}`)}
                      className="flex-1 py-3 bg-surface-container-high text-on-surface text-sm font-black uppercase rounded-full hover:bg-surface-container-highest transition-colors"
                    >
                       訂單詳情
                    </button>
                  </div>
                </motion.div>
              ))}
            </div>
          ) : (
            <motion.div 
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              className="text-center py-24 space-y-4 opacity-30"
            >
              <span className="material-symbols-outlined text-6xl">inventory_2</span>
              <p className="text-sm font-black tracking-widest uppercase">目前尚無此類別的預約</p>
            </motion.div>
          )}
        </AnimatePresence>
      </motion.main>
    </div>
  )
}

export default ClientOrders
