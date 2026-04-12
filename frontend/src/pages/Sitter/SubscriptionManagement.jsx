import React, { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { motion, AnimatePresence } from 'framer-motion'
import { subscriptionService } from '../../services/api'

const PLANS = [
  {
    id: 'FREE',
    name: '免費版',
    price: 0,
    period: '',
    color: 'from-on-surface-variant/20 to-outline/10',
    features: [
      '接單上限：3 筆 / 月',
      '照護回報：僅限文字',
      '定價：固定牌價',
      '媒體保留：7 天',
    ],
  },
  {
    id: 'STANDARD',
    name: '基礎版',
    price: 499,
    period: '/月',
    color: 'from-secondary/60 to-secondary/20',
    features: [
      '接單上限：20 筆 / 月',
      '照護回報：5 張照片 + 文字',
      '定價：固定牌價',
      '媒體保留：30 天',
    ],
  },
  {
    id: 'PRO',
    name: '專業版',
    price: 899,
    period: '/月',
    color: 'from-[#1e3a8a] to-[#3b82f6]',
    highlight: true,
    features: [
      '接單上限：無限制',
      '照護回報：10 張照片 + 2 影片',
      '定價：自由手動調價',
      '媒體保留：90 天',
      '黑名單門禁',
      '特定日期開放預約',
    ],
  },
  {
    id: 'PREMIUM',
    name: '頂級版',
    price: 1299,
    period: '/月',
    color: 'from-[#1e3a8a] to-[#7c3aed]',
    highlight: true,
    features: [
      '接單上限：無限制',
      '照護回報：25 張照片 + 5 影片',
      '定價：自由手動調價',
      '媒體保留：永久保留',
      '白名單 + 黑名單門禁',
      '額滿日期控制',
      '特定日期開放預約',
    ],
  },
]

const SubscriptionManagement = () => {
  const navigate = useNavigate()
  const [currentSub, setCurrentSub] = useState(null)
  const [isLoading, setIsLoading] = useState(true)
  const [showCancelConfirm, setShowCancelConfirm] = useState(false)
  const [isCancelling, setIsCancelling] = useState(false)
  const [isChanging, setIsChanging] = useState(null)
  const [billingCycle, setBillingCycle] = useState('monthly')
  const [showAgreement, setShowAgreement] = useState(false)
  const [selectedPlanId, setSelectedPlanId] = useState(null)
  const [isProcessingPayment, setIsProcessingPayment] = useState(false)

  useEffect(() => {
    subscriptionService.getCurrent()
      .then(data => setCurrentSub(data))
      .catch(() => setCurrentSub({ planId: 'PRO', status: 'ACTIVE', renewsAt: '2026-05-04' }))
      .finally(() => setIsLoading(false))
  }, [])

  const initiateChangePlan = (planId) => {
    if (planId === currentSub?.planId) return
    setSelectedPlanId(planId)
    setShowAgreement(true)
  }

  const handleConfirmAndPay = async () => {
    setShowAgreement(false)
    setIsProcessingPayment(true)
    
    // Simulate payment processing time
    await new Promise(resolve => setTimeout(resolve, 2500))
    
    try {
      const updated = await subscriptionService.changePlan(selectedPlanId)
      
      // Show success state briefly
      await new Promise(resolve => setTimeout(resolve, 1000))
      
      setCurrentSub(updated)
      setSelectedPlanId(null)
    } catch (e) {
      console.error('Plan change failed:', e)
      alert('付費處理發生錯誤，請稍後再試。')
    } finally {
      setIsProcessingPayment(false)
    }
  }

  const handleChangePlan = initiateChangePlan

  const handleCancel = async () => {
    setIsCancelling(true)
    try {
      await subscriptionService.cancel()
      setCurrentSub(prev => ({ ...prev, status: 'CANCELLED' }))
      setShowCancelConfirm(false)
    } catch (e) {
      console.error('Cancel failed:', e)
    } finally {
      setIsCancelling(false)
    }
  }

  const currentPlan = PLANS.find(p => p.id === currentSub?.planId) || PLANS[2]
  const annualDiscount = 0.85 // 15% off

  return (
    <div className="min-h-screen bg-surface text-on-surface pb-32">
      {/* Header */}
      <nav className="sticky top-0 z-50 bg-surface/80 backdrop-blur-md px-4 py-4 flex items-center justify-between border-b border-outline-variant/5">
        <button onClick={() => navigate(-1)} className="p-2 -ml-2 text-on-surface-variant hover:text-primary transition-colors">
          <span className="material-symbols-outlined text-2xl">arrow_back</span>
        </button>
        <h1 className="text-sm font-extrabold font-headline uppercase tracking-tighter">訂閱方案管理</h1>
        <div className="w-10" />
      </nav>

      <motion.main initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="px-5 pt-6 space-y-6 max-w-xl mx-auto">

        {/* Current Plan Status */}
        {!isLoading && currentSub && (
          <section className={`relative overflow-hidden rounded-[40px] p-7 bg-gradient-to-br ${currentPlan.color} ${currentPlan.highlight ? 'text-on-primary' : 'text-on-surface'} shadow-2xl`}>
            <div className="absolute top-0 right-0 p-4 opacity-10">
              <span className="material-symbols-outlined text-9xl">verified</span>
            </div>
            <div className="relative z-10 space-y-4">
              <div>
                <p className="text-[10px] font-bold tracking-[0.3em] uppercase opacity-70">目前方案</p>
                <h3 className="text-4xl font-extrabold font-headline tracking-tighter italic mt-1">{currentPlan.name}</h3>
              </div>
              <div className="flex items-end gap-1">
                <span className="text-3xl font-black font-headline">${currentPlan.price}</span>
                <span className="text-sm font-bold opacity-70 mb-0.5">{currentPlan.period}</span>
              </div>
              {currentSub.renewsAt && (
                <div className="flex flex-col gap-1">
                  <p className="text-xs font-bold opacity-70">
                    {currentSub.status === 'CANCELLED' ? '✕ 已取消，有效至 ' : '下次續費：'}
                    {currentSub.renewsAt}
                  </p>
                  <div className="inline-flex items-center gap-1 opacity-40">
                    <span className="material-symbols-outlined text-[10px]">gavel</span>
                    <span className="text-[9px] font-bold uppercase tracking-widest">已同意服務協議</span>
                  </div>
                </div>
              )}
            </div>
          </section>
        )}

        {/* Billing Cycle Toggle */}
        <div className="flex bg-surface-container-low rounded-full p-1">
          <button
            onClick={() => setBillingCycle('monthly')}
            className={`flex-1 py-2.5 text-xs font-extrabold uppercase tracking-widest rounded-full transition-all ${
              billingCycle === 'monthly' ? 'bg-navy text-white shadow-lg shadow-navy/20' : 'text-on-surface-variant'
            }`}
          >
            月繳
          </button>
          <button
            onClick={() => setBillingCycle('annual')}
            className={`flex-1 py-2.5 text-xs font-extrabold uppercase tracking-widest rounded-full transition-all relative ${
              billingCycle === 'annual' ? 'bg-navy text-white shadow-lg shadow-navy/20' : 'text-on-surface-variant'
            }`}
          >
            年繳
            <span className="absolute -top-1.5 -right-1 px-1.5 py-0.5 bg-error text-white text-[8px] font-black rounded-full">-15%</span>
          </button>
        </div>

        {/* Plan Cards */}
        <div className="space-y-4">
          {PLANS.map(plan => {
            const isCurrent = plan.id === currentSub?.planId
            const price = billingCycle === 'annual' && plan.price > 0
              ? Math.round(plan.price * annualDiscount)
              : plan.price
            return (
              <motion.div
                key={plan.id}
                layout
                className={`p-6 rounded-[32px] border transition-all ${
                  isCurrent
                    ? 'bg-navy/5 border-navy/30 ring-2 ring-navy/10'
                    : 'bg-surface-container-low border-outline-variant/10'
                }`}
              >
                <div className="flex justify-between items-start mb-4">
                  <div>
                    <div className="flex items-center gap-2">
                      <h4 className="text-base font-extrabold tracking-tight">{plan.name}</h4>
                      {isCurrent && (
                        <span className="px-2 py-0.5 bg-navy text-white text-[9px] font-black rounded-full uppercase tracking-widest">目前</span>
                      )}
                    </div>
                    <div className="flex items-end gap-1 mt-1">
                      <span className="text-2xl font-black text-navy">${price}</span>
                      {plan.period && (
                        <span className="text-xs font-bold opacity-40 mb-0.5">
                          {billingCycle === 'annual' ? '/月（年繳）' : plan.period}
                        </span>
                      )}
                    </div>
                  </div>
                  {!isCurrent && (
                    <button
                      onClick={() => handleChangePlan(plan.id)}
                      disabled={isChanging === plan.id}
                      className="px-4 py-2 bg-navy text-white rounded-full text-[10px] font-extrabold uppercase tracking-widest active:scale-95 transition-all disabled:opacity-50 shadow-lg shadow-navy/20"
                    >
                      {isChanging === plan.id ? '切換中...' : '切換'}
                    </button>
                  )}
                </div>
                <ul className="space-y-1.5">
                  {plan.features.map((f, i) => (
                    <li key={i} className="flex items-center gap-2 text-xs font-bold opacity-60">
                      <span className="material-symbols-outlined text-sm text-navy">check_circle</span>
                      {f}
                    </li>
                  ))}
                </ul>
              </motion.div>
            )
          })}
        </div>

        {/* Cancel Subscription */}
        {currentSub?.status !== 'CANCELLED' && currentSub?.planId !== 'FREE' && (
          <section className="pt-4">
            <button
              onClick={() => setShowCancelConfirm(true)}
              className="w-full py-4 text-[10px] font-black text-error/50 hover:text-error transition-colors uppercase tracking-widest border border-error/10 rounded-full hover:border-error/30"
            >
              取消訂閱
            </button>
          </section>
        )}
      </motion.main>

      {/* Payment Processing Overlay */}
      <AnimatePresence>
        {isProcessingPayment && (
          <div className="fixed inset-0 z-[200] flex items-center justify-center bg-navy/90 backdrop-blur-md">
            <div className="text-center space-y-6">
              <div className="relative w-24 h-24 mx-auto">
                <div className="absolute inset-0 border-4 border-white/20 rounded-full" />
                <motion.div
                  className="absolute inset-0 border-4 border-t-white rounded-full"
                  animate={{ rotate: 360 }}
                  transition={{ duration: 1, repeat: Infinity, ease: "linear" }}
                />
              </div>
              <div className="space-y-2">
                <h3 className="text-xl font-extrabold text-white font-headline italic tracking-tighter">付費處理中...</h3>
                <p className="text-xs font-bold text-white/40 uppercase tracking-widest">Securely processing your transaction</p>
              </div>
            </div>
          </div>
        )}
      </AnimatePresence>

      {/* Subscription Agreement Modal */}
      <AnimatePresence>
        {showAgreement && selectedPlanId && (
          <div className="fixed inset-0 z-[110] flex items-end sm:items-center justify-center p-4">
            <motion.div
              initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
              onClick={() => setShowAgreement(false)}
              className="absolute inset-0 bg-black/60 backdrop-blur-sm"
            />
            <motion.div
              initial={{ y: 100, opacity: 0 }} animate={{ y: 0, opacity: 1 }} exit={{ y: 100, opacity: 0 }}
              className="relative w-full max-w-lg bg-surface rounded-[40px] p-8 shadow-2xl space-y-6 max-h-[90vh] overflow-y-auto"
            >
              <div className="space-y-2">
                <h3 className="text-2xl font-black font-headline tracking-tighter">服務合約與訂閱協議</h3>
                <p className="text-xs font-bold opacity-30 uppercase tracking-widest">Terms of Service & Subscription Agreement</p>
              </div>
              
              <div className="bg-surface-container-low rounded-3xl p-6 space-y-4 text-sm font-medium leading-relaxed opacity-80">
                <div className="flex gap-3">
                  <span className="w-5 h-5 bg-navy text-white text-[10px] font-black rounded-lg flex items-center justify-center shrink-0 mt-0.5">1</span>
                  <p>本方案採自動續約機制，系統將於每個月到期前自動寄發續約通知與刷卡連結。</p>
                </div>
                <div className="flex gap-3">
                  <span className="w-5 h-5 bg-navy text-white text-[10px] font-black rounded-lg flex items-center justify-center shrink-0 mt-0.5">2</span>
                  <p>若於扣款失敗或未於期限內完成付費，帳號功能將自動降級回「免費方案」。</p>
                </div>
                <div className="flex gap-3">
                  <span className="w-5 h-5 bg-navy text-white text-[10px] font-black rounded-lg flex items-center justify-center shrink-0 mt-0.5">3</span>
                  <p>訂閱方案一經啟用受法律效力保護，恕不接受中途退費或取消當月合約。</p>
                </div>
                <div className="flex gap-3">
                  <span className="w-5 h-5 bg-navy text-white text-[10px] font-black rounded-lg flex items-center justify-center shrink-0 mt-0.5">4</span>
                  <p>本系統僅支援「升級方案」（需依剩餘天數比例補差價），生效截止時間維持原合約日期；目前暫不支援降級服務。</p>
                </div>
              </div>

              <div className="flex flex-col gap-3">
                <button
                  onClick={handleConfirmAndPay}
                  className="w-full py-5 bg-navy text-white rounded-full text-sm font-black uppercase tracking-widest shadow-xl shadow-navy/20 active:scale-95 transition-all"
                >
                  同意並立即支付 ${PLANS.find(p => p.id === selectedPlanId)?.price}
                </button>
                <button
                  onClick={() => setShowAgreement(false)}
                  className="w-full py-4 text-xs font-bold opacity-30 uppercase tracking-widest hover:opacity-60 transition-opacity"
                >
                   我再考慮一下
                </button>
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>

      {/* Cancel Confirm Dialog */}
      <AnimatePresence>
        {showCancelConfirm && (
          <div className="fixed inset-0 z-[100] flex items-center justify-center p-6">
            <motion.div
              initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
              onClick={() => setShowCancelConfirm(false)}
              className="absolute inset-0 bg-black/60 backdrop-blur-sm"
            />
            <motion.div
              initial={{ scale: 0.9, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} exit={{ scale: 0.9, opacity: 0 }}
              className="relative w-full max-w-sm bg-surface rounded-[32px] p-8 shadow-2xl space-y-6 text-center"
            >
              <div className="w-16 h-16 bg-error/10 rounded-full flex items-center justify-center mx-auto">
                <span className="material-symbols-outlined text-3xl text-error">cancel</span>
              </div>
              <div>
                <h3 className="text-xl font-extrabold font-headline tracking-tighter">確定取消訂閱？</h3>
                <p className="text-xs font-bold opacity-40 mt-2 leading-relaxed">
                  取消後帳號功能將在目前計費週期結束後受到限制。您可以隨時重新訂閱。
                </p>
              </div>
              <div className="flex gap-3">
                <button
                  onClick={() => setShowCancelConfirm(false)}
                  className="flex-1 py-3.5 bg-surface-container-low rounded-full text-sm font-bold border border-outline-variant/20 hover:bg-surface-container transition-colors"
                >
                  返回
                </button>
                <button
                  onClick={handleCancel}
                  disabled={isCancelling}
                  className="flex-1 py-3.5 bg-error text-white rounded-full text-sm font-bold active:scale-95 transition-all disabled:opacity-50"
                >
                  {isCancelling ? '取消中...' : '確認取消'}
                </button>
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>
    </div>
  )
}

export default SubscriptionManagement
