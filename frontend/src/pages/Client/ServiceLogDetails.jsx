import React, { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { motion, AnimatePresence } from 'framer-motion'
import { visitService } from '../../services/api'

const ServiceLogDetails = () => {
  const { id } = useParams()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [selectedPhoto, setSelectedPhoto] = useState(null)
  const [logData, setLogData] = useState(null)
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    const fetchLog = async () => {
      try {
        setIsLoading(true)
        const data = await visitService.getDetail(id)
        setLogData(data)
      } catch (error) {
        console.error('Failed to fetch visit log:', error)
      } finally {
        setIsLoading(false)
      }
    }
    fetchLog()
  }, [id])

  if (isLoading || !logData) return <div className="p-20 text-center italic opacity-20">Fetching Cat Memories...</div>

  return (
    <div className="min-h-screen bg-surface text-on-surface pb-24">
      {/* Sticky Top Navigation */}
      <nav className="sticky top-0 z-50 bg-surface/80 backdrop-blur-md px-4 py-4 flex items-center justify-between border-b border-outline-variant/10">
        <button onClick={() => navigate(-1)} className="p-2 -ml-2 text-on-surface-variant hover:text-primary transition-colors">
          <span className="material-symbols-outlined text-2xl">arrow_back</span>
        </button>
        <h1 className="text-base font-extrabold font-headline tracking-tighter uppercase">{t('client.service_log')}</h1>
        <div className="w-10"></div> {/* Spacer */}
      </nav>

      <motion.main 
        initial={{ opacity: 0, y: 15 }}
        animate={{ opacity: 1, y: 0 }}
        className="px-5 pt-8 space-y-10 max-w-2xl mx-auto"
      >
        {/* Diary Header */}
        <section className="space-y-4">
          <div className="inline-flex items-center px-3 py-1 bg-primary/10 text-primary rounded-full text-[10px] font-bold tracking-widest uppercase">
             {new Date().toLocaleDateString()}
          </div>
          <h2 className="text-4xl font-extrabold font-headline leading-[0.9] tracking-tighter">
            貓咪的<br />即時日誌。
          </h2>
          <div className="flex items-center gap-3 pt-2">
            <div className="w-10 h-10 rounded-full bg-surface-container flex items-center justify-center border border-outline-variant/10 overflow-hidden text-primary font-bold">WW</div>
            <div>
              <p className="text-xs font-bold leading-none">WhiskerWatch Sitter</p>
              <p className="text-[10px] text-on-surface-variant opacity-60 mt-1 uppercase tracking-wider">Your Dedicated Caregiver</p>
            </div>
          </div>
        </section>

        {/* Checkpoints */}
        <section className="grid grid-cols-2 gap-3">
          {logData.items?.map((cp, idx) => (
            <div key={cp.id || idx} className="p-4 bg-surface-container-low rounded-3xl flex items-center gap-3 border border-outline-variant/5">
              <span className={`material-symbols-outlined text-base ${cp.isCompleted ? 'text-primary fill-1' : 'opacity-20'}`}>
                {cp.isCompleted ? 'check_circle' : 'circle'}
              </span>
              <span className={`text-[11px] font-bold ${cp.isCompleted ? 'opacity-100' : 'opacity-30'}`}>{cp.description || cp.serviceType}</span>
            </div>
          ))}
        </section>

        {/* Narrative Narrative */}
        <section className="relative">
          <span className="absolute -top-6 -left-2 text-primary opacity-10 text-8xl font-serif">“</span>
          <p className="font-body text-base leading-relaxed text-on-surface-variant first-letter:text-5xl first-letter:font-headline first-letter:font-extrabold first-letter:mr-3 first-letter:float-left first-letter:text-primary">
            {logData.sitterNotes || '保母尚未填寫今日照護報告。'}
          </p>
        </section>

        {/* Visual Gallery */}
        <section className="space-y-4">
          <div className="flex items-baseline justify-between">
            <h3 className="text-xs font-extrabold tracking-widest uppercase opacity-40">{t('client.activity')}</h3>
            <span className="text-[10px] font-bold opacity-30">{logData.moments?.length || 0} MOMENTS</span>
          </div>
          <div className="grid grid-cols-2 gap-3">
            {logData.moments?.map((m, idx) => (
              <motion.div 
                key={m.id || idx}
                whileHover={{ scale: 0.98 }}
                onClick={() => setSelectedPhoto(m.mediaUrl)}
                className={`relative rounded-3xl overflow-hidden cursor-zoom-in group ${idx === 0 ? 'col-span-2 aspect-[16/10]' : 'aspect-square'}`}
              >
                <img src={m.mediaUrl} alt={m.caption} title={m.caption} className="w-full h-full object-cover transition-transform duration-700 group-hover:scale-110" />
                <div className="absolute inset-0 bg-gradient-to-t from-black/40 to-transparent opacity-0 group-hover:opacity-100 transition-opacity flex items-end p-4">
                   <p className="text-white text-[10px] font-bold">{m.caption}</p>
                </div>
              </motion.div>
            ))}
          </div>
        </section>
      </motion.main>

      {/* Fullscreen Lightbox */}
      <AnimatePresence>
        {selectedPhoto && (
          <motion.div 
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={() => setSelectedPhoto(null)}
            className="fixed inset-0 z-[100] bg-surface/95 backdrop-blur-xl flex items-center justify-center p-4 cursor-zoom-out"
          >
            <motion.img 
              initial={{ scale: 0.9, y: 20 }}
              animate={{ scale: 1, y: 0 }}
              src={selectedPhoto} 
              className="max-w-full max-h-[85vh] object-contain rounded-2xl shadow-2xl" 
            />
            <button className="absolute top-10 right-10 text-on-surface-variant hover:text-primary p-2">
              <span className="material-symbols-outlined text-4xl">close</span>
            </button>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}

export default ServiceLogDetails
