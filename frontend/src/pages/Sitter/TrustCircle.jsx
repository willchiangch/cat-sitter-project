import React, { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { motion, AnimatePresence } from 'framer-motion'
import { trustCircleService } from '../../services/api'

const TrustCircle = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [partners, setPartners] = useState([])
  const [isLoading, setIsLoading] = useState(true)
  const [removingId, setRemovingId] = useState(null)

  // Search state
  const [showSearch, setShowSearch] = useState(false)
  const [searchSlug, setSearchSlug] = useState('')
  const [isSearching, setIsSearching] = useState(false)
  const [searchResult, setSearchResult] = useState(null)
  const [searchError, setSearchError] = useState('')
  const [isAdding, setIsAdding] = useState(false)

  useEffect(() => { document.querySelector('main')?.scrollTo({ top: 0, behavior: 'instant' }) }, [])

  useEffect(() => {
    const fetchData = async () => {
      try {
        setIsLoading(true)
        const partnersData = await trustCircleService.listSitters()
        setPartners(partnersData)
      } catch (error) {
        console.error('Failed to fetch trust circle data:', error)
      } finally {
        setIsLoading(false)
      }
    }
    fetchData()
  }, [])

  const handleSearch = async () => {
    const slug = searchSlug.trim()
    if (!slug) return
    setIsSearching(true)
    setSearchError('')
    setSearchResult(null)
    try {
      const data = await trustCircleService.searchBySlug(slug)
      setSearchResult(data.sitterProfile)
    } catch (e) {
      setSearchError('找不到此保母，請確認網址代碼是否正確')
    } finally {
      setIsSearching(false)
    }
  }

  const handleAdd = async () => {
    if (!searchResult?.profileId) return
    setIsAdding(true)
    try {
      await trustCircleService.add(searchResult.profileId)
      const updated = await trustCircleService.listSitters()
      setPartners(updated)
      setSearchResult(null)
      setSearchSlug('')
      setShowSearch(false)
    } catch (e) {
      setSearchError('加入失敗，該保母可能已在信任圈中')
    } finally {
      setIsAdding(false)
    }
  }

  const handleRemovePartner = async (itemId) => {
    if (!window.confirm('確定要從信任圈移除此夥伴？')) return
    setRemovingId(itemId)
    try {
      await trustCircleService.remove(itemId)
      setPartners(prev => prev.filter(p => p.id !== itemId))
    } catch (e) {
      console.error('Failed to remove partner:', e)
    } finally {
      setRemovingId(null)
    }
  }

  const copyReferralLink = (slug) => {
    const link = `${window.location.origin}/booking/sitter/${slug}`
    navigator.clipboard.writeText(link)
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
        {/* Trust Circle Explanation */}
        <section className="bg-primary/5 border border-primary/10 p-6 rounded-[32px] space-y-3">
          <div className="flex items-center gap-3 text-primary">
            <span className="material-symbols-outlined">hub</span>
            <h3 className="text-xs font-extrabold uppercase tracking-widest">關於信任圈</h3>
          </div>
          <p className="text-xs font-medium leading-relaxed opacity-60">
            信任圈是您的「夥伴保母白名單」。可以分享您信任的夥伴保母給客戶，讓客戶在您無法接單時有更好的選擇。
          </p>
        </section>

        {/* Partner List (Sitter to Sitter) */}
        <section className="space-y-4">
          <div className="flex items-center justify-between px-1">
            <h3 className="text-[10px] font-bold tracking-[0.3em] text-on-surface-variant/40 uppercase">信任夥伴 ({partners.length})</h3>
            <button
              onClick={() => { setShowSearch(v => !v); setSearchResult(null); setSearchError(''); setSearchSlug('') }}
              className="text-[10px] font-bold text-primary flex items-center gap-1 px-3 py-1.5 bg-primary/10 rounded-full border border-primary/20 hover:bg-primary/20 transition-colors active:scale-95"
            >
              <span className="material-symbols-outlined text-sm">{showSearch ? 'close' : 'person_add'}</span>
              {showSearch ? '取消' : '加入清單'}
            </button>
          </div>

          {/* Search Panel */}
          <AnimatePresence>
            {showSearch && (
              <motion.div
                initial={{ opacity: 0, y: -8 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -8 }}
                className="bg-surface-container-low border border-outline-variant/10 rounded-[28px] p-6 space-y-4"
              >
                <p className="text-[10px] font-black uppercase tracking-widest opacity-40">輸入夥伴保母的接單網址代碼</p>
                <div className="flex gap-2">
                  <input
                    type="text"
                    placeholder="例：sitter-mary-taipei"
                    value={searchSlug}
                    onChange={e => setSearchSlug(e.target.value)}
                    onKeyDown={e => e.key === 'Enter' && handleSearch()}
                    className="flex-1 px-4 py-3 bg-surface border border-outline-variant/20 rounded-2xl text-sm font-bold outline-none focus:border-primary transition-colors"
                  />
                  <button
                    onClick={handleSearch}
                    disabled={isSearching || !searchSlug.trim()}
                    className="px-5 py-3 bg-primary text-on-primary rounded-2xl text-xs font-extrabold uppercase tracking-widest transition-all active:scale-95 disabled:opacity-40"
                  >
                    {isSearching ? '...' : '搜尋'}
                  </button>
                </div>

                {searchError && (
                  <p className="text-xs font-bold text-error px-1">{searchError}</p>
                )}

                {searchResult && (
                  <motion.div
                    initial={{ opacity: 0 }}
                    animate={{ opacity: 1 }}
                    className="flex items-center justify-between p-4 bg-surface border border-primary/20 rounded-2xl"
                  >
                    <div className="flex items-center gap-3">
                      <div className="w-12 h-12 rounded-2xl overflow-hidden bg-primary/10 flex items-center justify-center flex-shrink-0">
                        {searchResult.avatarUrl ? (
                          <img src={searchResult.avatarUrl} className="w-full h-full object-cover" alt={searchResult.name} />
                        ) : (
                          <span className="material-symbols-outlined text-primary">person</span>
                        )}
                      </div>
                      <div>
                        <h4 className="text-sm font-extrabold">{searchResult.name}</h4>
                        {searchResult.bioSummary && (
                          <p className="text-[10px] font-bold opacity-40 mt-0.5 max-w-[180px] truncate">{searchResult.bioSummary}</p>
                        )}
                      </div>
                    </div>
                    <button
                      onClick={handleAdd}
                      disabled={isAdding}
                      className="px-4 py-2 bg-primary text-on-primary rounded-full text-[10px] font-extrabold uppercase tracking-widest active:scale-95 transition-all disabled:opacity-50 shadow-lg shadow-primary/20"
                    >
                      {isAdding ? '加入中...' : '加入'}
                    </button>
                  </motion.div>
                )}
              </motion.div>
            )}
          </AnimatePresence>

          {/* Partner Cards */}
          <div className="space-y-3">
            <AnimatePresence>
              {partners.map((item) => {
                const partner = item.trustedSitter
                return (
                  <motion.div
                    key={item.id}
                    layout
                    className="bg-surface-container-low p-5 rounded-[40px] border border-outline-variant/10 flex items-center justify-between group"
                  >
                    <div className="flex items-center gap-4">
                      <div className="w-14 h-14 rounded-[20px] overflow-hidden bg-primary/10 flex items-center justify-center">
                        {partner.avatarUrl ? (
                          <img src={partner.avatarUrl} className="w-full h-full object-cover" alt={partner.name} />
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
                      <button
                        onClick={() => handleRemovePartner(item.id)}
                        disabled={removingId === item.id}
                        className="w-11 h-11 flex items-center justify-center bg-error/10 text-error rounded-full hover:scale-110 active:scale-95 transition-all"
                        title="從信任圈移除"
                      >
                        <span className="material-symbols-outlined text-xl">
                          {removingId === item.id ? 'progress_activity' : 'person_remove'}
                        </span>
                      </button>
                    </div>
                  </motion.div>
                )
              })}
            </AnimatePresence>
            {partners.length === 0 && !isLoading && (
              <div className="p-12 text-center border-2 border-dashed border-outline-variant/10 rounded-[40px] opacity-20 italic">
                尚未加入任何信任夥伴。
              </div>
            )}
          </div>
        </section>
      </motion.main>
    </div>
  )
}

export default TrustCircle
