import React, { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { motion, AnimatePresence } from 'framer-motion'
import { whitelistService, blacklistService } from '../../services/api'

const ClientGate = () => {
  const navigate = useNavigate()
  const [activeTab, setActiveTab] = useState('whitelist')
  const [whitelist, setWhitelist] = useState([])
  const [blacklist, setBlacklist] = useState([])
  const [isLoading, setIsLoading] = useState(true)
  const [searchQuery, setSearchQuery] = useState('')
  const [searchResults, setSearchResults] = useState([])
  const [isSearching, setIsSearching] = useState(false)
  const [removingId, setRemovingId] = useState(null)

  useEffect(() => { document.querySelector('main')?.scrollTo({ top: 0, behavior: 'instant' }) }, [])

  useEffect(() => {
    const fetchData = async () => {
      try {
        setIsLoading(true)
        const [wl, bl] = await Promise.all([
          whitelistService.list(),
          blacklistService.list(),
        ])
        setWhitelist(wl)
        setBlacklist(bl)
      } catch (e) {
        console.error('Failed to fetch gate lists:', e)
      } finally {
        setIsLoading(false)
      }
    }
    fetchData()
  }, [])

  const handleSearch = async () => {
    if (!searchQuery.trim()) return
    setIsSearching(true)
    try {
      const service = activeTab === 'whitelist' ? whitelistService : blacklistService
      const results = await service.search(searchQuery.trim())
      setSearchResults(results)
    } catch (e) {
      console.error('Search failed:', e)
      setSearchResults([])
    } finally {
      setIsSearching(false)
    }
  }

  const handleAdd = async (clientId, clientName) => {
    try {
      if (activeTab === 'whitelist') {
        const added = await whitelistService.add(clientId)
        setWhitelist(prev => [...prev, added])
      } else {
        const added = await blacklistService.add(clientId)
        setBlacklist(prev => [...prev, added])
      }
      setSearchResults(prev => prev.filter(r => r.id !== clientId))
    } catch (e) {
      console.error('Add failed:', e)
    }
  }

  const handleRemove = async (clientId) => {
    setRemovingId(clientId)
    try {
      if (activeTab === 'whitelist') {
        await whitelistService.remove(clientId)
        setWhitelist(prev => prev.filter(item => item.clientProfile?.id !== clientId && item.clientId !== clientId))
      } else {
        await blacklistService.remove(clientId)
        setBlacklist(prev => prev.filter(item => item.clientProfile?.id !== clientId && item.clientId !== clientId))
      }
    } catch (e) {
      console.error('Remove failed:', e)
    } finally {
      setRemovingId(null)
    }
  }

  const currentList = activeTab === 'whitelist' ? whitelist : blacklist
  const isWhitelist = activeTab === 'whitelist'

  const ClientCard = ({ item, isResult = false }) => {
    const profile = item.clientProfile || item
    const clientId = profile.id
    return (
      <div className={`p-5 rounded-[32px] border flex items-center justify-between ${
        isResult
          ? 'bg-surface-container/50 border-outline-variant/10'
          : isWhitelist
            ? 'bg-surface-container-low border-primary/10'
            : 'bg-surface-container-low border-error/10'
      }`}>
        <div className="flex items-center gap-4">
          <div className="w-12 h-12 rounded-2xl overflow-hidden bg-surface-container flex items-center justify-center flex-shrink-0">
            {profile.avatarUrl ? (
              <img src={profile.avatarUrl} className="w-full h-full object-cover" alt={profile.name} />
            ) : (
              <span className="material-symbols-outlined text-on-surface-variant/40">person</span>
            )}
          </div>
          <div>
            <h4 className="text-sm font-extrabold">{profile.name || '未知用戶'}</h4>
            <p className="text-[10px] font-bold opacity-30 mt-0.5">{profile.email || profile.phone || ''}</p>
          </div>
        </div>
        {isResult ? (
          <button
            onClick={() => handleAdd(clientId, profile.name)}
            className={`px-4 py-2 rounded-full text-[10px] font-extrabold uppercase tracking-widest border transition-all active:scale-95 ${
              isWhitelist
                ? 'bg-primary text-on-primary border-primary shadow-lg shadow-primary/20'
                : 'bg-error text-white border-error shadow-lg shadow-error/20'
            }`}
          >
            加入
          </button>
        ) : (
          <button
            onClick={() => handleRemove(clientId)}
            disabled={removingId === clientId}
            className="w-10 h-10 rounded-full bg-surface-container flex items-center justify-center text-error/70 hover:text-error hover:bg-error/10 transition-all"
            title="移除"
          >
            <span className="material-symbols-outlined text-lg">
              {removingId === clientId ? 'progress_activity' : 'remove_circle'}
            </span>
          </button>
        )}
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-surface text-on-surface pb-32">
      {/* Header */}
      <nav className="sticky top-0 z-50 bg-surface/80 backdrop-blur-md px-4 py-4 flex items-center justify-between border-b border-outline-variant/5">
        <button onClick={() => navigate(-1)} className="p-2 -ml-2 text-on-surface-variant hover:text-primary transition-colors">
          <span className="material-symbols-outlined text-2xl">arrow_back</span>
        </button>
        <h1 className="text-sm font-extrabold font-headline uppercase tracking-tighter">客群門禁管理</h1>
        <div className="w-10" />
      </nav>

      <motion.main initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="px-5 pt-6 space-y-6 max-w-xl mx-auto">

        {/* Tab Selector */}
        <div className="flex bg-surface-container-low rounded-full p-1">
          <button
            onClick={() => { setActiveTab('whitelist'); setSearchQuery(''); setSearchResults([]) }}
            className={`flex-1 py-2.5 text-xs font-extrabold uppercase tracking-widest rounded-full transition-all ${
              activeTab === 'whitelist'
                ? 'bg-primary text-on-primary shadow-lg shadow-primary/20'
                : 'text-on-surface-variant'
            }`}
          >
            白名單
          </button>
          <button
            onClick={() => { setActiveTab('blacklist'); setSearchQuery(''); setSearchResults([]) }}
            className={`flex-1 py-2.5 text-xs font-extrabold uppercase tracking-widest rounded-full transition-all ${
              activeTab === 'blacklist'
                ? 'bg-error text-white shadow-lg shadow-error/20'
                : 'text-on-surface-variant'
            }`}
          >
            黑名單
          </button>
        </div>

        {/* Description */}
        <section className={`p-5 rounded-[28px] border text-xs font-medium leading-relaxed opacity-70 ${
          isWhitelist ? 'bg-primary/5 border-primary/10' : 'bg-error/5 border-error/10'
        }`}>
          {isWhitelist
            ? '白名單中的客戶可以在您設定的時段內優先預約，並可跳過一般問卷流程（可個別設定）。'
            : '黑名單中的客戶將無法看到或預約您的服務方案。'}
        </section>

        {/* Search & Add */}
        <section className="space-y-3">
          <div className="flex gap-2">
            <div className="relative flex-1">
              <span className="material-symbols-outlined absolute left-4 top-1/2 -translate-y-1/2 text-on-surface-variant/40">search</span>
              <input
                type="text"
                placeholder="輸入客戶姓名、Email 或電話..."
                value={searchQuery}
                onChange={e => setSearchQuery(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && handleSearch()}
                className="w-full pl-12 pr-4 py-3.5 bg-surface-container-low border border-outline-variant/10 rounded-full text-sm font-bold outline-none focus:border-primary transition-colors"
              />
            </div>
            <button
              onClick={handleSearch}
              disabled={isSearching || !searchQuery.trim()}
              className={`px-5 py-3.5 rounded-full text-xs font-extrabold uppercase tracking-widest transition-all active:scale-95 disabled:opacity-40 ${
                isWhitelist ? 'bg-primary text-on-primary' : 'bg-error text-white'
              }`}
            >
              {isSearching ? '...' : '搜尋'}
            </button>
          </div>

          {/* Search Results */}
          <AnimatePresence>
            {searchResults.length > 0 && (
              <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} className="space-y-2">
                <p className="text-[10px] font-bold tracking-[0.2em] text-on-surface-variant/40 uppercase px-1">搜尋結果</p>
                {searchResults.map(r => <ClientCard key={r.id} item={r} isResult />)}
              </motion.div>
            )}
          </AnimatePresence>
        </section>

        {/* Current List */}
        <section className="space-y-3">
          <p className="text-[10px] font-bold tracking-[0.2em] text-on-surface-variant/40 uppercase px-1">
            {isWhitelist ? '白名單' : '黑名單'} ({currentList.length})
          </p>
          {isLoading ? (
            <div className="p-12 text-center opacity-30 italic text-sm">載入中...</div>
          ) : currentList.length === 0 ? (
            <div className="p-12 text-center border-2 border-dashed border-outline-variant/10 rounded-[32px] opacity-20 italic text-sm">
              {isWhitelist ? '尚未加入任何白名單客戶。' : '尚未設定任何黑名單。'}
            </div>
          ) : (
            <div className="space-y-2">
              {currentList.map(item => <ClientCard key={item.id || item.clientProfile?.id} item={item} />)}
            </div>
          )}
        </section>
      </motion.main>
    </div>
  )
}

export default ClientGate
