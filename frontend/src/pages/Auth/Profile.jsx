import React, { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { motion, AnimatePresence } from 'framer-motion'
import { useAuthStore } from '../../store/authStore'
import { profileService, storageService } from '../../services/api'

const Profile = () => {
  const { t } = useTranslation()
  const user = useAuthStore((state) => state.user)
  const logout = useAuthStore((state) => state.logout)
  
  const [isUploading, setIsUploading] = useState(false)
  const [isLoading, setIsLoading] = useState(true)
  const [sitterData, setSitterData] = useState(null)
  const [activeTab, setActiveTab] = useState('account')

  useEffect(() => {
    const fetchProfile = async () => {
      if (user?.role === 'ROLE_SITTER') {
        try {
          setIsLoading(true)
          const data = await profileService.getSitterMe()
          setSitterData(data)
        } catch (error) {
          console.error('Failed to fetch sitter profile:', error)
        } finally {
          setIsLoading(false)
        }
      } else {
        setIsLoading(false)
      }
    }
    fetchProfile()
  }, [user])

  const handleUpdate = async (field, value) => {
    if (user?.role !== 'ROLE_SITTER') return
    try {
      const updated = await profileService.updateSitterMe({
        ...sitterData,
        [field]: value
      })
      setSitterData(updated)
    } catch (e) {
      console.error('Update failed:', e)
    }
  }

  const handleIdentityUpload = async (e, type) => {
    const file = e.target.files?.[0]
    if (!file) return
    setIsUploading(true)
    try {
      const url = await storageService.uploadFile(file, 'identity')
      await handleUpdate(type === 'front' ? 'idCardFrontUrl' : 'idCardBackUrl', url)
    } catch (error) {
      console.error('Identity upload failed:', error)
    } finally {
      setIsUploading(false)
    }
  }

  const handleAvatarUpload = async (e) => {
    const file = e.target.files?.[0]
    if (!file) return
    setIsUploading(true)
    try {
      const url = await storageService.uploadFile(file, 'profiles')
      await handleUpdate('avatarUrl', url)
    } catch (error) {
      console.error('[Profile] Avatar upload failed:', error)
    } finally {
      setIsUploading(false)
    }
  }

  const SettingsSection = ({ title, children }) => (
    <div className="space-y-4">
      <h3 className="text-[10px] font-bold tracking-[0.2em] text-on-surface-variant/40 uppercase px-1">{title}</h3>
      <div className="bg-surface-container-low rounded-[32px] border border-outline-variant/10 overflow-hidden">
        {children}
      </div>
    </div>
  )

  const SettingsItem = ({ icon, label, value, onClick, color = "text-on-surface" }) => (
    <button 
      onClick={onClick}
      className="w-full px-6 py-5 flex items-center justify-between hover:bg-surface-container-high/50 transition-colors border-b border-outline-variant/5 last:border-0"
    >
      <div className="flex items-center gap-4">
        <div className={`w-10 h-10 rounded-2xl bg-surface-container flex items-center justify-center ${color === "text-error" ? "text-error" : "text-primary"}`}>
          <span className="material-symbols-outlined text-xl">{icon}</span>
        </div>
        <div className="text-left">
          <p className="text-xs font-bold opacity-40 uppercase tracking-widest leading-none mb-1.5">{label}</p>
          <p className={`text-sm font-extrabold ${color}`}>{value}</p>
        </div>
      </div>
      <span className="material-symbols-outlined text-lg opacity-20">chevron_right</span>
    </button>
  )

  return (
    <div className="min-h-screen bg-surface text-on-surface pb-32">
      {/* Header with Avatar */}
      <section className="px-6 pt-12 pb-8 flex flex-col items-center text-center space-y-6">
        <div className="relative group">
          <div className="w-32 h-32 rounded-[48px] bg-primary/5 p-1 border-2 border-primary/20 overflow-hidden relative">
            <img 
              src={user?.avatarUrl || "https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?auto=format&fit=crop&q=80&w=200"} 
              alt="Profile" 
              className={`w-full h-full object-cover rounded-[44px] transition-all duration-500 ${isUploading ? 'blur-sm scale-95' : 'group-hover:scale-105'}`}
            />
            <AnimatePresence>
              {isUploading && (
                <motion.div 
                  initial={{ opacity: 0 }} 
                  animate={{ opacity: 1 }} 
                  className="absolute inset-0 flex items-center justify-center bg-black/20"
                >
                  <span className="material-symbols-outlined text-white animate-spin">progress_activity</span>
                </motion.div>
              )}
            </AnimatePresence>
          </div>
          <label className="absolute -bottom-2 -right-2 w-10 h-10 bg-primary text-on-primary rounded-2xl shadow-xl flex items-center justify-center cursor-pointer hover:scale-110 active:scale-95 transition-all">
            <span className="material-symbols-outlined text-base">photo_camera</span>
            <input type="file" className="hidden" accept="image/*" onChange={handleAvatarUpload} />
          </label>
        </div>

        <div>
          <h2 className="text-3xl font-extrabold font-headline tracking-tighter">{user?.name || 'James Wilson'}</h2>
          <div className="mt-2 flex items-center justify-center gap-2">
            <span className="px-3 py-0.5 bg-primary/10 text-primary text-[10px] font-bold rounded-full border border-primary/20 uppercase tracking-widest">
              {user?.role === 'ROLE_SITTER' ? 'Professional Sitter' : 'Elite Owner'}
            </span>
          </div>
        </div>
      </section>

      <motion.main 
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        className="px-5 space-y-10 max-w-xl mx-auto"
      >
        {/* SaaS Tier Card (Magazine Style) */}
        <section className="relative overflow-hidden rounded-[40px] p-8 bg-gradient-to-br from-on-surface to-on-surface-variant text-surface shadow-2xl">
          <div className="absolute top-0 right-0 p-4 opacity-10">
            <span className="material-symbols-outlined text-9xl">verified</span>
          </div>
          <div className="relative z-10 space-y-6">
            <div className="space-y-1">
              <p className="text-[10px] font-bold tracking-[0.3em] uppercase opacity-60">Account Status</p>
              <h3 className="text-4xl font-extrabold font-headline tracking-tighter italic">Professional.</h3>
            </div>
            <p className="text-xs font-medium opacity-80 leading-relaxed max-w-[200px]">
              {t('profile.saas_description', 'You are currently on the Professional Tier ($899). Enjoy full billing sovereignty and AI reports.')}
            </p>
            <button className="flex items-center gap-2 px-5 py-2.5 bg-surface text-on-surface rounded-full text-[11px] font-bold hover:scale-105 active:scale-95 transition-all">
              Manage Subscription
              <span className="material-symbols-outlined text-base">north_east</span>
            </button>
          </div>
        </section>

        {/* Global Settings Sections */}
        <div className="space-y-8">
          {user?.role === 'ROLE_SITTER' && sitterData && (
            <>
              {/* Identity Verification Section (V30) */}
              <SettingsSection title="Identity Verification">
                <SettingsItem 
                  icon={sitterData.isVerified ? "verified_user" : "pending_actions"} 
                  label="Verification Status" 
                  value={sitterData.isVerified ? "Verified Professional" : "Pending Verification"}
                  color={sitterData.isVerified ? "text-primary" : "text-on-surface-variant/40"}
                />
                <div className="grid grid-cols-2 gap-px bg-outline-variant/10">
                  <button className="p-6 bg-surface-container-low hover:bg-surface-container-high transition-colors text-left relative overflow-hidden">
                     <p className="text-[10px] font-bold opacity-40 uppercase tracking-widest leading-none mb-2">ID Front</p>
                     <span className="material-symbols-outlined text-primary">{sitterData.idCardFrontUrl ? 'task' : 'upload_file'}</span>
                     <input type="file" className="absolute inset-0 opacity-0 cursor-pointer" onChange={(e) => handleIdentityUpload(e, 'front')} />
                  </button>
                  <button className="p-6 bg-surface-container-low hover:bg-surface-container-high transition-colors text-left relative overflow-hidden">
                     <p className="text-[10px] font-bold opacity-40 uppercase tracking-widest leading-none mb-2">ID Back</p>
                     <span className="material-symbols-outlined text-primary">{sitterData.idCardBackUrl ? 'task' : 'upload_file'}</span>
                     <input type="file" className="absolute inset-0 opacity-0 cursor-pointer" onChange={(e) => handleIdentityUpload(e, 'back')} />
                  </button>
                </div>
              </SettingsSection>

              {/* Professional Labels (V30) */}
              <SettingsSection title="Professional Narrative">
                <div className="p-6 space-y-4">
                   <p className="text-[10px] font-bold opacity-40 uppercase tracking-[0.2em] leading-none mb-1">Your Focus Labels</p>
                   <div className="flex flex-wrap gap-2">
                      {sitterData.professionalLabels?.map((label, idx) => (
                        <span key={idx} className="px-3 py-1 bg-primary/5 text-primary text-[10px] font-extrabold rounded-lg border border-primary/10">
                          {label}
                        </span>
                      ))}
                      <button className="px-3 py-1 bg-surface-container text-on-surface-variant text-[10px] font-extrabold rounded-lg border border-dashed border-outline-variant/30">
                        + ADD LABEL
                      </button>
                   </div>
                </div>
              </SettingsSection>

              {/* Payout Information (Optional, V30) */}
              <SettingsSection title="Financial Settlement (Optional)">
                <SettingsItem icon="account_balance" label="Bank Channel" value={sitterData.bankCode || "Not Linked"} />
                <SettingsItem icon="credit_card" label="Account Number" value={sitterData.bankAccount || "Not Linked"} />
              </SettingsSection>
            </>
          )}

          <SettingsSection title={t('profile.account_group', 'Identity & Security')}>
            <SettingsItem icon="person" label="Display Name" value={user?.name} />
            <SettingsItem icon="alternate_email" label="Email Address" value={user?.email} />
            <SettingsItem icon="lock" label="Account Password" value="••••••••" />
          </SettingsSection>

          <SettingsSection title={t('profile.danger_group', 'Danger Zone')}>
            <SettingsItem 
              icon="logout" 
              label="End Session" 
              value="Sign out of WhiskerWatch" 
              color="text-error" 
              onClick={logout} 
            />
          </SettingsSection>
        </div>
      </motion.main>
    </div>
  )
}

export default Profile
