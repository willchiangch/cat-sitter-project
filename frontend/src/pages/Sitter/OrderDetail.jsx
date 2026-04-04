import React, { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { motion, AnimatePresence } from 'framer-motion'
import { QuoteModal, ReferralModal } from '../../components/sitter/DecisionModals'
import { orderService } from '../../services/api'

const OrderDetail = () => {
  const { orderId } = useParams()
  const navigate = useNavigate()
  const { t } = useTranslation()
  const [showQuote, setShowQuote] = useState(false)
  const [showReferral, setShowReferral] = useState(false)
  const [order, setOrder] = useState(null)
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    fetchOrderDetail()
  }, [orderId])

  const fetchOrderDetail = async () => {
    try {
      setIsLoading(true)
      const data = await orderService.getDetail(orderId)
      setOrder(data)
    } catch (error) {
      console.error('Failed to fetch order detail:', error)
    } finally {
      setIsLoading(false)
    }
  }

  const handleQuoteConfirm = async (data) => {
    try {
      setIsLoading(true)
      const quoteRequest = {
        baseAmount: data.baseAmount,
        surchargeAmount: data.surcharge,
        discountAmount: data.discount,
        pricingNotes: data.notes
      }
      await orderService.submitQuote(orderId, quoteRequest)
      await fetchOrderDetail() // Refresh status
      setShowQuote(false)
    } catch (error) {
      alert('報價送出失敗，請檢查網路狀態。')
    } finally {
      setIsLoading(false)
    }
  }

  const handleReferralConfirm = (partner) => {
    console.log('Referral Sent to:', partner.name)
    // To be implemented: orderService.referOrder(orderId, partner.id)
    setShowReferral(false)
  }

  if (isLoading || !order) return <div className="p-20 text-center italic opacity-20">Loading Strategic Insight...</div>

  return (
    <div className="min-h-screen bg-surface text-on-surface pb-40">
      {/* Editorial Header */}
      <nav className="px-6 py-6 flex items-center justify-between sticky top-0 bg-surface/80 backdrop-blur-xl z-50">
        <button onClick={() => navigate(-1)} className="p-2 -ml-2 text-on-surface-variant hover:text-primary transition-colors">
          <span className="material-symbols-outlined text-2xl">arrow_back</span>
        </button>
        <div className="text-center">
            <h1 className="text-sm font-extrabold font-headline uppercase tracking-[0.2em]">訂單詳情</h1>
            <p className="text-[10px] font-bold opacity-30 uppercase tracking-tighter mt-1">{order.orderStatus} • {order.id}</p>
        </div>
        <button className="p-2 -mr-2 text-on-surface-variant">
          <span className="material-symbols-outlined text-2xl">more_vert</span>
        </button>
      </nav>

      <motion.main 
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        className="px-8 mt-4 space-y-12 max-w-xl mx-auto"
      >
        {/* Core Request Info */}
        <section className="space-y-6">
           <div className="flex items-center gap-6">
              <div className="flex -space-x-4">
                 <div className="w-20 h-20 rounded-[32px] border-4 border-surface overflow-hidden shadow-xl ring-1 ring-on-surface/5">
                    <img src="https://images.unsplash.com/photo-1514888286974-6c03e2ca1dba?auto=format&fit=crop&q=80&w=200" className="w-full h-full object-cover" />
                 </div>
                 <div className="w-20 h-20 rounded-[32px] border-4 border-surface overflow-hidden shadow-xl ring-1 ring-on-surface/5">
                    <img src="https://images.unsplash.com/photo-1533738363-b7f9aef128ce?auto=format&fit=crop&q=80&w=200" className="w-full h-full object-cover" />
                 </div>
              </div>
              <div className="space-y-1">
                 <h2 className="text-3xl font-extrabold font-headline tracking-tighter">{order.clientName}</h2>
                 <p className="text-xs font-bold opacity-40 uppercase tracking-widest">{order.serviceType}</p>
              </div>
           </div>

           <div className="grid grid-cols-2 gap-4">
              <div className="bg-surface-container-low p-5 rounded-[32px] border border-outline-variant/10">
                 <p className="text-[10px] font-bold opacity-30 uppercase tracking-[0.2em] mb-1">Service Date</p>
                 <p className="text-sm font-bold">{order.date}</p>
              </div>
              <div className="bg-surface-container-low p-5 rounded-[32px] border border-outline-variant/10">
                 <p className="text-[10px] font-bold opacity-30 uppercase tracking-[0.2em] mb-1">Base Fee</p>
                 <p className="text-sm font-bold text-primary">${order.baseAmount}</p>
              </div>
           </div>
        </section>

        {/* Questionnaire Answers Section (Spec 5.1) */}
        <section className="space-y-6">
           <h3 className="text-[10px] font-bold tracking-[0.3em] text-on-surface-variant/40 uppercase px-1">事前問護問卷評估</h3>
           <div className="space-y-8">
              {order.answers.map((ans, idx) => (
                <div key={idx} className="space-y-2 relative pl-6 border-l-2 border-primary/10">
                   <p className="text-xs font-bold text-primary opacity-60 uppercase tracking-tight">{ans.question}</p>
                   <p className="text-sm font-medium leading-relaxed italic">{ans.answer}</p>
                </div>
              ))}
           </div>
        </section>

        {/* Support Section */}
        <div className="p-8 bg-surface-container-highest rounded-[40px] text-center space-y-4">
          <p className="text-[10px] font-bold opacity-40 uppercase tracking-widest leading-relaxed">
            如對問卷回答有疑慮，可先透過對話功能與家長進一步確認環境細節。
          </p>
          <button className="text-xs font-extrabold text-primary border-b border-primary/20 pb-0.5">
            於問卷下方發起討論
          </button>
        </div>
      </motion.main>

      {/* Decision Action Bar */}
      <footer className="fixed bottom-0 w-full z-50 p-8 glass-effect border-t border-outline-variant/5">
        <div className="max-w-xl mx-auto flex gap-4">
          {order.orderStatus === 'PENDING' ? (
            <>
               <button 
                 onClick={() => setShowReferral(true)}
                 className="w-16 h-16 flex items-center justify-center bg-surface-container-high rounded-full hover:bg-surface-container-highest transition-all"
               >
                 <span className="material-symbols-outlined">hub</span>
               </button>
               <button
                 onClick={() => setShowQuote(true)}
                 className="flex-1 py-5 bg-on-surface text-surface rounded-full text-sm font-extrabold tracking-widest uppercase shadow-2xl hover:scale-[1.02] active:scale-95 transition-all"
               >
                 專業報價
               </button>
            </>
          ) : (
            <div className="flex-1 py-5 bg-surface-container-low border border-outline-variant/10 rounded-full text-center">
               <span className="text-[10px] font-bold uppercase tracking-widest opacity-40">
                  {order.orderStatus === 'QUOTED' ? '已送出報價 · 待付款' : order.orderStatus}
               </span>
            </div>
          )}
        </div>
      </footer>

      <AnimatePresence>
        <QuoteModal 
           isOpen={showQuote} 
           onClose={() => setShowQuote(false)} 
           baseAmount={order.totalAmount}
           onConfirm={handleQuoteConfirm}
        />
        <ReferralModal 
           isOpen={showReferral} 
           onClose={() => setShowReferral(false)}
           partners={[
             { id: 's2', name: 'Lucas Zhang', location: 'Xinyi Dist', avatar: 'https://images.unsplash.com/photo-1472099645785-5658abf4ff4e?auto=format&fit=crop&q=80&w=200' }
           ]}
           onConfirm={handleReferralConfirm}
        />
      </AnimatePresence>
    </div>
  )
}

export default OrderDetail
