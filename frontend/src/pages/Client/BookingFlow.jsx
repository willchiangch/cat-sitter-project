import React, { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { motion, AnimatePresence } from 'framer-motion'
import { petService, orderService } from '../../services/api'

const BookingFlow = () => {
  const { sitterId } = useParams()
  const navigate = useNavigate()
  const { t } = useTranslation()
  const [step, setStep] = useState(1)
  
  const [myPets, setMyPets] = useState([])
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    const fetchMyPets = async () => {
      try {
        const data = await petService.list()
        setMyPets(data)
      } catch (error) {
        console.error('Failed to fetch pets:', error)
      } finally {
        setIsLoading(false)
      }
    }
    fetchMyPets()
  }, [])

  // Mock Data: Sitter's Preference for this Client
  const isRegularGuest = true // In real app, fetch from trust-circle API
  const skipQuestionnaire = true

  // State Management
  const [selectedPets, setSelectedPets] = useState([])
  const [service, setService] = useState('STANDARD')
  const [date, setDate] = useState('')
  const [notes, setNotes] = useState('')

  const totalAmount = (service === 'STANDARD' ? 500 : 800) + (selectedPets.length > 1 ? (selectedPets.length - 1) * 200 : 0)

  const handleNext = () => {
    if (step === 2 && skipQuestionnaire) {
      setStep(4) // Directly skip to summary for regular guests
    } else {
      setStep(prev => prev + 1)
    }
  }

  const handleBack = () => {
    if (step === 4 && skipQuestionnaire) {
      setStep(2)
    } else {
      setStep(prev => prev - 1)
    }
  }

  const handleFinalConfirm = async () => {
    try {
      setIsLoading(true)
      const bookingData = {
        sitterProfileId: sitterId,
        serviceId: service === 'STANDARD' ? '68511200-0045-6120-0000-000000000001' : '68511200-0045-6120-0000-000000000002',
        visits: [
          { startTime: `${date}T10:00:00Z`, endTime: `${date}T11:00:00Z` }
        ],
        answers: skipQuestionnaire ? [] : [
           { questionId: 'q-generic-1', answer: notes || '無特別備註' }
        ]
      }
      await orderService.createBooking(bookingData)
      navigate('/client/orders')
    } catch (error) {
      alert('預約提交失敗，請檢查資料完整性。')
    } finally {
      setIsLoading(false)
    }
  }

  const StepIndicator = () => (
    <div className="flex gap-1.5 px-6 pt-10 pb-6 overflow-hidden">
      {[1, 2, 3, 4].map((i) => (
        <div 
          key={i} 
          className={`h-1 flex-1 rounded-full transition-all duration-500 ${
            i <= step ? 'bg-primary' : 'bg-surface-container-highest'
          } ${i === step ? 'scale-y-150' : ''}`} 
        />
      ))}
    </div>
  )

  return (
    <div className="min-h-screen bg-surface text-on-surface flex flex-col overflow-hidden">
      {/* Immersive Header */}
      <nav className="px-4 py-4 flex items-center justify-between">
        <button onClick={() => navigate(-1)} className="p-2 text-on-surface-variant">
          <span className="material-symbols-outlined text-2xl">close</span>
        </button>
        <span className="text-[10px] font-bold tracking-[0.4em] uppercase opacity-40">Booking Concierge</span>
        <div className="w-10"></div>
      </nav>

      <StepIndicator />

      <div className="flex-1 relative">
        <AnimatePresence mode="wait">
          {step === 1 && (
            <motion.section 
              key="step1"
              initial={{ opacity: 0, x: 20 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -20 }}
              className="px-8 space-y-8"
            >
              <div className="space-y-2">
                <h2 className="text-4xl font-extrabold font-headline tracking-tighter">{t('booking.step1_title')}</h2>
                <p className="text-sm font-medium opacity-40">{t('booking.step1_subtitle')}</p>
              </div>

              <div className="grid grid-cols-1 gap-4">
                {myPets.map(pet => (
                  <button 
                    key={pet.id}
                    onClick={() => {
                        setSelectedPets(prev => prev.includes(pet.id) ? prev.filter(id => id !== pet.id) : [...prev, pet.id])
                    }}
                    className={`p-5 rounded-[32px] border transition-all flex items-center gap-4 ${
                        selectedPets.includes(pet.id) ? 'bg-primary/5 border-primary shadow-lg shadow-primary/5 scale-[1.02]' : 'bg-surface-container-low border-outline-variant/10'
                    }`}
                  >
                    <div className="w-16 h-16 rounded-[24px] overflow-hidden">
                      <img src={pet.img} className="w-full h-full object-cover" />
                    </div>
                    <div className="text-left">
                      <h3 className="text-lg font-extrabold">{pet.name}</h3>
                      <p className="text-xs font-bold opacity-40">{pet.breed}</p>
                    </div>
                    {selectedPets.includes(pet.id) && (
                      <span className="material-symbols-outlined ml-auto text-primary">check_circle</span>
                    )}
                  </button>
                ))}
              </div>
            </motion.section>
          )}

          {step === 2 && (
            <motion.section 
              key="step2"
              initial={{ opacity: 0, x: 20 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -20 }}
              className="px-8 space-y-8"
            >
              <div className="space-y-2">
                <h2 className="text-4xl font-extrabold font-headline tracking-tighter">{t('booking.step2_title')}</h2>
                <p className="text-sm font-medium opacity-40">{t('booking.step2_subtitle')}</p>
              </div>

              <div className="space-y-4">
                <div className="grid grid-cols-2 gap-3">
                  {['STANDARD', 'ELITE'].map(s => (
                    <button 
                      key={s}
                      onClick={() => setService(s)}
                      className={`p-6 rounded-[32px] border text-left transition-all ${
                        service === s ? 'bg-on-surface text-surface border-on-surface' : 'bg-surface-container-low border-outline-variant/10'
                      }`}
                    >
                      <h4 className="text-xs font-bold uppercase tracking-widest opacity-40 mb-2">{s}</h4>
                      <p className="text-xl font-extrabold font-headline">${s === 'STANDARD' ? '500' : '800'}</p>
                    </button>
                  ))}
                </div>
                <input 
                  type="date" 
                  className="w-full p-6 bg-surface-container-low border border-outline-variant/10 rounded-[32px] text-base font-bold outline-none focus:border-primary transition-colors"
                  value={date}
                  onChange={(e) => setDate(e.target.value)}
                />
              </div>
            </motion.section>
          )}

          {step === 3 && (
            <motion.section 
              key="step3"
              initial={{ opacity: 0, x: 20 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -20 }}
              className="px-8 space-y-8"
            >
              <div className="space-y-2">
                <h2 className="text-4xl font-extrabold font-headline tracking-tighter">{t('booking.step3_title')}</h2>
                <p className="text-sm font-medium opacity-40">{t('booking.step3_subtitle')}</p>
              </div>
              <textarea 
                className="w-full h-48 p-6 bg-surface-container-low border border-outline-variant/10 rounded-[32px] text-base font-medium outline-none focus:border-primary transition-colors resize-none"
                placeholder="Ex: Feeding amounts, medication times, or favorite toys..."
                value={notes}
                onChange={(e) => setNotes(e.target.value)}
              />
            </motion.section>
          )}

          {step === 4 && (
            <motion.section 
              key="step4"
              initial={{ opacity: 0, scale: 0.95 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.95 }}
              className="px-8 space-y-8"
            >
              <div className="space-y-2">
                <h2 className="text-4xl font-extrabold font-headline tracking-tighter">{t('booking.step4_title')}</h2>
                <p className="text-sm font-medium opacity-40">{t('booking.step4_subtitle')}</p>
              </div>

              {skipQuestionnaire && (
                <motion.div 
                  initial={{ opacity: 0, y: 10 }}
                  animate={{ opacity: 1, y: 0 }}
                  className="bg-primary/10 border border-primary/20 p-5 rounded-[24px] flex items-center gap-3"
                >
                  <span className="material-symbols-outlined text-primary text-xl">verified</span>
                  <p className="text-[10px] font-bold text-primary uppercase tracking-widest leading-relaxed">
                    {t('booking.regular_guest_skip', 'Regular Guest Status: Care form optional.')}
                  </p>
                </motion.div>
              )}

              <div className="bg-surface-container-highest p-8 rounded-[40px] space-y-6">
                 <div className="flex justify-between items-baseline">
                    <span className="text-xs font-bold opacity-40 uppercase tracking-widest">Pricing Summary</span>
                    <span className="text-3xl font-extrabold font-headline">${totalAmount}</span>
                 </div>
                 <div className="h-px bg-on-surface/5" />
                 <div className="space-y-2">
                    <p className="text-xs font-bold">{selectedPets.length} Cats • {service} Plan</p>
                    <p className="text-xs opacity-40">{date || 'No date selected'}</p>
                 </div>
              </div>
            </motion.section>
          )}
        </AnimatePresence>
      </div>

      {/* Persistent Footer Actions */}
      <footer className="p-8 bg-surface/80 backdrop-blur-xl border-t border-outline-variant/5">
        <div className="flex gap-3 max-w-xl mx-auto w-full">
          {step > 1 && (
            <button 
              onClick={handleBack}
              className="w-16 h-16 flex items-center justify-center bg-surface-container-high rounded-full hover:bg-surface-container-highest transition-all"
            >
              <span className="material-symbols-outlined">arrow_back</span>
            </button>
          )}
          <button 
            disabled={(step === 1 && selectedPets.length === 0) || isLoading}
            onClick={step === 4 ? handleFinalConfirm : handleNext}
            className="flex-1 py-5 bg-on-surface text-surface rounded-full text-base font-extrabold hover:scale-[1.02] active:scale-95 transition-all shadow-xl disabled:opacity-20 disabled:scale-100"
          >
            {isLoading ? 'Processing...' : step === 4 ? 'Confirm Booking' : 'Continue'}
          </button>
        </div>
      </footer>
    </div>
  )
}

export default BookingFlow
