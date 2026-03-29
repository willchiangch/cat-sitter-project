import React, { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { motion, AnimatePresence } from 'framer-motion'
import { sitterService } from '../../services/api'

const ServicePackages = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [packages, setPackages] = useState([])
  const [isLoading, setIsLoading] = useState(true)
  const [isEditing, setIsEditing] = useState(false)
  const [currentPkg, setCurrentPkg] = useState(null)

  useEffect(() => {
    fetchPackages()
  }, [])

  const fetchPackages = async () => {
    try {
      setIsLoading(true)
      const data = await sitterService.list()
      setPackages(data)
    } catch (e) {
      console.error('Failed to fetch packages:', e)
    } finally {
      setIsLoading(false)
    }
  }

  const handleToggleActive = async (pkg) => {
    try {
      const updated = await sitterService.update(pkg.id, {
        ...pkg,
        isActive: !pkg.isActive
      })
      setPackages(prev => prev.map(p => p.id === pkg.id ? updated : p))
    } catch (e) {
      console.error('Toggle active failed:', e)
    }
  }

  const PackageCard = ({ pkg }) => (
    <motion.div 
      layout
      className={`p-6 rounded-[40px] border transition-all ${
        pkg.isActive ? 'bg-surface-container-low border-outline-variant/10' : 'bg-surface/40 border-outline-variant/5 grayscale opacity-60'
      }`}
    >
      <div className="flex justify-between items-start mb-6">
        <div className="space-y-1">
          <h4 className="text-lg font-extrabold tracking-tight">{pkg.name}</h4>
          <div className="flex items-center gap-2">
            <span className="px-2 py-0.5 bg-primary/5 text-primary text-[10px] font-bold rounded-lg border border-primary/10 uppercase tracking-widest">
              {pkg.durationMinutes} MIN
            </span>
          </div>
        </div>
        <div className="text-right">
          <p className="text-2xl font-black font-headline tracking-tighter text-primary">${pkg.basePrice}</p>
          <p className="text-[10px] font-bold opacity-30 uppercase tracking-widest">Base Rate</p>
        </div>
      </div>

      <div className="flex items-center justify-between pt-4 border-t border-outline-variant/5">
        <div className="flex items-center gap-4">
           <button 
             onClick={() => { setCurrentPkg(pkg); setIsEditing(true); }}
             className="text-[10px] font-bold text-on-surface-variant hover:text-primary transition-colors flex items-center gap-1.5"
           >
             <span className="material-symbols-outlined text-sm">edit</span>
             EDIT
           </button>
        </div>
        <button 
          onClick={() => handleToggleActive(pkg)}
          className={`px-4 py-1.5 rounded-full text-[10px] font-black tracking-widest transition-all ${
            pkg.isActive ? 'bg-primary/10 text-primary border border-primary/20' : 'bg-on-surface-variant/5 text-on-surface-variant border border-on-surface-variant/10'
          }`}
        >
          {pkg.isActive ? 'ACTIVE' : 'INACTIVE'}
        </button>
      </div>
    </motion.div>
  )

  return (
    <div className="min-h-screen bg-surface text-on-surface pb-32">
      {/* Header */}
      <nav className="sticky top-0 z-50 bg-surface/80 backdrop-blur-md px-4 py-4 flex items-center justify-between border-b border-outline-variant/5">
        <button onClick={() => navigate(-1)} className="p-2 -ml-2 text-on-surface-variant hover:text-primary transition-colors">
          <span className="material-symbols-outlined text-2xl">arrow_back</span>
        </button>
        <h1 className="text-sm font-extrabold font-headline uppercase tracking-tighter">{t('sitter.service_management', 'Service Packages')}</h1>
        <button 
          onClick={() => { setCurrentPkg(null); setIsEditing(true); }}
          className="w-10 h-10 bg-primary text-on-primary rounded-full flex items-center justify-center shadow-lg active:scale-95 transition-all"
        >
          <span className="material-symbols-outlined text-xl">add</span>
        </button>
      </nav>

      <motion.main 
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        className="px-5 pt-8 space-y-10 max-w-xl mx-auto"
      >
        <section className="space-y-4">
          <div className="space-y-4">
            {packages.map(pkg => (
              <PackageCard key={pkg.id} pkg={pkg} />
            ))}
            {packages.length === 0 && !isLoading && (
              <div className="p-20 text-center border-2 border-dashed border-outline-variant/10 rounded-[40px] opacity-20 italic">
                {t('sitter.no_packages', 'No service packages defined yet.')}
              </div>
            )}
          </div>
        </section>

        {/* Edit Modal (Simulated for Now) */}
        <AnimatePresence>
          {isEditing && (
            <div className="fixed inset-0 z-[100] flex items-end sm:items-center justify-center p-4">
              <motion.div 
                initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
                onClick={() => setIsEditing(false)}
                className="absolute inset-0 bg-black/60 backdrop-blur-sm"
              />
              <motion.div 
                initial={{ y: 100, opacity: 0 }} animate={{ y: 0, opacity: 1 }} exit={{ y: 100, opacity: 0 }}
                className="relative w-full max-w-lg bg-surface rounded-[40px] p-8 shadow-2xl space-y-8"
              >
                <h3 className="text-2xl font-black font-headline tracking-tighter">
                  {currentPkg ? 'Edit Package' : 'New Package'}
                </h3>
                {/* Form fields would go here - for brevity, focusing on the List/Toggle realization */}
                <button 
                  onClick={() => setIsEditing(false)}
                  className="w-full py-4 bg-primary text-on-primary rounded-full font-bold shadow-xl shadow-primary/20"
                >
                  SAVE PACKAGE
                </button>
              </motion.div>
            </div>
          )}
        </AnimatePresence>
      </motion.main>
    </div>
  )
}

export default ServicePackages
