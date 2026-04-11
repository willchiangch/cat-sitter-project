import React, { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { motion, AnimatePresence } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { orderService } from '../../services/api'

const Finance = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [data, setData] = useState(null)
  const [isLoading, setIsLoading] = useState(true)
  const [activeTab, setActiveTab] = useState('PENDING')
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

  const StatCard = ({ label, value, suffix = "" }) => (
    <div className="bg-surface-container-low p-5 rounded-[32px] border border-outline-variant/10 space-y-1">
      <p className="text-sm font-black uppercase tracking-widest text-on-surface-variant/60">{label}</p>
      <div className="flex items-baseline gap-1">
        <span className="text-2xl font-extrabold font-headline">{value.toLocaleString()}</span>
        <span className="text-xs font-bold opacity-40">{suffix}</span>
      </div>
    </div>
  )

  const tabs = [
    { id: 'PENDING', label: '待付款' },
    { id: 'HISTORY', label: '收款紀錄' }
  ]

  if (isLoading || !data) return <div className="p-20 text-center italic opacity-20">載入中...</div>

  const pendingTransactions = data.recentTransactions.filter(tx => tx.status !== 'COMPLETED')
  const completedTransactions = data.recentTransactions.filter(tx => tx.status === 'COMPLETED')

  const TransactionItem = ({ tx }) => (
    <div
      className="bg-surface-container-low p-5 rounded-[32px] border border-outline-variant/10 flex items-center justify-between group hover:border-primary/20 transition-colors cursor-pointer"
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
            <p className="text-[10px] font-bold text-primary mt-0.5">{tx.catNames}</p>
          )}
        </div>
      </div>
      <div className="text-right">
        <p className="text-base font-extrabold font-headline">${tx.amount}</p>
        <p className="text-[10px] font-bold tracking-widest opacity-20 uppercase">{tx.status}</p>
      </div>
    </div>
  )

  return (
    <div className="min-h-screen bg-surface text-on-surface pb-32">
      {/* Header */}
      <nav className="sticky top-0 z-50 bg-surface/80 backdrop-blur-md px-6 py-4 flex items-center justify-between border-b border-outline-variant/10">
        <h1 className="text-base font-extrabold font-headline uppercase tracking-tighter">{t('common.finance')}</h1>
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
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        className="px-5 pt-8 space-y-8 max-w-xl mx-auto"
      >
        {activeTab === 'PENDING' && (
          <>
            {/* Payout Hero Card */}
            <section className="relative overflow-hidden rounded-[40px] p-8 bg-on-surface text-surface shadow-2xl">
              <div className="relative z-10 space-y-6">
                <div className="space-y-1">
                  <p className="text-sm font-black tracking-widest uppercase text-surface/60">可提領餘額</p>
                  <h2 className="text-5xl font-extrabold font-headline tracking-tighter">
                    ${data.withdrawableBalance.toLocaleString()}
                  </h2>
                </div>
                <button
                  onClick={() => {
                    setPayoutAmount(data.withdrawableBalance)
                    setShowPayoutModal(true)
                  }}
                  className="w-full py-4 bg-primary text-on-primary rounded-full text-sm font-black hover:scale-[1.02] active:scale-95 transition-all shadow-xl shadow-primary/20"
                >
                  申請提款
                </button>
              </div>
              <div className="absolute -bottom-10 -right-10 opacity-5">
                <span className="material-symbols-outlined text-[200px]">payments</span>
              </div>
            </section>

            {/* Performance Stats */}
            <section className="space-y-4">
              <h3 className="text-sm font-black uppercase tracking-widest text-on-surface-variant/60 px-1">收益總覽</h3>
              <div className="grid grid-cols-2 gap-3">
                <StatCard label="總收益" value={data.totalRevenue} suffix="TWD" />
                <StatCard label="進行訂單" value={data.activeOrderCount} suffix="筆" />
                <div className="col-span-2">
                  <StatCard label="平均訂單金額" value={data.averageOrderValue} suffix="每次" />
                </div>
              </div>
            </section>

            {/* Pending Transactions */}
            <section className="space-y-4">
              <h3 className="text-sm font-black uppercase tracking-widest text-on-surface-variant/60 px-1">待確認款項</h3>
              {pendingTransactions.length > 0 ? (
                <div className="space-y-3">
                  {pendingTransactions.map((tx) => (
                    <TransactionItem key={tx.orderId} tx={tx} />
                  ))}
                </div>
              ) : (
                <div className="py-16 text-center space-y-4 opacity-30">
                  <span className="material-symbols-outlined text-5xl">checklist</span>
                  <p className="text-xs font-bold tracking-widest uppercase">目前沒有待確認款項</p>
                </div>
              )}
            </section>
          </>
        )}

        {activeTab === 'HISTORY' && (
          <section className="space-y-4">
            <h3 className="text-sm font-black uppercase tracking-widest text-on-surface-variant/60 px-1">已完成收款</h3>
            {completedTransactions.length > 0 ? (
              <div className="space-y-3">
                {completedTransactions.map((tx) => (
                  <TransactionItem key={tx.orderId} tx={tx} />
                ))}
              </div>
            ) : (
              <div className="py-24 text-center space-y-4 opacity-30">
                <span className="material-symbols-outlined text-6xl">receipt_long</span>
                <p className="text-xs font-bold tracking-widest uppercase">尚無收款紀錄</p>
              </div>
            )}
          </section>
        )}
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
                    <h3 className="text-2xl font-extrabold font-headline tracking-tight mb-2">申請提款</h3>
                    <p className="text-xs font-medium text-on-surface-variant opacity-60">將收益提領至您的銀行帳戶。</p>
                  </header>

                  <div className="space-y-4">
                    <div className="bg-surface-container-low p-6 rounded-3xl border border-outline-variant/5">
                      <p className="text-sm font-black tracking-widest text-primary uppercase mb-2">提款金額</p>
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
                        <p className="opacity-40 uppercase tracking-widest">目標銀行帳戶</p>
                        <p>玉山銀行 (808) **** 8291</p>
                      </div>
                    </div>
                  </div>

                  <div className="flex gap-3">
                    <button
                      onClick={() => setShowPayoutModal(false)}
                      disabled={isSubmitting}
                      className="flex-1 py-4 text-sm font-extrabold opacity-40 hover:opacity-100 transition-opacity"
                    >
                      取消
                    </button>
                    <button
                      onClick={async () => {
                        setIsSubmitting(true)
                        await new Promise(r => setTimeout(r, 1500))
                        setIsSubmitting(false)
                        setPayoutSuccess(true)
                        setTimeout(() => {
                          setShowPayoutModal(false)
                          setPayoutSuccess(false)
                        }, 2000)
                      }}
                      disabled={isSubmitting || payoutAmount <= 0}
                      className="flex-[2] py-4 bg-on-surface text-surface rounded-full text-sm font-black shadow-lg flex items-center justify-center gap-2"
                    >
                      {isSubmitting ? (
                        <>
                          <div className="w-4 h-4 border-2 border-surface/20 border-t-surface rounded-full animate-spin" />
                          處理中...
                        </>
                      ) : '確認提款'}
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
                    <h3 className="text-xl font-extrabold font-headline">申請已送出！</h3>
                    <p className="text-[10px] font-bold text-on-surface-variant/40 uppercase tracking-widest leading-relaxed">
                      款項將於 2-3 個工作日內入帳。
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
