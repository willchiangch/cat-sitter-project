import React from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { motion } from 'framer-motion'

const CatPassport = () => {
  const { id } = useParams()
  const { t } = useTranslation()
  const navigate = useNavigate()

  // Mock data for the Cat Passport (to be replaced by API)
  const catData = {
    name: 'Miso',
    breed: 'British Shorthair',
    birthDate: '2022-05-12',
    chipId: '985112000045612',
    weight: '4.2 kg',
    imageUrl: 'https://images.unsplash.com/photo-1533738363-b7f9aef128ce?auto=format&fit=crop&q=80&w=800',
    medical: {
      vaccines: ['FVRCP (2025-01)', 'Rabies (2024-06)', 'FeLV (2024-06)'],
      allergies: ['Shrimp', 'Certain Dust Mites'],
      medications: ['None currently']
    },
    habits: {
      diet: 'Royal Canin British Shorthair Adult (Wet + Dry)',
      personality: 'Independent but enjoys morning cuddles. Vocal during meal times.',
      favorites: 'Silvervine sticks, Feather wands'
    },
    emergency: {
      vet: 'Blue Cross Animal Hospital (+886-2-2345-6789)',
      notes: 'Key is under the blue pot in the hallway. Extra food in the pantry second shelf.'
    }
  }

  const InfoTag = ({ icon, label, value }) => (
    <div className="flex flex-col gap-1">
      <div className="flex items-center gap-1.5 opacity-40">
        <span className="material-symbols-outlined text-[14px]">{icon}</span>
        <span className="text-[10px] font-bold tracking-widest uppercase">{label}</span>
      </div>
      <p className="text-sm font-bold text-on-surface">{value}</p>
    </div>
  )

  const VaultSection = ({ title, icon, children }) => (
    <section className="space-y-4">
      <div className="flex items-center gap-2 px-1">
        <div className="w-8 h-8 rounded-xl bg-primary/10 flex items-center justify-center text-primary">
          <span className="material-symbols-outlined text-xl">{icon}</span>
        </div>
        <h3 className="text-xs font-extrabold tracking-widest uppercase opacity-60">{title}</h3>
      </div>
      <div className="bg-surface-container-low rounded-[32px] p-6 border border-outline-variant/10 space-y-6">
        {children}
      </div>
    </section>
  )

  return (
    <div className="min-h-screen bg-surface text-on-surface pb-32">
      {/* Top Bar */}
      <nav className="sticky top-0 z-50 bg-surface/80 backdrop-blur-md px-4 py-4 flex items-center justify-between">
        <button onClick={() => navigate(-1)} className="p-2 -ml-2 text-on-surface-variant hover:text-primary transition-colors">
          <span className="material-symbols-outlined text-2xl">arrow_back</span>
        </button>
        <div className="flex flex-col items-center">
          <span className="text-[10px] font-bold tracking-[0.2em] text-primary/60 uppercase">Digital Vault</span>
          <h1 className="text-sm font-extrabold font-headline uppercase leading-none mt-1">{t('client.pet_passport')}</h1>
        </div>
        <div className="w-10"></div>
      </nav>

      <motion.main 
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        className="px-5 pt-4 space-y-10 max-w-xl mx-auto"
      >
        {/* Identity Card (Glossy) */}
        <section className="relative aspect-[1.58/1] w-full rounded-[40px] overflow-hidden shadow-2xl shadow-primary/20">
          <img src={catData.imageUrl} alt={catData.name} className="absolute inset-0 w-full h-full object-cover" />
          <div className="absolute inset-0 bg-gradient-to-t from-black/80 via-black/20 to-transparent" />
          
          <div className="absolute bottom-8 left-8 right-8 flex items-end justify-between text-white">
            <div className="space-y-1">
              <h2 className="text-5xl font-extrabold font-headline tracking-tighter leading-none">{catData.name}</h2>
              <p className="text-sm font-medium opacity-80">{catData.breed}</p>
            </div>
            <div className="text-right">
              <p className="text-[10px] font-bold tracking-widest uppercase opacity-40 mb-1">Passport ID</p>
              <p className="text-xs font-mono font-bold">WW-{catData.chipId.slice(-6)}</p>
            </div>
          </div>
        </section>

        {/* Quick Stats Grid */}
        <section className="grid grid-cols-2 gap-8 px-2 py-4 border-y border-outline-variant/10">
          <InfoTag icon="calendar_today" label="Birthday" value={catData.birthDate} />
          <InfoTag icon="monitor_weight" label="Weight" value={catData.weight} />
          <InfoTag icon="fingerprint" label="Microchip" value={catData.chipId} />
          <InfoTag icon="verified_user" label="Status" value="Verified Member" />
        </section>

        {/* Medical Vault */}
        <VaultSection title="Medical Records" icon="medical_services">
          <div className="space-y-4">
            <div>
              <p className="text-[10px] font-bold opacity-40 uppercase tracking-widest mb-2">Vaccination History</p>
              <div className="flex flex-wrap gap-2">
                {catData.medical.vaccines.map(v => (
                  <span key={v} className="px-3 py-1 bg-surface-container text-on-surface text-[11px] font-bold rounded-full border border-outline-variant/10">{v}</span>
                ))}
              </div>
            </div>
            <div className="grid grid-cols-1 gap-4">
              <div>
                <p className="text-[10px] font-bold text-error/60 uppercase tracking-widest mb-1">Allergies</p>
                <p className="text-sm font-medium">{catData.medical.allergies.join(', ')}</p>
              </div>
              <div>
                <p className="text-[10px] font-bold opacity-40 uppercase tracking-widest mb-1">Ongoing Meds</p>
                <p className="text-sm font-medium">{catData.medical.medications}</p>
              </div>
            </div>
          </div>
        </VaultSection>

        {/* Lifestyle & Habits */}
        <VaultSection title="Concierge SOP" icon="star">
          <div className="space-y-6">
            <div>
              <p className="text-[10px] font-bold opacity-40 uppercase tracking-widest mb-1">Dietary Preferences</p>
              <p className="text-sm font-medium leading-relaxed italic">"{catData.habits.diet}"</p>
            </div>
            <div>
              <p className="text-[10px] font-bold opacity-40 uppercase tracking-widest mb-1">Behavioral Notes</p>
              <p className="text-sm font-medium leading-relaxed">{catData.habits.personality}</p>
            </div>
          </div>
        </VaultSection>

        {/* Emergency Portal */}
        <VaultSection title="Emergency Protocol" icon="report">
          <div className="space-y-4">
            <div className="p-4 bg-error-container/10 rounded-2xl border border-error/5">
              <p className="text-[10px] font-bold text-error/60 uppercase tracking-widest mb-2 flex items-center gap-1">
                <span className="material-symbols-outlined text-sm">local_hospital</span> Primary Vet
              </p>
              <p className="text-sm font-extrabold text-on-surface">{catData.emergency.vet}</p>
            </div>
            <div className="p-4 bg-surface-container rounded-2xl border border-outline-variant/10">
              <p className="text-[10px] font-bold opacity-40 uppercase tracking-widest mb-2">Access & Key Notes</p>
              <p className="text-sm font-medium leading-relaxed text-on-surface-variant">{catData.emergency.notes}</p>
            </div>
          </div>
        </VaultSection>
      </motion.main>
    </div>
  )
}

export default CatPassport
