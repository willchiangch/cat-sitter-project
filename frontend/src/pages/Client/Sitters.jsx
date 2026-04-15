import React, { useState } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { trustCircleService } from '../../services/api'

const Sitters = () => {
  const navigate = useNavigate()
  const [sitters, setSitters] = useState(() => {
    const saved = localStorage.getItem('whiskerwatch_my_sitters')
    return saved ? JSON.parse(saved) : []
  })
  const [searchCode, setSearchCode] = useState('')
  const [isSearching, setIsSearching] = useState(false)
  const [error, setError] = useState('')

  // Persist sitters list to localStorage
  React.useEffect(() => {
    localStorage.setItem('whiskerwatch_my_sitters', JSON.stringify(sitters))
  }, [sitters])

  const handleSearch = async (e) => {
    e.preventDefault()
    if (!searchCode.trim()) return
    
    setIsSearching(true)
    setError('')
    
    try {
      const data = await trustCircleService.searchBySlug(searchCode.trim())
      const sitterInfo = data.sitterProfile
      
      // Add to list if not already present
      setSitters(prev => {
        if (prev.find(s => s.id === sitterInfo.profileId)) return prev
        return [{
          id: sitterInfo.profileId,
          name: sitterInfo.name,
          code: sitterInfo.slug, // Now correctly populated from backend
          avatarUrl: sitterInfo.avatarUrl // Consistent with DTO
        }, ...prev]
      })
      
      setSearchCode('')
    } catch (err) {
      console.error('Search failed:', err)
      setError('找不到該保母代碼，請確認後再試')
    } finally {
      setIsSearching(false)
    }
  }

  const handleRemove = (id, e) => {
    e.stopPropagation()
    if (window.confirm('確定要移除這位保母嗎？')) {
      setSitters(prev => prev.filter(s => s.id !== id))
    }
  }

  return (
    <div className="min-h-screen bg-surface text-on-surface pb-32">
      <header className="px-6 pt-12 pb-8 space-y-1">
        <h1 className="text-4xl font-extrabold font-headline tracking-tighter">我的保母</h1>
        <p className="text-xs font-bold opacity-30 uppercase tracking-[0.2em]">My Sitters</p>
      </header>

      <main className="px-6 space-y-6">
        {/* Search Bar */}
        <div className="space-y-2">
          <form onSubmit={handleSearch} className="flex gap-3">
            <input
              type="text"
              value={searchCode}
              onChange={e => setSearchCode(e.target.value)}
              placeholder="輸入保母代碼 (如: 35c6a634)"
              className="flex-1 bg-surface-container-low rounded-2xl px-4 py-3 text-sm font-medium outline-none border border-outline-variant/20 focus:border-primary/40 transition-colors"
            />
            <button
              type="submit"
              disabled={isSearching || !searchCode.trim()}
              className="bg-primary text-on-primary rounded-2xl px-5 py-3 text-sm font-bold disabled:opacity-40 transition-opacity active:scale-95"
            >
              {isSearching ? (
                <span className="material-symbols-outlined text-xl animate-spin">progress_activity</span>
              ) : (
                <span className="material-symbols-outlined text-xl">search</span>
              )}
            </button>
          </form>
          {error && <p className="text-[10px] font-bold text-error px-2">{error}</p>}
        </div>

        {/* Sitter List */}
        <AnimatePresence mode="popLayout">
          {sitters.length === 0 ? (
            <div className="py-20 text-center space-y-4 opacity-40">
              <span className="material-symbols-outlined text-6xl">person_search</span>
              <p className="text-sm font-bold uppercase tracking-widest">還沒有加入任何保母</p>
              <p className="text-xs opacity-60">輸入保母代碼即可加入</p>
            </div>
          ) : (
            <div className="grid grid-cols-1 gap-4">
              {sitters.map(sitter => (
                <motion.div
                  key={sitter.id}
                  layout
                  initial={{ opacity: 0, scale: 0.9 }}
                  animate={{ opacity: 1, scale: 1 }}
                  exit={{ opacity: 0, x: -20 }}
                  onClick={() => navigate(`/s/${sitter.code}`)}
                  className="bg-surface-container-low rounded-[32px] p-5 border border-outline-variant/10 flex items-center gap-5 cursor-pointer hover:bg-surface-container transition-colors active:scale-[0.98]"
                >
                  <div className="w-16 h-16 rounded-2xl overflow-hidden shadow-lg ring-2 ring-surface flex-shrink-0">
                    <img
                      src={sitter.avatarUrl || 'https://images.unsplash.com/photo-1514888286974-6c03e2ca1dba?auto=format&fit=crop&q=80&w=200'}
                      className="w-full h-full object-cover"
                      alt={sitter.name}
                    />
                  </div>

                  <div className="flex-1 space-y-0.5">
                    <h3 className="text-lg font-extrabold font-headline tracking-tighter">{sitter.name}</h3>
                    <p className="text-[11px] font-bold opacity-40 uppercase tracking-widest">{sitter.code}</p>
                  </div>

                  <button
                    onClick={e => handleRemove(sitter.id, e)}
                    className="p-2 text-on-surface/30 hover:text-red-500 transition-colors active:scale-90"
                  >
                    <span className="material-symbols-outlined text-xl">person_remove</span>
                  </button>
                </motion.div>
              ))}
            </div>
          )}
        </AnimatePresence>
      </main>
    </div>
  )
}

export default Sitters
