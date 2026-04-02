import React, { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { motion, AnimatePresence } from 'framer-motion'
import { useAuthStore } from '../../store/authStore'
import { profileService, storageService, calendarService } from '../../services/api'

const Profile = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const user = useAuthStore((state) => state.user)
  const logout = useAuthStore((state) => state.logout)
  
  const [isUploading, setIsUploading] = useState(false)
  const [isLoading, setIsLoading] = useState(true)
  const [sitterData, setSitterData] = useState(null)
  const [calendarStatus, setCalendarStatus] = useState(null)
  
  useEffect(() => {
    const fetchData = async () => {
      const isSitter = user?.role === 'SITTER' || user?.lastActiveRole === 'SITTER'
      console.log('[DEBUG] Auth State:', { user, isSitter });
      if (isSitter) {
        setIsLoading(true)
        try {
          const profile = await profileService.getSitterMe()
          console.log('[DEBUG] Sitter Profile:', profile);
          setSitterData(profile)
        } catch (error) {
          console.error('[DEBUG] Failed to fetch profile:', error)
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
        setIsLoading(false)
      }
    }
    fetchData()
  }, [user])

  const handleUpdate = async (field, value) => {
    const isSitter = user?.role === 'SITTER' || user?.lastActiveRole === 'SITTER'
    if (!isSitter) return
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
          <h2 className="text-3xl font-extrabold font-headline tracking-tighter">{user?.profiles?.[0]?.name || user?.name}</h2>
          <div className="mt-2 flex items-center justify-center gap-2">
            <span className="px-3 py-0.5 bg-primary/10 text-primary text-[10px] font-bold rounded-full border border-primary/20 uppercase tracking-widest">
              {(user?.role === 'SITTER' || user?.lastActiveRole === 'SITTER') ? 'Professional Sitter' : 'Elite Owner'}
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
              <p className="text-[10px] font-bold tracking-[0.3em] uppercase opacity-60">帳戶方案</p>
              <h3 className="text-4xl font-extrabold font-headline tracking-tighter italic">Professional.</h3>
            </div>
            <p className="text-xs font-medium opacity-80 leading-relaxed max-w-[200px]">
              您目前使用的是 專業版方案 ($899/月)。享受全自動日曆同步與專業報表。
            </p>
            <button className="flex items-center gap-2 px-5 py-2.5 bg-surface text-on-surface rounded-full text-[11px] font-bold hover:scale-105 active:scale-95 transition-all">
              管理訂閱
              <span className="material-symbols-outlined text-base">north_east</span>
            </button>
          </div>
        </section>

        <div className="space-y-8">
          {(() => {
            const condRole = user?.role === 'SITTER' || user?.lastActiveRole === 'SITTER';
            console.log('[DEBUG] Condition Check:', { condRole, hasSitterData: !!sitterData });
            return null;
          })()}
          {(user?.role === 'SITTER' || user?.lastActiveRole === 'SITTER') && sitterData && (
            <>
              {/* Calendar Sync (New) */}
              <SettingsSection title="行事曆同步 (Beta)">
                <SettingsItem 
                  icon="calendar_apps" 
                  label="Google 行事曆狀態" 
                  value={calendarStatus?.linked ? "服務同步中" : "未連結"}
                  description={calendarStatus?.linked ? "所有確認訂單將自動同步至 Google" : "同步您的預約排程至私人日曆"}
                  color={calendarStatus?.linked ? "text-primary" : "text-on-surface-variant/40"}
                  onClick={calendarStatus?.linked ? handleDisconnectCalendar : handleConnectCalendar}
                />
                <SettingsItem 
                  icon="rss_feed" 
                  label="Ical 訂閱網址" 
                  value={calendarStatus?.feedUrl ? "點擊複製網址" : "尚未產生"}
                  description={calendarStatus?.feedUrl || "同步至 Apple Calendar / Outlook"}
                  onClick={() => {
                    if (calendarStatus?.feedUrl) {
                      navigator.clipboard.writeText(window.location.origin + calendarStatus.feedUrl)
                      alert('已複製 Ical 網址')
                    } else {
                      handleResetIcal()
                    }
                  }}
                />
                {calendarStatus?.feedUrl && (
                  <button 
                    onClick={handleResetIcal}
                    className="w-full py-4 text-[10px] font-black text-on-surface-variant/30 hover:text-on-surface-variant/60 transition-colors uppercase tracking-widest border-t border-outline-variant/5"
                  >
                    重置訂閱 Token
                  </button>
                )}
              </SettingsSection>

              {/* Identity Verification Section */}
              <SettingsSection title="身份驗證狀態">
                <SettingsItem 
                  icon={sitterData.isVerified ? "verified_user" : "pending_actions"} 
                  label="審核狀態" 
                  value={sitterData.isVerified ? "已通過專業認證" : "審核中"}
                  color={sitterData.isVerified ? "text-primary" : "text-on-surface-variant/40"}
                />
                <div className="grid grid-cols-2 gap-px bg-outline-variant/10">
                   <button className="p-6 bg-surface-container-low hover:bg-surface-container-high transition-colors text-left relative overflow-hidden h-32">
                      <p className="text-[10px] font-bold opacity-40 uppercase tracking-widest leading-none mb-2 z-10 relative">證件正面</p>
                      {sitterData.idCardFrontUrl ? (
                        <img src={sitterData.idCardFrontUrl} alt="Front" className="absolute inset-0 w-full h-full object-cover opacity-50" />
                      ) : (
                        <span className="material-symbols-outlined text-primary relative z-10">upload_file</span>
                      )}
                      <input type="file" className="absolute inset-0 opacity-0 cursor-pointer z-20" onChange={(e) => handleIdentityUpload(e, 'front')} />
                   </button>
                   <button className="p-6 bg-surface-container-low hover:bg-surface-container-high transition-colors text-left relative overflow-hidden h-32">
                      <p className="text-[10px] font-bold opacity-40 uppercase tracking-widest leading-none mb-2 z-10 relative">證件反面</p>
                      {sitterData.idCardBackUrl ? (
                        <img src={sitterData.idCardBackUrl} alt="Back" className="absolute inset-0 w-full h-full object-cover opacity-50" />
                      ) : (
                        <span className="material-symbols-outlined text-primary relative z-10">upload_file</span>
                      )}
                      <input type="file" className="absolute inset-0 opacity-0 cursor-pointer z-20" onChange={(e) => handleIdentityUpload(e, 'back')} />
                   </button>
                </div>
              </SettingsSection>

              {/* Professional Labels */}
              <SettingsSection title="專業形象標籤">
                <div className="p-6 space-y-4">
                   <p className="text-[10px] font-bold opacity-40 uppercase tracking-[0.2em] leading-none mb-1">您的服務特色</p>
                   <div className="flex flex-wrap gap-2">
                      {sitterData.professionalLabels?.map((label, idx) => (
                        <span key={idx} className="px-3 py-1 bg-primary/5 text-primary text-[10px] font-extrabold rounded-lg border border-primary/10">
                          {label}
                        </span>
                      ))}
                      <button className="px-3 py-1 bg-surface-container text-on-surface-variant text-[10px] font-extrabold rounded-lg border border-dashed border-outline-variant/30">
                        + 新增標籤
                      </button>
                   </div>
                </div>
              </SettingsSection>

              {/* Payout Information */}
              <SettingsSection title="財務結算資訊 (選填)">
                <SettingsItem icon="account_balance" label="銀行代碼" value={sitterData.bankCode || "未設定"} />
                <SettingsItem icon="credit_card" label="匯款帳號" value={sitterData.bankAccount || "未設定"} />
              </SettingsSection>

              {/* Business Tools (New) */}
              <SettingsSection title="專業經營工具">
                <SettingsItem 
                  icon="inventory_2" 
                  label="管理服務方案" 
                  value="設定定價與時數" 
                  onClick={() => navigate('/sitter/service-packages')} 
                />
                <SettingsItem 
                  icon="assignment" 
                  label="預約問卷設定" 
                  value="編輯家長必填題目" 
                  onClick={() => navigate('/sitter/questionnaire')} 
                />
                <SettingsItem 
                  icon="group" 
                  label="信任圈夥伴" 
                  value="管理互助保母列表" 
                  onClick={() => navigate('/sitter/trust-circle')} 
                />
              </SettingsSection>
            </>
          )}

          <SettingsSection title="帳號與安全">
            <SettingsItem icon="person" label="顯示名稱" value={user?.profiles?.[0]?.name || user?.name} />
            <SettingsItem icon="alternate_email" label="電子郵件" value={user?.email} />
            <SettingsItem icon="lock" label="修改密碼" value="••••••••" />
          </SettingsSection>

          <SettingsSection title="危險控制區">
            <SettingsItem 
              icon="logout" 
              label="登出系統" 
              value="結束本次連線" 
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
