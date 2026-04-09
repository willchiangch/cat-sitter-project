import React, { useState, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { motion, AnimatePresence } from 'framer-motion'
import { useAuthStore } from '../../store/authStore'
import { useThemeStore } from '../../store/themeStore'
import { profileService, storageService, calendarService, petService, authService } from '../../services/api'
import PetFormModal from '../../components/client/PetFormModal'
import DevAuthTools from '../../components/DevAuthTools'

const Profile = () => {
  const user = useAuthStore((state) => state.user)
  const logout = useAuthStore((state) => state.logout)
  const { mode } = useThemeStore()
  const isSitter = mode === 'SITTER'
  const navigate = useNavigate()

  const [isUploading, setIsUploading] = useState(false)
  const [uploadingField, setUploadingField] = useState(null)
  const [uploadError, setUploadError] = useState(null)
  const [isLoading, setIsLoading] = useState(true)
  const [sitterData, setSitterData] = useState(null)
  const [clientData, setClientData] = useState(null)
  const [calendarStatus, setCalendarStatus] = useState(null)
  const [pets, setPets] = useState([])
  const [showPetModal, setShowPetModal] = useState(false)
  const [localAvatarUrl, setLocalAvatarUrl] = useState(null)
  const [showEditProfile, setShowEditProfile] = useState(false)
  const [editName, setEditName] = useState('')
  const [editPhone, setEditPhone] = useState('')
  const [isSavingProfile, setIsSavingProfile] = useState(false)
  const [showLabelInput, setShowLabelInput] = useState(false)
  const [labelError, setLabelError] = useState('')
  const labelInputRef = useRef(null)
  const [showBankEdit, setShowBankEdit] = useState(false)
  const [editBankCode, setEditBankCode] = useState('')
  const [editBankAccount, setEditBankAccount] = useState('')
  const [isSavingBank, setIsSavingBank] = useState(false)
  const [showSitterNameEdit, setShowSitterNameEdit] = useState(false)
  const [editSitterName, setEditSitterName] = useState('')
  const [isSavingSitterName, setIsSavingSitterName] = useState(false)
  const [profileSaveError, setProfileSaveError] = useState('')
  const [showEmailEdit, setShowEmailEdit] = useState(false)
  const [editEmail, setEditEmail] = useState('')
  const [isSavingEmail, setIsSavingEmail] = useState(false)
  const [emailSaveError, setEmailSaveError] = useState('')
  const [emailSuccessMessage, setEmailSuccessMessage] = useState('')
  
  useEffect(() => {
    const fetchData = async () => {
      if (isSitter) {
        if (!sitterData) setIsLoading(true)
        try {
          const profile = await profileService.getSitterMe()
          setSitterData(profile)
        } catch (error) {
          console.error('Failed to fetch sitter profile:', error)
        }

        try {
          const calendar = await calendarService.getStatus()
          setCalendarStatus(calendar)
        } catch (error) {
          console.warn('Failed to fetch calendar status:', error)
        } finally {
          setIsLoading(false)
        }
      } else {
        setIsLoading(true)
        try {
          const profile = await profileService.getClientMe()
          setClientData(profile)
        } catch (error) {
          console.error('Failed to fetch client profile:', error)
        } finally {
          setIsLoading(false)
        }
      }
    }
    fetchData()
  }, [user, mode])

  useEffect(() => {
    if (!isSitter && user) {
      petService.list().then(setPets).catch(() => {})
    }
  }, [user, mode])

  const handleUpdate = async (field, value) => {
    if (!isSitter || !sitterData) return false
    try {
      const updated = await profileService.updateSitterMe({
        ...sitterData,
        [field]: value
      })
      setSitterData(updated)
      return true
    } catch (e) {
      console.error('Update failed:', e)
      return false
    }
  }

  const handleIdentityUpload = async (e, type) => {
    const file = e.target.files?.[0]
    if (!file) return
    const field = type === 'front' ? 'idCardFrontUrl' : 'facePhotoUrl'
    setUploadingField(field)
    setUploadError(null)
    try {
      const url = await storageService.uploadFile(file, `identity/${type}`)
      await handleUpdate(field, url)
    } catch (error) {
      console.error('Identity upload failed:', error)
      setUploadError(field)
      setTimeout(() => setUploadError(null), 3000)
    } finally {
      setUploadingField(null)
    }
  }

  const handleAddLabel = async () => {
    const label = labelInputRef.current?.value?.trim()
    if (!label) return
    setLabelError('')
    const ok = await handleUpdate('professionalLabels', [...(sitterData?.professionalLabels || []), label])
    if (ok) {
      if (labelInputRef.current) labelInputRef.current.value = ''
      setShowLabelInput(false)
    } else {
      setLabelError('儲存失敗，請稍後再試')
    }
  }

  const handleRemoveLabel = async (idx) => {
    if (!sitterData) return
    const labels = (sitterData.professionalLabels || []).filter((_, i) => i !== idx)
    await handleUpdate('professionalLabels', labels)
  }

  const openBankEdit = () => {
    setEditBankCode(sitterData?.bankCode || '')
    setEditBankAccount(sitterData?.bankAccount || '')
    setShowBankEdit(true)
  }

  const handleSaveBankInfo = async () => {
    setIsSavingBank(true)
    try {
      await handleUpdate('bankCode', editBankCode)
      await handleUpdate('bankAccount', editBankAccount)
      setShowBankEdit(false)
    } catch (e) {
      console.error('Save bank info failed:', e)
    } finally {
      setIsSavingBank(false)
    }
  }

  const openEditProfile = () => {
    setEditName(clientData?.name || user?.profiles?.[0]?.name || user?.name || '')
    setEditPhone(clientData?.phone || '')
    setShowEditProfile(true)
  }

  const handleSaveClientProfile = async () => {
    if (!editName.trim()) { setProfileSaveError('請填寫顯示名稱'); return }
    setIsSavingProfile(true)
    setProfileSaveError('')
    try {
      const updated = await profileService.updateClientMe({
        name: editName.trim(),
        phone: editPhone,
        avatarUrl: clientData?.avatarUrl || null,
        address: clientData?.address || null,
      })
      setClientData(updated)
      setShowEditProfile(false)
    } catch (e) {
      console.error('Save client profile failed:', e)
      setProfileSaveError('儲存失敗，請稍後再試')
    } finally {
      setIsSavingProfile(false)
    }
  }

  const openSitterNameEdit = () => {
    setEditSitterName(sitterData?.name || '')
    setShowSitterNameEdit(true)
  }

  const handleSaveSitterName = async () => {
    if (!editSitterName.trim()) return
    setIsSavingSitterName(true)
    const ok = await handleUpdate('name', editSitterName.trim())
    setIsSavingSitterName(false)
    if (ok) setShowSitterNameEdit(false)
  }

  const openEmailEdit = () => {
    setEditEmail(user?.email || '')
    setEmailSaveError('')
    setEmailSuccessMessage('')
    setShowEmailEdit(true)
  }

  const handleSaveEmail = async () => {
    if (!editEmail.trim() || !editEmail.includes('@')) {
      setEmailSaveError('請輸入有效的電子郵件地址')
      return
    }
    setIsSavingEmail(true)
    setEmailSaveError('')
    try {
      await authService.updateEmail(editEmail.trim())
      setEmailSuccessMessage('Email 已更新！驗證信已寄出，請檢查您的新信箱。')
      setTimeout(() => {
        setShowEmailEdit(false)
        window.location.reload() // Reload to refresh user info in store
      }, 3000)
    } catch (e) {
      console.error('Save email failed:', e)
      setEmailSaveError(e.response?.data?.message || '儲存失敗，該 Email 可能已被使用')
    } finally {
      setIsSavingEmail(false)
    }
  }

  const handleConnectCalendar = async () => {
    try {
      const { url } = await calendarService.getAuthUrl()
      window.location.href = url
    } catch (e) {
      console.error('Failed to get auth url:', e)
    }
  }

  const handleDisconnectCalendar = async () => {
    if (!window.confirm('確定要斷開 Google 行事曆連結嗎？')) return
    try {
      await calendarService.disconnect()
      const status = await calendarService.getStatus()
      setCalendarStatus(status)
    } catch (e) {
      console.error('Disconnect failed:', e)
    }
  }

  const handleResetIcal = async () => {
    try {
      const status = await calendarService.resetToken()
      setCalendarStatus(prev => ({ ...prev, ...status, hasIcalToken: true }))
    } catch (e) {
      console.error('Reset token failed:', e)
    }
  }

  const handleAvatarUpload = async (e) => {
    const file = e.target.files?.[0]
    if (!file) return
    setIsUploading(true)
    try {
      const url = await storageService.uploadFile(file, 'profiles')
      if (isSitter) {
        await handleUpdate('avatarUrl', url)
      } else {
        const updated = await profileService.updateClientMe({ ...clientData, avatarUrl: url })
        setClientData(updated)
      }
      setLocalAvatarUrl(url)
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

  const SettingsItem = ({ icon, label, value, onClick, color = "text-on-surface", description }) => (
    <button 
      onClick={onClick}
      className={`w-full px-6 py-5 flex items-center justify-between hover:bg-surface-container-high/50 transition-colors border-b border-outline-variant/5 last:border-0 ${!onClick ? 'cursor-default group' : ''}`}
    >
      <div className="flex items-center gap-4">
        <div className={`w-10 h-10 rounded-2xl bg-surface-container flex items-center justify-center ${color === "text-error" ? "text-error" : "text-primary"}`}>
          <span className="material-symbols-outlined text-xl">{icon}</span>
        </div>
        <div className="text-left">
          <p className="text-xs font-bold opacity-40 uppercase tracking-widest leading-none mb-1.5">{label}</p>
          <p className={`text-sm font-extrabold ${color}`}>{value}</p>
          {description && <p className="text-[10px] opacity-30 font-bold mt-1 max-w-[200px] truncate">{description}</p>}
        </div>
      </div>
      {onClick && <span className="material-symbols-outlined text-lg opacity-20 group-hover:opacity-100 transition-opacity">chevron_right</span>}
    </button>
  )

  return (
    <div className="min-h-screen bg-surface text-on-surface pb-32">
      <DevAuthTools />


      {/* Header with Avatar */}
      <section className="px-6 pt-12 pb-8 flex flex-col items-center text-center space-y-6">
        <div className="relative group">
          <div className="w-32 h-32 rounded-[48px] bg-white p-1.5 shadow-[0_10px_40px_rgba(30,58,138,0.12)] relative">
            <div className="absolute inset-0 rounded-[48px] bg-gradient-to-br from-[#e0f2fe] to-[#f3e8ff] opacity-50" />
            <img
              src={localAvatarUrl || sitterData?.avatarUrl || clientData?.avatarUrl || user?.avatarUrl || "https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?auto=format&fit=crop&q=80&w=200"}
              alt="Profile"
              className={`w-full h-full object-cover rounded-[40px] relative z-10 transition-all duration-500 ${isUploading ? 'blur-sm scale-95' : 'group-hover:scale-105'}`}
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
          <label className="absolute -bottom-1 -right-1 w-10 h-10 bg-navy text-white rounded-2xl shadow-xl flex items-center justify-center cursor-pointer hover:scale-110 active:scale-95 transition-all z-20">
            <span className="material-symbols-outlined text-base">photo_camera</span>
            <input type="file" className="hidden" accept="image/*" onChange={handleAvatarUpload} />
          </label>
        </div>

        <div>
          <h2 className="text-3xl font-extrabold font-headline tracking-tighter">{user?.profiles?.[0]?.name || user?.name}</h2>
          <div className="mt-2 flex items-center justify-center gap-2">
            <span className="px-3 py-1 bg-gradient-to-r from-[#e0f2fe] to-[#f3e8ff] text-navy text-[10px] font-black rounded-full border border-blue-200/50 uppercase tracking-widest shadow-sm">
              {(isSitter) ? 'Professional Sitter' : 'Elite Owner'}
            </span>
          </div>
        </div>
      </section>

      <motion.main 
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        className="px-5 space-y-10 max-w-xl mx-auto"
      >
        {/* Booking URL (Sitter only) */}
        {isSitter && (
          <section className="bg-surface-container-low rounded-[32px] border border-outline-variant/10 p-6 space-y-4">
            <p className="text-[10px] font-bold tracking-[0.2em] text-on-surface-variant/40 uppercase">接單專屬網址</p>
            {sitterData?.slug ? (
              <p className="text-xs font-bold text-on-surface-variant break-all">
                {`${window.location.origin}/s/${sitterData.slug}`}
              </p>
            ) : (
              <p className="text-xs font-bold text-on-surface-variant/40 italic">載入中...</p>
            )}
            <div className="flex gap-3">
              <button
                onClick={() => {
                  if (!sitterData?.slug) return
                  navigator.clipboard.writeText(`${window.location.origin}/s/${sitterData.slug}`)
                  alert('已複製接單網址')
                }}
                className="flex-1 py-3 bg-surface-container rounded-full text-[10px] font-extrabold uppercase tracking-widest flex items-center justify-center gap-1.5 hover:bg-surface-container-high transition-colors active:scale-95"
              >
                <span className="material-symbols-outlined text-base">content_copy</span>
                複製網址
              </button>
              <button
                onClick={() => sitterData?.slug && navigate(`/s/${sitterData.slug}`)}
                className="flex-1 py-3 bg-surface-container rounded-full text-[10px] font-extrabold uppercase tracking-widest flex items-center justify-center gap-1.5 hover:bg-surface-container-high transition-colors active:scale-95"
              >
                <span className="material-symbols-outlined text-base">north_east</span>
                預覽對外網頁
              </button>
            </div>
          </section>
        )}

        <div className="space-y-8">
          {/* Business Tools — shown for all sitters regardless of sitterData load status */}
          {(isSitter) && (
            <SettingsSection title="專業經營工具">
              <SettingsItem
                icon="inventory_2"
                label="管理服務方案"
                value="設定定價與時數"
                onClick={() => { navigate('/sitter/service-packages'); document.querySelector('main')?.scrollTo({ top: 0, behavior: 'instant' }) }}
              />
              <SettingsItem
                icon="assignment"
                label="預約問卷設定"
                value="編輯家長必填題目"
                onClick={() => { navigate('/sitter/questionnaire'); document.querySelector('main')?.scrollTo({ top: 0, behavior: 'instant' }) }}
              />
              <SettingsItem
                icon="group"
                label="信任圈夥伴"
                value="管理互助保母列表"
                onClick={() => { navigate('/sitter/trust-circle'); document.querySelector('main')?.scrollTo({ top: 0, behavior: 'instant' }) }}
              />
              <SettingsItem
                icon="manage_accounts"
                label="客群門禁管理"
                value="白名單 / 黑名單設定"
                onClick={() => { navigate('/sitter/client-gate'); document.querySelector('main')?.scrollTo({ top: 0, behavior: 'instant' }) }}
              />
            </SettingsSection>
          )}

          {isSitter && (
            <>
              {/* Calendar Sync */}
              <SettingsSection title="行事曆同步 (Beta)">
                {/* Google Calendar row */}
                <button
                  onClick={calendarStatus?.linked ? handleDisconnectCalendar : handleConnectCalendar}
                  className="w-full px-6 py-5 flex items-center justify-between hover:bg-surface-container-high/50 transition-colors border-b border-outline-variant/5"
                >
                  <div className="flex items-center gap-4 min-w-0">
                    <div className="w-10 h-10 rounded-2xl bg-surface-container flex items-center justify-center flex-shrink-0 text-primary overflow-hidden">
                      <span className="material-symbols-outlined text-xl">calendar_month</span>
                    </div>
                    <div className="text-left min-w-0">
                      <p className="text-xs font-bold opacity-40 uppercase tracking-widest leading-none mb-1.5">Google 行事曆狀態</p>
                      <p className={`text-sm font-extrabold ${calendarStatus?.linked ? 'text-primary' : 'text-on-surface-variant/40'}`}>
                        {calendarStatus?.linked ? '服務同步中' : '未連結'}
                      </p>
                      <p className="text-[10px] opacity-30 font-bold mt-1">
                        {calendarStatus?.linked ? '所有確認訂單將自動同步至 Google' : '同步您的預約排程至私人日曆'}
                      </p>
                    </div>
                  </div>
                  <span className="material-symbols-outlined text-lg opacity-20 flex-shrink-0 ml-2">chevron_right</span>
                </button>

                {/* Ical URL row — separate layout to handle long URLs */}
                <div className="px-6 py-5 border-b border-outline-variant/5 space-y-3">
                  <div className="flex items-center gap-4">
                    <div className="w-10 h-10 rounded-2xl bg-surface-container flex items-center justify-center flex-shrink-0 text-primary">
                      <span className="material-symbols-outlined text-xl">rss_feed</span>
                    </div>
                    <div className="min-w-0">
                      <p className="text-xs font-bold opacity-40 uppercase tracking-widest leading-none mb-1.5">Ical 訂閱網址</p>
                      <p className="text-sm font-extrabold">{calendarStatus?.feedUrl ? 'iCal Feed 已產生' : '尚未產生'}</p>
                    </div>
                  </div>
                  {calendarStatus?.feedUrl ? (
                    <div className="ml-14 space-y-2">
                      <p className="text-[10px] font-bold opacity-30 break-all leading-relaxed">
                        {window.location.origin + calendarStatus.feedUrl}
                      </p>
                      <button
                        onClick={() => {
                          navigator.clipboard.writeText(window.location.origin + calendarStatus.feedUrl)
                          alert('已複製 Ical 網址')
                        }}
                        className="px-4 py-2 bg-surface-container rounded-full text-[10px] font-extrabold uppercase tracking-widest flex items-center gap-1.5 hover:bg-surface-container-high transition-colors"
                      >
                        <span className="material-symbols-outlined text-sm">content_copy</span>
                        複製網址
                      </button>
                    </div>
                  ) : (
                    <div className="ml-14">
                      <button
                        onClick={handleResetIcal}
                        className="px-4 py-2 bg-surface-container rounded-full text-[10px] font-extrabold uppercase tracking-widest flex items-center gap-1.5 hover:bg-surface-container-high transition-colors"
                      >
                        <span className="material-symbols-outlined text-sm">add_circle</span>
                        產生訂閱連結
                      </button>
                    </div>
                  )}
                </div>

                {calendarStatus?.feedUrl && (
                  <button
                    onClick={handleResetIcal}
                    className="w-full py-4 text-[10px] font-black text-on-surface-variant/30 hover:text-on-surface-variant/60 transition-colors uppercase tracking-widest"
                  >
                    重置訂閱 Token
                  </button>
                )}
              </SettingsSection>

              {/* Identity Verification Section */}
              <SettingsSection title="身份驗證狀態">
                <SettingsItem
                  icon={sitterData?.isVerified ? "verified_user" : "pending_actions"}
                  label="審核狀態"
                  value={sitterData?.isVerified ? "已通過專業認證" : "審核中"}
                  color={sitterData?.isVerified ? "text-primary" : "text-on-surface-variant/40"}
                />
                <div className="grid grid-cols-2 gap-px bg-outline-variant/10">
                   {/* 證件正面 */}
                   <div className="p-5 bg-surface-container-low text-left relative overflow-hidden h-36">
                      <p className="text-[10px] font-bold opacity-40 uppercase tracking-widest leading-none mb-2 z-10 relative">證件正面</p>
                      {sitterData?.idCardFrontUrl ? (
                        <img src={sitterData.idCardFrontUrl} alt="Front" className="absolute inset-0 w-full h-full object-cover opacity-60" />
                      ) : (
                        <span className="material-symbols-outlined text-primary relative z-10 text-3xl">upload_file</span>
                      )}
                      {uploadingField === 'idCardFrontUrl' && (
                        <div className="absolute inset-0 flex items-center justify-center bg-black/30 z-30">
                          <span className="material-symbols-outlined text-white animate-spin text-2xl">progress_activity</span>
                        </div>
                      )}
                      {uploadError === 'idCardFrontUrl' && (
                        <div className="absolute inset-0 flex items-center justify-center bg-error/20 z-30">
                          <span className="text-[10px] font-black text-error">上傳失敗</span>
                        </div>
                      )}
                      <label className="absolute inset-0 cursor-pointer z-20 flex items-end justify-end p-3">
                        <span className="w-8 h-8 bg-navy text-white rounded-xl flex items-center justify-center shadow-lg active:scale-90 transition-transform">
                          <span className="material-symbols-outlined text-sm">photo_camera</span>
                        </span>
                        <input type="file" accept="image/*" className="hidden" onChange={(e) => handleIdentityUpload(e, 'front')} />
                      </label>
                   </div>
                   {/* 人臉辨識自拍 */}
                   <div className="p-5 bg-surface-container-low text-left relative overflow-hidden h-36">
                      <p className="text-[10px] font-bold opacity-40 uppercase tracking-widest leading-none mb-2 z-10 relative">人臉辨識(自拍)</p>
                      {sitterData?.facePhotoUrl ? (
                        <img src={sitterData.facePhotoUrl} alt="Face Photo" className="absolute inset-0 w-full h-full object-cover opacity-60" />
                      ) : (
                        <span className="material-symbols-outlined text-primary relative z-10 text-3xl">face</span>
                      )}
                      {uploadingField === 'facePhotoUrl' && (
                        <div className="absolute inset-0 flex items-center justify-center bg-black/30 z-30">
                          <span className="material-symbols-outlined text-white animate-spin text-2xl">progress_activity</span>
                        </div>
                      )}
                      {uploadError === 'facePhotoUrl' && (
                        <div className="absolute inset-0 flex items-center justify-center bg-error/20 z-30">
                          <span className="text-[10px] font-black text-error">上傳失敗</span>
                        </div>
                      )}
                      <label className="absolute inset-0 cursor-pointer z-20 flex items-end justify-end p-3">
                        <span className="w-8 h-8 bg-navy text-white rounded-xl flex items-center justify-center shadow-lg active:scale-90 transition-transform">
                          <span className="material-symbols-outlined text-sm">photo_camera</span>
                        </span>
                        <input type="file" accept="image/*" capture="user" className="hidden" onChange={(e) => handleIdentityUpload(e, 'face')} />
                      </label>
                   </div>
                </div>
              </SettingsSection>

              {/* Professional Labels */}
              <SettingsSection title="自我介紹">
                <div className="p-6">
                  <textarea
                    defaultValue={sitterData?.bioSummary || ''}
                    key={sitterData?.bioSummary}
                    onBlur={async (e) => {
                      const val = e.target.value.trim()
                      if (val !== (sitterData?.bioSummary || '')) {
                        await handleUpdate('bioSummary', val)
                      }
                    }}
                    rows={4}
                    placeholder="介紹你的服務特色、照護理念、養貓經驗等..."
                    className="w-full p-4 bg-surface-container border border-outline-variant/10 rounded-[20px] text-sm font-medium outline-none focus:border-primary transition-colors resize-none"
                  />
                  <p className="text-[10px] font-bold opacity-30 uppercase tracking-widest mt-2">離開欄位時自動儲存</p>
                </div>
              </SettingsSection>

              <SettingsSection title="專業形象標籤">
                <div className="p-6 space-y-4">
                   <p className="text-[10px] font-bold opacity-40 uppercase tracking-[0.2em] leading-none mb-1">您的服務特色</p>
                   <div className="flex flex-wrap gap-2">
                      {sitterData?.professionalLabels?.map((label, idx) => (
                        <div key={idx} className="flex items-center gap-1 px-3 py-1 bg-primary/5 text-primary text-[10px] font-extrabold rounded-lg border border-primary/10 group">
                          <span>{label}</span>
                          <button
                            onClick={() => handleRemoveLabel(idx)}
                            className="ml-0.5 opacity-0 group-hover:opacity-100 transition-opacity text-error text-[12px] leading-none"
                            title="移除標籤"
                          >×</button>
                        </div>
                      ))}
                      {showLabelInput ? (
                        <div className="flex flex-col gap-1">
                          <div className="flex items-center gap-1">
                            <input
                              ref={labelInputRef}
                              autoFocus
                              type="text"
                              defaultValue=""
                              onKeyDown={e => {
                                if (e.isComposing) return
                                if (e.key === 'Enter') handleAddLabel()
                                if (e.key === 'Escape') { setShowLabelInput(false); setLabelError('') }
                              }}
                              placeholder="輸入標籤..."
                              className="px-3 py-1 text-[10px] font-bold bg-surface-container-low border border-primary rounded-lg outline-none w-28"
                            />
                            <button onClick={handleAddLabel} className="text-primary text-xs font-bold">確認</button>
                          </div>
                          {labelError && <p className="text-[10px] font-bold text-error">{labelError}</p>}
                        </div>
                      ) : (
                        <button
                          onClick={() => setShowLabelInput(true)}
                          className="px-3 py-1 bg-surface-container text-on-surface-variant text-[10px] font-extrabold rounded-lg border border-dashed border-outline-variant/30 hover:border-primary hover:text-primary transition-colors"
                        >
                          + 新增標籤
                        </button>
                      )}
                   </div>
                </div>
              </SettingsSection>

              {/* Payout Information */}
              <SettingsSection title="財務結算資訊 (選填)">
                <SettingsItem icon="account_balance" label="銀行代碼" value={sitterData?.bankCode || "未設定"} onClick={openBankEdit} />
                <SettingsItem icon="credit_card" label="匯款帳號" value={sitterData?.bankAccount ? `****${sitterData.bankAccount.slice(-4)}` : "未設定"} onClick={openBankEdit} />
              </SettingsSection>

            </>
          )}

          {!isSitter && (
            <>
            <SettingsSection title="我的基本資料">
              <SettingsItem
                icon="person"
                label="顯示名稱"
                value={clientData?.name || user?.profiles?.[0]?.name || user?.name || '未設定'}
                onClick={openEditProfile}
              />
              <SettingsItem
                icon="phone"
                label="聯絡電話"
                value={clientData?.phone || '未設定'}
                onClick={openEditProfile}
              />
              <SettingsItem
                icon="alternate_email"
                label="電子郵件"
                value={user?.email}
                onClick={openEmailEdit}
              />
            </SettingsSection>
            </>
          )}

          {!isSitter && (
            <SettingsSection title="我的毛孩">
              <div className="p-5 space-y-4">
                {pets.length > 0 && (
                  <div className="flex gap-4 overflow-x-auto no-scrollbar pb-1">
                    {pets.map(pet => (
                      <div key={pet.id} className="flex-shrink-0 flex flex-col items-center gap-1.5">
                        <div className="w-14 h-14 rounded-2xl overflow-hidden bg-surface-container ring-2 ring-outline-variant/10">
                          <img
                            src={pet.avatarUrl || 'https://images.unsplash.com/photo-1514888286974-6c03e2ca1dba?auto=format&fit=crop&q=80&w=100'}
                            alt={pet.name}
                            className="w-full h-full object-cover"
                          />
                        </div>
                        <p className="text-[10px] font-bold">{pet.name}</p>
                      </div>
                    ))}
                  </div>
                )}
                <div className="flex gap-3">
                  <button
                    onClick={() => setShowPetModal(true)}
                    className="flex-1 py-3 bg-navy text-white rounded-full text-[10px] font-extrabold uppercase tracking-widest flex items-center justify-center gap-1.5 active:scale-95 transition-all shadow-lg shadow-navy/20"
                  >
                    <span className="material-symbols-outlined text-base">add</span>
                    新增寵物
                  </button>
                  <button
                    onClick={() => { navigate('/client/pets'); document.querySelector('main')?.scrollTo({ top: 0, behavior: 'instant' }) }}
                    className="flex-1 py-3 bg-surface-container rounded-full text-[10px] font-extrabold uppercase tracking-widest flex items-center justify-center gap-1.5 active:scale-95 transition-colors hover:bg-surface-container-high"
                  >
                    <span className="material-symbols-outlined text-base">pets</span>
                    管理全部
                  </button>
                </div>
              </div>
            </SettingsSection>
          )}

          {isSitter && (
            <SettingsSection title="帳號與安全">
              <SettingsItem icon="person" label="顯示名稱" value={sitterData?.name || user?.profiles?.[0]?.name || user?.name || '未設定'} onClick={openSitterNameEdit} />
              <SettingsItem icon="alternate_email" label="電子郵件" value={user?.email} onClick={openEmailEdit} />
            </SettingsSection>
          )}

          {/* SaaS Tier Card (Sitter only) - Relocated and resized */}
          {(isSitter) && (
            <section className="relative overflow-hidden rounded-[32px] p-6 bg-navy text-white shadow-xl">
              <div className="absolute top-0 right-0 p-4 opacity-10">
                <span className="material-symbols-outlined text-7xl">verified</span>
              </div>
              <div className="relative z-10 space-y-4">
                <div className="space-y-0.5">
                  <p className="text-[9px] font-bold tracking-[0.3em] uppercase opacity-60 leading-none">帳戶方案</p>
                  <h3 className="text-2xl font-extrabold font-headline tracking-tighter italic text-[#f59e0b]">Professional.</h3>
                </div>
                <p className="text-[11px] font-medium opacity-80 leading-relaxed max-w-[240px]">
                  您目前使用的是 專業版方案 ($899/月)。享受全自動日曆同步與專業報表。
                </p>
                <button
                  onClick={() => navigate('/sitter/subscription')}
                  className="flex items-center gap-2 px-4 py-2 bg-white/10 hover:bg-white/20 text-white border border-white/20 rounded-full text-[10px] font-bold active:scale-95 transition-all w-fit">
                  管理訂閱
                  <span className="material-symbols-outlined text-sm">north_east</span>
                </button>
              </div>
            </section>
          )}

          <SettingsSection title="危險控制區">
            <SettingsItem
              icon="logout"
              label="登出系統"
              value="結束本次連線"
              color="text-error"
              onClick={() => { logout(); navigate('/login') }}
            />
          </SettingsSection>
        </div>
      </motion.main>

      <PetFormModal
        isOpen={showPetModal}
        onClose={() => setShowPetModal(false)}
        onSave={() => { setShowPetModal(false); petService.list().then(setPets).catch(() => {}) }}
      />

      {/* Bank Info Edit Modal */}
      <AnimatePresence>
        {showBankEdit && (
          <div className="fixed inset-0 z-[100] flex items-end sm:items-center justify-center p-4">
            <motion.div
              initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
              onClick={() => setShowBankEdit(false)}
              className="absolute inset-0 bg-black/60 backdrop-blur-sm"
            />
            <motion.div
              initial={{ y: 100, opacity: 0 }} animate={{ y: 0, opacity: 1 }} exit={{ y: 100, opacity: 0 }}
              className="relative w-full max-w-lg bg-surface rounded-[40px] p-8 shadow-2xl space-y-6"
            >
              <h3 className="text-2xl font-black font-headline tracking-tighter">財務結算資訊</h3>
              <p className="text-xs font-bold opacity-40">僅用於款項撥付，資料加密儲存。</p>
              <div className="space-y-4">
                <div className="space-y-2">
                  <label className="text-[10px] font-bold tracking-[0.2em] text-on-surface-variant/50 uppercase">銀行代碼</label>
                  <input
                    type="text"
                    value={editBankCode}
                    onChange={e => setEditBankCode(e.target.value)}
                    placeholder="例：004"
                    className="w-full px-5 py-3.5 bg-surface-container-low border border-outline-variant/20 rounded-2xl text-sm font-bold outline-none focus:border-primary transition-colors"
                  />
                </div>
                <div className="space-y-2">
                  <label className="text-[10px] font-bold tracking-[0.2em] text-on-surface-variant/50 uppercase">匯款帳號</label>
                  <input
                    type="text"
                    value={editBankAccount}
                    onChange={e => setEditBankAccount(e.target.value)}
                    placeholder="完整銀行帳號"
                    className="w-full px-5 py-3.5 bg-surface-container-low border border-outline-variant/20 rounded-2xl text-sm font-bold outline-none focus:border-primary transition-colors"
                  />
                </div>
              </div>
              <div className="flex gap-3">
                <button onClick={() => setShowBankEdit(false)} className="flex-1 py-4 bg-surface-container-low border border-outline-variant/20 rounded-full text-sm font-bold hover:bg-surface-container transition-colors">取消</button>
                <button onClick={handleSaveBankInfo} disabled={isSavingBank} className="flex-1 py-4 bg-primary text-on-primary rounded-full font-bold shadow-xl shadow-primary/20 disabled:opacity-50 active:scale-95 transition-all">
                  {isSavingBank ? '儲存中...' : '儲存'}
                </button>
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>

      {/* Client Profile Edit Modal */}
      <AnimatePresence>
        {showEditProfile && (
          <div className="fixed inset-0 z-[100] flex items-end sm:items-center justify-center p-4">
            <motion.div
              initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
              onClick={() => { setShowEditProfile(false); setProfileSaveError('') }}
              className="absolute inset-0 bg-black/60 backdrop-blur-sm"
            />
            <motion.div
              initial={{ y: 100, opacity: 0 }} animate={{ y: 0, opacity: 1 }} exit={{ y: 100, opacity: 0 }}
              className="relative w-full max-w-lg bg-surface rounded-[40px] p-8 shadow-2xl space-y-6"
            >
              <h3 className="text-2xl font-black font-headline tracking-tighter">編輯基本資料</h3>
              <div className="space-y-4">
                <div className="space-y-2">
                  <label className="text-[10px] font-bold tracking-[0.2em] text-on-surface-variant/50 uppercase">顯示名稱</label>
                  <input
                    type="text"
                    value={editName}
                    onChange={e => setEditName(e.target.value)}
                    placeholder="您的名稱"
                    className="w-full px-5 py-3.5 bg-surface-container-low border border-outline-variant/20 rounded-2xl text-sm font-bold outline-none focus:border-primary transition-colors"
                  />
                </div>
                <div className="space-y-2">
                  <label className="text-[10px] font-bold tracking-[0.2em] text-on-surface-variant/50 uppercase">聯絡電話</label>
                  <input
                    type="tel"
                    value={editPhone}
                    onChange={e => setEditPhone(e.target.value)}
                    placeholder="09xx-xxx-xxx"
                    className="w-full px-5 py-3.5 bg-surface-container-low border border-outline-variant/20 rounded-2xl text-sm font-bold outline-none focus:border-primary transition-colors"
                  />
                </div>
              </div>
              {profileSaveError && <p className="text-xs font-bold text-error">{profileSaveError}</p>}
              <div className="flex gap-3">
                <button
                  onClick={() => { setShowEditProfile(false); setProfileSaveError('') }}
                  className="flex-1 py-4 bg-surface-container-low border border-outline-variant/20 rounded-full text-sm font-bold hover:bg-surface-container transition-colors"
                >
                  取消
                </button>
                <button
                  onClick={handleSaveClientProfile}
                  disabled={isSavingProfile}
                  className="flex-1 py-4 bg-primary text-on-primary rounded-full font-bold shadow-xl shadow-primary/20 disabled:opacity-50 active:scale-95 transition-all"
                >
                  {isSavingProfile ? '儲存中...' : '儲存'}
                </button>
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>

      {/* Sitter Name Edit Modal */}
      <AnimatePresence>
        {showSitterNameEdit && (
          <div className="fixed inset-0 z-[100] flex items-end sm:items-center justify-center p-4">
            <motion.div
              initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
              onClick={() => setShowSitterNameEdit(false)}
              className="absolute inset-0 bg-black/60 backdrop-blur-sm"
            />
            <motion.div
              initial={{ y: 100, opacity: 0 }} animate={{ y: 0, opacity: 1 }} exit={{ y: 100, opacity: 0 }}
              className="relative w-full max-w-lg bg-surface rounded-[40px] p-8 shadow-2xl space-y-6"
            >
              <h3 className="text-2xl font-black font-headline tracking-tighter">編輯顯示名稱</h3>
              <input
                type="text"
                value={editSitterName}
                onChange={e => setEditSitterName(e.target.value)}
                placeholder="您的名稱"
                className="w-full px-5 py-3.5 bg-surface-container-low border border-outline-variant/20 rounded-2xl text-sm font-bold outline-none focus:border-primary transition-colors"
              />
              <div className="flex gap-3">
                <button onClick={() => setShowSitterNameEdit(false)} className="flex-1 py-4 bg-surface-container-low border border-outline-variant/20 rounded-full text-sm font-bold hover:bg-surface-container transition-colors">取消</button>
                <button onClick={handleSaveSitterName} disabled={isSavingSitterName} className="flex-1 py-4 bg-primary text-on-primary rounded-full font-bold shadow-xl shadow-primary/20 disabled:opacity-50 active:scale-95 transition-all">
                  {isSavingSitterName ? '儲存中...' : '儲存'}
                </button>
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>

      {/* Email Edit Modal */}
      <AnimatePresence>
        {showEmailEdit && (
          <div className="fixed inset-0 z-[100] flex items-end sm:items-center justify-center p-4">
            <motion.div
              initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
              onClick={() => { if (!isSavingEmail) setShowEmailEdit(false) }}
              className="absolute inset-0 bg-black/60 backdrop-blur-sm"
            />
            <motion.div
              initial={{ y: 100, opacity: 0 }} animate={{ y: 0, opacity: 1 }} exit={{ y: 100, opacity: 0 }}
              className="relative w-full max-w-lg bg-surface rounded-[40px] p-8 shadow-2xl space-y-6"
            >
              <h3 className="text-2xl font-black font-headline tracking-tighter">更改電子郵件</h3>
              {emailSuccessMessage ? (
                <div className="p-4 bg-primary/10 rounded-2xl border border-primary/20">
                  <p className="text-sm font-bold text-primary">{emailSuccessMessage}</p>
                </div>
              ) : (
                <div className="space-y-4">
                  <div className="space-y-2">
                    <label className="text-[10px] font-bold tracking-[0.2em] text-on-surface-variant/50 uppercase">新電子郵件地址</label>
                    <input
                      type="email"
                      value={editEmail}
                      onChange={e => setEditEmail(e.target.value)}
                      placeholder="example@mail.com"
                      className="w-full px-5 py-3.5 bg-surface-container-low border border-outline-variant/20 rounded-2xl text-sm font-bold outline-none focus:border-primary transition-colors"
                    />
                  </div>
                  <p className="text-xs font-bold opacity-40">更改後，系統將寄出驗證信，您需要重新驗證新信箱。</p>
                </div>
              )}
              {emailSaveError && <p className="text-xs font-bold text-error">{emailSaveError}</p>}
              {!emailSuccessMessage && (
                <div className="flex gap-3">
                  <button onClick={() => setShowEmailEdit(false)} className="flex-1 py-4 bg-surface-container-low border border-outline-variant/20 rounded-full text-sm font-bold hover:bg-surface-container transition-colors">取消</button>
                  <button onClick={handleSaveEmail} disabled={isSavingEmail} className="flex-1 py-4 bg-primary text-on-primary rounded-full font-bold shadow-xl shadow-primary/20 disabled:opacity-50 active:scale-95 transition-all">
                    {isSavingEmail ? '儲存中...' : '儲存'}
                  </button>
                </div>
              )}
            </motion.div>
          </div>
        )}
      </AnimatePresence>

    </div>
  )
}

export default Profile
