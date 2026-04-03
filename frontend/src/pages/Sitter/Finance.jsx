import React, { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { orderService } from '../../services/api'

const Finance = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [data, setData] = useState(null)
  const [isLoading, setIsLoading] = useState(true)
  const [showPayoutModal, setShowPayoutModal] = useState(false)
  const [payoutAmount, setPayoutAmount] = useState(0)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [payoutSuccess, setPayoutSuccess] = useState(false)

  useEffect(() => {
    const fetchFinance = async () => {
      try {
        setIsLoading(true)
        const summary = await orderService.getFinanceSummary()
        setData(summary)
      } catch (error) {
        console.error('Failed to load finance summary:', error)
      } finally {
        setIsLoading(false)
      }
    }
    fetchFinance()
  }, [])

  const StatCard = ({ label, value, trend, suffix = "" }) => (
    <div className="bg-surface-container-low p-5 rounded-[32px] border border-outline-variant/10 space-y-1">
      <p className="text-[10px] font-bold tracking-[0.2em] text-on-surface-variant/40 uppercase">{label}</p>
      <div className="flex items-baseline gap-1">
        <span className="text-2xl font-extrabold font-headline">{value.toLocaleString()}</span>
        <span className="text-xs font-bold opacity-40">{suffix}</span>
      </div>
      {trend && (
        <div className="flex items-center gap-1 mt-2 text-[10px] font-bold text-primary">
          <span className="material-symbols-outlined text-xs">trending_up</span>
          {trend} vs Last Month
        </div>
      )}
    </div>
  )

  if (isLoading || !data) return <div className="p-20 text-center italic opacity-20">Calculating Prosperity...</div>

  return (
    <div className="min-h-screen bg-surface text-on-surface pb-32">
      {/* Header Navigation */}
      <nav className="sticky top-0 z-50 bg-surface/80 backdrop-blur-md px-4 py-4 flex items-center justify-between">
        <button onClick={() => navigate(-1)} className="p-2 -ml-2 text-on-surface-variant hover:text-primary transition-colors">
          <span className="material-symbols-outlined text-2xl">arrow_back</span>
        </button>
        <h1 className="text-base font-extrabold font-headline uppercase tracking-tighter">{t('common.finance')}</h1>
        <div className="w-10"></div>
      </nav>

      <motion.main 
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        className="px-5 pt-4 space-y-10 max-w-xl mx-auto"
      >
        {/* Payout Hero Card */}
        <section className="relative overflow-hidden rounded-[40px] p-8 bg-on-surface text-surface shadow-2xl">
          <div className="relative z-10 space-y-6">
            <div className="space-y-1">
              <p className="text-[10px] font-bold tracking-[0.3em] uppercase opacity-40">Withdrawable Balance</p>
              <h2 className="text-5xl font-extrabold font-headline tracking-tighter">
                ${data.withdrawableBalance.toLocaleString()}
              </h2>
            </div>
            <button 
              onClick={() => {
                setPayoutAmount(data.withdrawableBalance)
                setShowPayoutModal(true)
              }}
              className="w-full py-4 bg-primary text-on-primary rounded-full text-sm font-extrabold hover:scale-[1.02] active:scale-95 transition-all shadow-xl shadow-primary/20"
            >
              Request Payout
            </button>
          </div>
          <div className="absolute -bottom-10 -right-10 opacity-5">
            <span className="material-symbols-outlined text-[200px]">payments</span>
          </div>
        </section>

        {/* Monthly Performance Stats */}
        <section className="space-y-4">
          <div className="flex items-baseline justify-between px-1">
             <h3 className="text-[10px] font-bold tracking-[0.2em] text-on-surface-variant/40 uppercase">Performance Summary</h3>
             <span className="text-[10px] font-bold text-primary">REAL-TIME</span>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <StatCard label="Total Revenue" value={data.totalRevenue} suffix="TWD" />
            <StatCard label="Active Orders" value={data.activeOrderCount} suffix="ORDERS" />
            <div className="col-span-2">
               <StatCard label="Avg. Order Value" value={data.averageOrderValue} suffix="PER VISIT" />
            </div>
          </div>
        </section>

        {/* Transaction Ledger */}
        <section className="space-y-4">
          <div className="flex items-center justify-between px-1">
            <h3 className="text-[10px] font-bold tracking-[0.3em] text-on-surface-variant/40 uppercase">Account Ledger</h3>
            <span className="material-symbols-outlined text-sm opacity-20">filter_list</span>
          </div>
          
          <div className="space-y-3">
            {data.recentTransactions.map((tx) => (
              <div 
                key={tx.orderId} 
                className="bg-surface-container-low p-5 rounded-[32px] border border-outline-variant/10 flex items-center justify-between group hover:border-primary/20 transition-colors"
                onClick={() => navigate(`/sitter/orders/${tx.orderId}`)}
              >
                <div className="flex items-center gap-4">
                  <div className={`w-12 h-12 rounded-2xl ${tx.status === 'COMPLETED' ? 'bg-primary/5 text-primary' : 'bg-surface-container text-outline'} flex items-center justify-center`}>
                    <span className="material-symbols-outlined text-2xl">
                      {tx.status === 'COMPLETED' ? 'check_circle' : 'pending'}
                    </span>
                  </div>
                  <div>
                    <div className="flex items-center gap-2">
                      <h4 className="text-sm font-extrabold tracking-tight">{tx.clientName}</h4>
                      <span className="text-[10px] font-medium opacity-30">{tx.date}</span>
                    </div>
                    {tx.catNames && (
                      <p className="text-[10px] font-bold text-primary mt-0.5">
                        {tx.catNames}
                      </p>
                    )}
                  </div>
                </div>
                <div className="text-right">
                  <p className="text-base font-extrabold font-headline">${tx.amount}</p>
                  <p className="text-[10px] font-bold tracking-widest opacity-20 uppercase">{tx.status}</p>
                </div>
              </div>
            ))}
          </div>
        </section>

        {/* Support Section */}
        <section className="p-8 bg-surface-container-highest rounded-[40px] text-center space-y-4">
          <p className="text-xs font-bold opacity-40 uppercase tracking-widest leading-relaxed">
            Need help with your earnings or fees?
          </p>
          <button className="text-sm font-extrabold text-primary border-b border-primary/20 pb-1">
            Contact Concierge Support
          </button>
        </section>
      </motion.main>

      {/* Payout Modal */}
      <AnimatePresence>
        {showPayoutModal && (
          <div className="fixed inset-0 z-[100] flex items-end sm:items-center justify-center p-0 sm:p-4">
            <motion.div 
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => !isSubmitting && setShowPayoutModal(false)}
              className="absolute inset-0 bg-surface/80 backdrop-blur-sm"
            />
            <motion.div 
              initial={{ y: "100%" }}
              animate={{ y: 0 }}
              exit={{ y: "100%" }}
              transition={{ type: "spring", damping: 30, stiffness: 300 }}
              className="relative w-full max-w-lg bg-surface-container-high rounded-t-[40px] sm:rounded-[48px] p-8 border border-outline-variant/10 shadow-2xl"
            >
              <div className="w-12 h-1.5 bg-on-surface/10 rounded-full mx-auto mb-8 sm:hidden" />
              
              {!payoutSuccess ? (
                <div className="space-y-8">
                  <header>
                    <h3 className="text-2xl font-extrabold font-headline tracking-tight mb-2">Request Payout</h3>
                    <p className="text-xs font-medium text-on-surface-variant opacity-60">Withdraw your earnings to your registered bank account.</p>
                  </header>

                  <div className="space-y-4">
                    <div className="bg-surface-container-low p-6 rounded-3xl border border-outline-variant/5">
                      <p className="text-[10px] font-bold tracking-widest text-primary uppercase mb-2">Withdrawal Amount</p>
                      <div className="flex items-baseline gap-2">
                        <span className="text-2xl font-extrabold opacity-40">$</span>
                        <input 
                          type="number"
                          value={payoutAmount}
                          onChange={(e) => setPayoutAmount(Number(e.target.value))}
                          className="w-full bg-transparent text-4xl font-extrabold font-headline focus:outline-none placeholder:opacity-10"
                          placeholder="0"
                        />
                      </div>
                    </div>

                    <div className="flex items-center gap-4 px-2">
                      <div className="w-10 h-10 rounded-full bg-secondary/10 flex items-center justify-center text-secondary">
                        <span className="material-symbols-outlined text-xl">account_balance</span>
                      </div>
                      <div className="text-[10px] font-bold">
                        <p className="opacity-40 uppercase tracking-widest">Target Bank Account</p>
                        <p>ESUN BANK (808) **** 8291</p>
                      </div>
                    </div>
                  </div>

                  <div className="flex gap-3">
                    <button 
                      onClick={() => setShowPayoutModal(false)}
                      disabled={isSubmitting}
                      className="flex-1 py-4 text-sm font-extrabold opacity-40 hover:opacity-100 transition-opacity"
                    >
                      Cancel
                    </button>
                    <button 
                      onClick={async () => {
                        setIsSubmitting(true)
                        // Simulate API call
                        await new Promise(r => setTimeout(r, 1500))
                        setIsSubmitting(false)
                        setPayoutSuccess(true)
                        setTimeout(() => {
                          setShowPayoutModal(false)
                          setPayoutSuccess(false)
                        }, 2000)
                      }}
                      disabled={isSubmitting || payoutAmount <= 0}
                      className="flex-[2] py-4 bg-on-surface text-surface rounded-full text-sm font-extrabold shadow-lg flex items-center justify-center gap-2"
                    >
                      {isSubmitting ? (
                        <>
                          <div className="w-4 h-4 border-2 border-surface/20 border-t-surface rounded-full animate-spin" />
                          Processing...
                        </>
                      ) : 'Confirm Withdrawal'}
                    </button>
                  </div>
                </div>
              ) : (
                <div className="py-12 text-center space-y-6">
                  <motion.div 
                    initial={{ scale: 0.5, opacity: 0 }}
                    animate={{ scale: 1, opacity: 1 }}
                    className="w-20 h-20 bg-primary/10 text-primary rounded-full mx-auto flex items-center justify-center"
                  >
                    <span className="material-symbols-outlined text-4xl leading-none">check_circle</span>
                  </motion.div>
                  <div className="space-y-1">
                    <h3 className="text-xl font-extrabold font-headline">Request Sent!</h3>
                    <p className="text-[10px] font-bold text-on-surface-variant/40 uppercase tracking-widest leading-relaxed">
                      Your funds will arrive in 2-3 business days.
                    </p>
                  </div>
                </div>
              )}
            </motion.div>
          </div>
        )}
      </AnimatePresence>
    </div>
  )
}

export default Finance
