import React, { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { motion } from 'framer-motion'
import { trustCircleService } from '../../services/api'
import { useAuthStore } from '../../store/authStore'

const SPECIES_MAP = {
  'CAT': '貓咪',
  'DOG': '狗狗',
  'BIRD': '鳥類',
  'HAMSTER': '倉鼠',
  'RABBIT': '兔子',
  'OTHER': '其他'
}

const ServiceCard = ({ service, index, isSelf, handleBook }) => {
  const [isExpanded, setIsExpanded] = useState(false)
  const hasDescription = !!service.description

  return (
    <motion.div
      initial={{ y: 20, opacity: 0 }}
      animate={{ y: 0, opacity: 1 }}
      transition={{ delay: 0.1 + index * 0.05 }}
      className="bg-surface-container-low rounded-[32px] p-6 border border-outline-variant/10 space-y-4"
    >
      <div className="space-y-3">
        <div className="flex items-start justify-between gap-4">
          <h3 className="text-lg font-extrabold font-headline tracking-tight leading-snug break-words">
            {service.name}
          </h3>
          <p className="text-2xl font-extrabold font-headline tracking-tighter text-primary shrink-0">
            ${service.basePrice?.toLocaleString()}
          </p>
        </div>

        {/* Pet Types Tags (Now under Title) */}
        {service.supportedPetTypes?.length > 0 && (
          <div className="flex gap-1 flex-wrap">
            {service.supportedPetTypes.map(type => (
              <span key={type} className="text-[10px] font-bold px-2 py-0.5 bg-surface-container-high rounded-full opacity-60">
                {SPECIES_MAP[type] || type}
              </span>
            ))}
          </div>
        )}

        {/* Line 2: Duration (Right aligned) */}
        {service.durationMinutes && (
          <div className="text-right">
            <p className="text-[10px] font-bold opacity-40 uppercase tracking-widest">
              {service.durationMinutes} 分鐘
            </p>
          </div>
        )}

        {/* Line 3: Description (Left aligned, with Show More) */}
        {hasDescription && (
          <div className="space-y-2">
            <div className={`text-sm font-medium opacity-60 leading-relaxed whitespace-pre-wrap break-words text-left ${!isExpanded ? 'line-clamp-3' : ''}`}>
              {service.description}
            </div>
            {(service.description.split('\n').length > 3 || service.description.length > 80) && (
              <button
                onClick={() => setIsExpanded(!isExpanded)}
                className="text-[10px] font-black text-primary uppercase tracking-widest hover:opacity-70 transition-opacity flex items-center gap-1"
              >
                {isExpanded ? '收回全文' : '顯示更多'}
                <span className="material-symbols-outlined text-xs">
                  {isExpanded ? 'expand_less' : 'expand_more'}
                </span>
              </button>
            )}
          </div>
        )}
      </div>

      <button
        onClick={() => handleBook(service.serviceId)}
        disabled={isSelf}
        className={`w-full py-4 rounded-full text-xs font-extrabold uppercase tracking-widest transition-all ${
          isSelf 
            ? 'bg-outline-variant/20 text-on-surface/40 cursor-not-allowed shadow-none' 
            : 'bg-on-surface text-surface shadow-xl shadow-on-surface/10 hover:scale-[1.01] active:scale-95'
        }`}
      >
        {isSelf ? '預覽模式 (無法預約)' : '立即預約'}
      </button>
    </motion.div>
  )
}

const SitterPublicPage = () => {
  const { slug } = useParams()
  const navigate = useNavigate()
  const { isAuthenticated, user } = useAuthStore()
  const [data, setData] = useState(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState(null)
  const [isBioExpanded, setIsBioExpanded] = useState(false)

  // Determine if viewing self
  const currentProfileId = user?.profiles?.find(p => p.role === user.lastActiveRole)?.profileId
  const isSelf = data?.sitterProfile?.profileId === currentProfileId

  useEffect(() => {
    window.scrollTo({ top: 0 })
    trustCircleService.searchBySlug(slug)
      .then(setData)
      .catch(() => setError('找不到此保母頁面'))
      .finally(() => setIsLoading(false))
  }, [slug])

  const handleBook = (serviceId) => {
    if (isSelf) return // Safety check
    if (!isAuthenticated) {
      navigate(`/login?redirect=/s/${slug}`)
      return
    }
    // Navigate to booking flow with sitter profile id + selected service
    navigate(`/booking/sitter/${data.sitterProfile.profileId}`, {
      state: { preselectedServiceId: serviceId, slug }
    })
  }

  if (isLoading) {
    return (
      <div className="min-h-screen bg-surface flex items-center justify-center">
        <p className="text-sm font-bold opacity-30 uppercase tracking-widest">Loading...</p>
      </div>
    )
  }

  if (error || !data) {
    return (
      <div className="min-h-screen bg-surface flex flex-col items-center justify-center gap-4">
        <span className="material-symbols-outlined text-6xl opacity-20">search_off</span>
        <p className="text-sm font-bold opacity-30 uppercase tracking-widest">{error || '頁面不存在'}</p>
      </div>
    )
  }

  const { sitterProfile, services } = data

  return (
    <div className="min-h-screen bg-surface text-on-surface">
      {/* Navigation */}
      <div className="fixed top-6 left-6 z-[50]">
        <button
          onClick={() => navigate(-1)}
          className="w-10 h-10 rounded-full bg-surface/80 backdrop-blur border border-outline-variant/10 shadow-lg flex items-center justify-center text-on-surface/60 hover:text-on-surface hover:scale-105 active:scale-95 transition-all"
        >
          <span className="material-symbols-outlined !text-[20px]">arrow_back</span>
        </button>
      </div>

      {/* Hero */}
      <div className="relative bg-surface-container-low overflow-hidden">
        <div className="absolute inset-0 opacity-5">
          <div className="absolute top-0 right-0 w-96 h-96 rounded-full bg-primary blur-3xl" />
        </div>
        <div className="relative max-w-2xl mx-auto px-6 pt-16 pb-12 flex flex-col items-center text-center gap-6">
          <motion.div
            initial={{ scale: 0.8, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            className="w-28 h-28 rounded-[40px] overflow-hidden shadow-2xl ring-4 ring-surface bg-surface-container-low flex items-center justify-center"
          >
            {sitterProfile.avatarUrl ? (
              <img
                src={sitterProfile.avatarUrl}
                className="w-full h-full object-cover"
                onError={e => { e.target.style.display = 'none'; e.target.nextSibling.style.display = 'flex' }}
              />
            ) : null}
            <span
              className="material-symbols-outlined text-5xl text-on-surface/20"
              style={{ display: sitterProfile.avatarUrl ? 'none' : 'flex' }}
            >person</span>
          </motion.div>

          <motion.div
            initial={{ y: 12, opacity: 0 }}
            animate={{ y: 0, opacity: 1 }}
            transition={{ delay: 0.1 }}
            className="space-y-2"
          >
            <h1 className="text-4xl font-extrabold font-headline tracking-tighter">{sitterProfile.name}</h1>
            {sitterProfile.serviceAreas?.length > 0 && (
              <p className="text-xs font-bold opacity-30 uppercase tracking-widest">
                {sitterProfile.serviceAreas.join(' · ')}
              </p>
            )}
          </motion.div>

          {/* Labels */}
          {sitterProfile.professionalLabels?.length > 0 && (
            <motion.div
              initial={{ y: 12, opacity: 0 }}
              animate={{ y: 0, opacity: 1 }}
              transition={{ delay: 0.15 }}
              className="flex flex-wrap justify-center gap-2"
            >
              {sitterProfile.professionalLabels.map((label, i) => (
                <span
                  key={i}
                  className="px-3 py-1 bg-primary/10 text-primary text-[11px] font-bold rounded-full"
                >
                  {label}
                </span>
              ))}
            </motion.div>
          )}

          {/* Bio */}
          {sitterProfile.bioSummary && (
            <motion.div
              initial={{ y: 12, opacity: 0 }}
              animate={{ y: 0, opacity: 1 }}
              transition={{ delay: 0.2 }}
              className="w-full max-w-md space-y-2"
            >
              <div
                className={`text-sm font-medium opacity-60 leading-relaxed whitespace-pre-wrap break-words text-left ${
                  !isBioExpanded ? 'line-clamp-3' : ''
                }`}
              >
                {sitterProfile.bioSummary}
              </div>
              {(sitterProfile.bioSummary.split('\n').length > 3 || sitterProfile.bioSummary.length > 100) && (
                <button
                  onClick={() => setIsBioExpanded(!isBioExpanded)}
                  className="text-xs font-black text-primary uppercase tracking-widest hover:opacity-70 transition-opacity flex items-center gap-1 mt-1"
                >
                  {isBioExpanded ? '收回全文' : '顯示更多'}
                  <span className="material-symbols-outlined text-sm">
                    {isBioExpanded ? 'expand_less' : 'expand_more'}
                  </span>
                </button>
              )}
            </motion.div>
          )}
        </div>
      </div>

      {/* Services */}
      <div className="max-w-2xl mx-auto px-6 py-12 space-y-6">
        <div className="space-y-1">
          <h2 className="text-2xl font-extrabold font-headline tracking-tighter">服務方案</h2>
          <p className="text-[10px] font-bold opacity-30 uppercase tracking-widest">Choose a plan to get started</p>
        </div>

        {services.length === 0 ? (
          <div className="py-16 text-center opacity-30">
            <span className="material-symbols-outlined text-5xl">inbox</span>
            <p className="text-xs font-bold uppercase tracking-widest mt-2">暫無開放方案</p>
          </div>
        ) : (
          <div className="space-y-4">
            {services.map((service, i) => (
              <ServiceCard 
                key={service.serviceId} 
                service={service} 
                index={i} 
                isSelf={isSelf} 
                handleBook={handleBook} 
              />
            ))}
          </div>
        )}
      </div>

      {/* Footer */}
      <div className="max-w-2xl mx-auto px-6 pb-16 text-center">
        <p className="text-[10px] font-bold opacity-20 uppercase tracking-widest">Powered by WhiskerWatch</p>
      </div>
    </div>
  )
}

export default SitterPublicPage
