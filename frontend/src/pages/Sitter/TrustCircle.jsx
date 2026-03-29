import React, { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { motion, AnimatePresence } from 'framer-motion'
import { whitelistService, trustCircleService } from '../../services/api'

const TrustCircle = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [searchQuery, setSearchQuery] = useState('')
  const [partners, setPartners] = useState([])
  const [whitelist, setWhitelist] = useState([])
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    const fetchData = async () => {
      try {
        setIsLoading(true)
        const [partnersData, whitelistData] = await Promise.all([
          trustCircleService.listSitters(),
          whitelistService.list()
        ])
        setPartners(partnersData)
        setWhitelist(whitelistData)
      } catch (error) {
        console.error('Failed to fetch trust circle data:', error)
      } finally {
        setIsLoading(false)
      }
    }
    fetchData()
  }, [])

  const handleToggleSkip = async (clientId, currentStatus) => {
    try {
      const updated = await whitelistService.toggleSkip(clientId, !currentStatus)
      setWhitelist(prev => prev.map(item => item.clientProfile.id === clientId ? updated : item))
    } catch (e) {
      console.error('Failed to toggle skip questionnaire:', e)
    }
  }

  const copyReferralLink = (slug) => {
    const link = `${window.location.origin}/booking/sitter/${slug}`
    navigator.clipboard.writeText(link)
    // Optional: show toast
    alert('已複製預約連結')
  }

  return (
    <div className="min-h-screen bg-surface text-on-surface pb-32">
      {/* Header */}
      <nav className="sticky top-0 z-50 bg-surface/80 backdrop-blur-md px-4 py-4 flex items-center justify-between border-b border-outline-variant/5">
        <button onClick={() => navigate(-1)} className="p-2 -ml-2 text-on-surface-variant hover:text-primary transition-colors">
          <span className="material-symbols-outlined text-2xl">arrow_back</span>
        </button>
        <h1 className="text-sm font-extrabold font-headline uppercase tracking-tighter">{t('sitter.trust_circle')}</h1>
        <div className="w-10"></div>
      </nav>

      <motion.main 
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        className="px-5 pt-8 space-y-10 max-w-xl mx-auto"
      >
        {/* Referral Logic Explanation */}
        <section className="bg-primary/5 border border-primary/10 p-6 rounded-[32px] space-y-3">
          <div className="flex items-center gap-3 text-primary">
            <span className="material-symbols-outlined">hub</span>
            <h3 className="text-xs font-extrabold uppercase tracking-widest">關於夥伴轉介</h3>
          </div>
          <p className="text-xs font-medium leading-relaxed opacity-60">
            信任圈是您的「夥伴白名單」。當您因為行程額滿無法接單時，系統會建議家長選擇這些您信任的夥伴，提升整體的照護品牌力。
          </p>
        </section>

        {/* Search & Add Partner */}
        <section className="space-y-4">
          <h3 className="text-[10px] font-bold tracking-[0.3em] text-on-surface-variant/40 uppercase px-1">尋找夥伴保母</h3>
          <div className="relative">
            <span className="material-symbols-outlined absolute left-5 top-1/2 -translate-y-1/2 text-on-surface-variant/40">search</span>
            <input 
              type="text" 
              placeholder="輸入夥伴代碼或姓名..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full pl-14 pr-6 py-4 bg-surface-container-low border border-outline-variant/10 rounded-full text-sm font-bold outline-none focus:border-primary transition-colors"
            />
          </div>
        </section>

        {/* Partner List (Sitter to Sitter) */}
        <section className="space-y-4">
          <div className="flex items-center justify-between px-1">
            <h3 className="text-[10px] font-bold tracking-[0.3em] text-on-surface-variant/40 uppercase">信任夥伴 ({partners.length})</h3>
            <button className="text-[10px] font-bold text-primary flex items-center gap-1">
               <span className="material-symbols-outlined text-sm">person_add</span>
               邀請
            </button>
          </div>
          <div className="space-y-3">
            <AnimatePresence>
              {partners.map((item) => {
                const partner = item.trustedSitter;
                return (
                  <motion.div 
                    key={item.id}
                    layout
                    className="bg-surface-container-low p-5 rounded-[40px] border border-outline-variant/10 flex items-center justify-between group"
                  >
                    <div className="flex items-center gap-4">
                      <div className="w-14 h-14 rounded-[20px] overflow-hidden bg-primary/10 flex items-center justify-center">
                        {partner.avatarUrl ? (
                           <img src={partner.avatarUrl} className="w-full h-full object-cover" />
                        ) : (
                           <span className="material-symbols-outlined text-primary">person</span>
                        )}
                      </div>
                      <div>
                        <h4 className="text-sm font-extrabold tracking-tight">{partner.name}</h4>
                        <p className="text-[10px] font-bold opacity-30 mt-0.5 uppercase tracking-widest">{partner.address}</p>
                      </div>
                    </div>
                    <div className="flex gap-2">
                      <button 
                        onClick={() => copyReferralLink(partner.slug)}
                        className="w-11 h-11 flex items-center justify-center bg-primary text-white rounded-full hover:scale-110 active:scale-95 transition-all shadow-lg"
                        title="複製預約連結"
                      >
                        <span className="material-symbols-outlined text-xl">link</span>
                      </button>
                    </div>
                  </motion.div>
                );
              })}
            </AnimatePresence>
          </div>
        </section>

        {/* Regular Client Whitelist (Sitter to Client) (V31) */}
        <section className="space-y-4">
          <h3 className="text-[10px] font-bold tracking-[0.3em] text-on-surface-variant/40 uppercase px-1">熟客白名單 ({whitelist.length})</h3>
          <div className="space-y-3">
            {whitelist.map((item) => (
              <div key={item.id} className="bg-surface-container-low p-6 rounded-[40px] border border-outline-variant/10 flex items-center justify-between">
                <div className="flex items-center gap-4">
                   <div className="w-12 h-12 rounded-2xl bg-surface-container flex items-center justify-center">
                      <img src={item.clientProfile.avatarUrl} className="w-full h-full object-cover rounded-2xl" />
                   </div>
                   <div>
                      <h4 className="text-sm font-extrabold">{item.clientProfile.name}</h4>
                      <div className="flex items-center gap-1 mt-1">
                        <span className="material-symbols-outlined text-xs text-primary">verified</span>
                        <span className="text-[10px] font-bold opacity-30 uppercase tracking-widest">REGULAR</span>
                      </div>
                   </div>
                </div>
                
                <div className="flex items-center gap-4">
                   <div className="text-right">
                      <p className="text-[10px] font-bold opacity-40 uppercase tracking-widest leading-none mb-1.5">免填問卷</p>
                      <button 
                        onClick={() => handleToggleSkip(item.clientProfile.id, item.skipQuestionnaire)}
                        className={`w-12 h-6 rounded-full transition-all relative ${item.skipQuestionnaire ? 'bg-primary' : 'bg-surface-container-high'}`}
                      >
                         <motion.div 
                           animate={{ x: item.skipQuestionnaire ? 24 : 4 }}
                           className="absolute top-1 left-0 w-4 h-4 rounded-full bg-white shadow-sm"
                         />
                      </button>
                   </div>
                </div>
              </div>
            ))}
            {whitelist.length === 0 && !isLoading && (
              <div className="p-12 text-center border-2 border-dashed border-outline-variant/10 rounded-[40px] opacity-20 italic">
                尚未加入任何熟客。
              </div>
            )}
          </div>
        </section>
      </motion.main>
    </div>
  )
}

export default TrustCircle
