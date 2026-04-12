import React, { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { motion } from 'framer-motion'
import { trustCircleService } from '../../services/api'
import { useAuthStore } from '../../store/authStore'

const SitterPublicPage = () => {
  const { slug } = useParams()
  const navigate = useNavigate()
  const { isAuthenticated } = useAuthStore()
  const [data, setData] = useState(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState(null)

  useEffect(() => {
    window.scrollTo({ top: 0 })
    trustCircleService.searchBySlug(slug)
      .then(setData)
      .catch(() => setError('找不到此保母頁面'))
      .finally(() => setIsLoading(false))
  }, [slug])

  const handleBook = (serviceId) => {
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
            <motion.p
              initial={{ y: 12, opacity: 0 }}
              animate={{ y: 0, opacity: 1 }}
              transition={{ delay: 0.2 }}
              className="text-sm font-medium opacity-60 leading-relaxed max-w-md"
            >
              {sitterProfile.bioSummary}
            </motion.p>
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
              <motion.div
                key={service.serviceId}
                initial={{ y: 20, opacity: 0 }}
                animate={{ y: 0, opacity: 1 }}
                transition={{ delay: 0.1 + i * 0.05 }}
                className="bg-surface-container-low rounded-[32px] p-6 border border-outline-variant/10 space-y-4"
              >
                <div className="flex items-start justify-between gap-4">
                  <div className="space-y-1">
                    <h3 className="text-lg font-extrabold font-headline tracking-tight">{service.name}</h3>
                    {service.durationMinutes && (
                      <p className="text-[10px] font-bold opacity-40 uppercase tracking-widest">
                        {service.durationMinutes} 分鐘
                      </p>
                    )}
                    {service.supportedPetTypes?.length > 0 && (
                      <div className="flex gap-1 flex-wrap pt-1">
                        {service.supportedPetTypes.map(type => (
                          <span key={type} className="text-[10px] font-bold px-2 py-0.5 bg-surface-container-high rounded-full opacity-60">
                            {SPECIES_MAP[type] || type}
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                  <div className="text-right shrink-0">
                    <p className="text-2xl font-extrabold font-headline tracking-tighter text-primary">
                      ${service.basePrice?.toLocaleString()}
                    </p>
                    <p className="text-[10px] font-bold opacity-30 uppercase">/ 次</p>
                  </div>
                </div>

                <button
                  onClick={() => handleBook(service.serviceId)}
                  className="w-full py-4 bg-on-surface text-surface rounded-full text-xs font-extrabold uppercase tracking-widest shadow-xl shadow-on-surface/10 hover:scale-[1.01] active:scale-95 transition-all"
                >
                  立即預約
                </button>
              </motion.div>
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
