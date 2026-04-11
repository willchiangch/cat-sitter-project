import React, { useState } from 'react'
import { motion } from 'framer-motion'
import { petService, storageService } from '../../services/api'

const PetFormModal = ({ isOpen, onClose, initialData, onSave }) => {
  const [formData, setFormData] = useState(initialData || {
    name: '',
    species: '',
    gender: '',
    neuteredStatus: '',
    vaccinationStatus: '',
    dewormingStatus: '',
    birthYear: new Date().getFullYear().toString(),
    birthMonth: '',
    avatarUrl: '',
    medicalNotes: '',
    personalityNotes: '',
  })
  const [isUploading, setIsUploading] = useState(false)
  const [isSubmitting, setIsSubmitting] = useState(false)

  // Sync stage with initialData when modal opens or initialData changes
  React.useEffect(() => {
    if (isOpen) {
      if (initialData) {
        // Parse birthDate (YYYY-MM-DD) into year and month
        let year = new Date().getFullYear().toString()
        let month = ''
        if (initialData.birthDate) {
          const parts = initialData.birthDate.split('-')
          year = parts[0]
          month = parseInt(parts[1], 10).toString()
        }

        setFormData({
          name: initialData.name || '',
          species: initialData.species || '',
          gender: initialData.gender || '',
          neuteredStatus: initialData.neuteredStatus || '',
          vaccinationStatus: initialData.vaccinationStatus || '',
          dewormingStatus: initialData.dewormingStatus || '',
          birthYear: year,
          birthMonth: month,
          avatarUrl: initialData.avatarUrl || '',
          medicalNotes: initialData.medicalNotes || '',
          personalityNotes: initialData.personalityNotes || '',
        })
      } else {
        setFormData({
          name: '',
          species: '',
          gender: '',
          neuteredStatus: '',
          vaccinationStatus: '',
          dewormingStatus: '',
          birthYear: new Date().getFullYear().toString(),
          birthMonth: '',
          avatarUrl: '',
          medicalNotes: '',
          personalityNotes: '',
        })
      }
    }
  }, [isOpen, initialData])

  const years = Array.from({ length: 30 }, (_, i) => (new Date().getFullYear() - i).toString())
  const months = Array.from({ length: 12 }, (_, i) => (i + 1).toString())

  const handleFileChange = async (e) => {
    const file = e.target.files[0]
    if (!file) return

    try {
      setIsUploading(true)
      const uploadedUrl = await storageService.uploadFile(file, 'pets')
      setFormData(prev => ({ ...prev, avatarUrl: uploadedUrl }))
    } catch (error) {
      alert('圖片上傳失敗，請稍後再試。')
    } finally {
      setIsUploading(false)
    }
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    
    // Validation
    const requiredFields = [
      { key: 'species', name: '寵物種類' },
      { key: 'gender', name: '性別' },
      { key: 'neuteredStatus', name: '結紮狀態' },
      { key: 'vaccinationStatus', name: '疫苗狀態' },
      { key: 'dewormingStatus', name: '驅蟲狀態' }
    ]

    for (const field of requiredFields) {
      if (!formData[field.key]) {
        alert(`請選擇${field.name}！`)
        return
      }
    }

    try {
      setIsSubmitting(true)
      
      const monthStr = formData.birthMonth ? formData.birthMonth.padStart(2, '0') : '01'
      const combinedDate = `${formData.birthYear}-${monthStr}-01`
      
      const submitData = {
        ...formData,
        birthDate: combinedDate
      }
      delete submitData.birthYear
      delete submitData.birthMonth

      if (initialData?.petId) {
        await petService.update(initialData.petId, submitData)
      } else {
        await petService.create(submitData)
      }
      onSave()
      onClose()
    } catch (error) {
      console.error('Save failed:', error)
      alert('儲存失敗，請檢查必填欄位。')
    } finally {
      setIsSubmitting(false)
    }
  }

  if (!isOpen) return null

  const HEALTH_OPTIONS = [
    { value: 'YES', label: '有' },
    { value: 'NO', label: '沒有' },
    { value: 'NOT_REQUIRED', label: '不需要' },
  ]

  return (
    <div className="fixed inset-0 z-[100] flex items-end justify-center px-4 pb-10 sm:items-center">
      <motion.div 
        initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
        onClick={onClose} 
        className="absolute inset-0 bg-on-surface/40 backdrop-blur-sm" 
      />
      <motion.div 
        initial={{ y: '100%' }} animate={{ y: 0 }} exit={{ y: '100%' }}
        className="relative w-full max-w-lg bg-surface rounded-[40px] p-8 shadow-2xl space-y-10 max-h-[90vh] overflow-y-auto no-scrollbar"
      >
        <div className="space-y-1">
          <h3 className="text-3xl font-extrabold font-headline tracking-tighter">
            {initialData ? '編輯毛孩資料' : '新增毛孩'}
          </h3>
          <p className="text-sm font-bold opacity-30 uppercase tracking-widest">Manage your pet profile.</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-10">
          <div className="flex flex-col items-center gap-4">
            <div className="relative w-32 h-32 rounded-[40px] overflow-hidden border-4 border-surface shadow-2xl ring-1 ring-on-surface/5 group">
              {isUploading ? (
                <div className="absolute inset-0 bg-primary/20 flex items-center justify-center italic text-xs font-bold">Uploading...</div>
              ) : (
                <img src={formData.avatarUrl || 'https://placehold.jp/24/336699/ffffff/200x200.png?text=Upload'} className="w-full h-full object-cover" />
              )}
              <label className="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 flex items-center justify-center cursor-pointer transition-opacity">
                <span className="material-symbols-outlined text-white">photo_camera</span>
                <input type="file" className="hidden" accept="image/*" onChange={handleFileChange} />
              </label>
            </div>
            <p className="text-xs font-bold opacity-30 uppercase tracking-widest">點擊頭像上傳實體照片至 GCS</p>
          </div>

          <div className="space-y-8">
            <div className="grid grid-cols-2 gap-6">
              <div className="space-y-2">
                <label className="text-sm font-black uppercase tracking-widest opacity-60 ml-2">
                  毛孩姓名<span className="text-error ml-1">*</span>
                </label>
                <input 
                  required
                  value={formData.name}
                  onChange={e => setFormData(prev => ({ ...prev, name: e.target.value }))}
                  className="w-full p-5 bg-surface-container-low border border-outline-variant/10 rounded-2xl text-sm font-bold outline-none focus:border-primary transition-colors" 
                  placeholder="Oliver" 
                />
              </div>
              <div className="space-y-2">
                <label className="text-sm font-black uppercase tracking-widest opacity-60 ml-2">
                  寵物種類<span className="text-error ml-1">*</span>
                </label>
                <div className="relative">
                  <select 
                    required
                    value={formData.species}
                    onChange={e => setFormData(prev => ({ ...prev, species: e.target.value }))}
                    className="w-full p-5 bg-surface-container-low border border-outline-variant/10 rounded-2xl text-sm font-bold outline-none focus:border-primary transition-colors appearance-none"
                  >
                    <option value="" disabled>請選擇</option>
                    <option value="DOG">犬 (Dog)</option>
                    <option value="CAT">貓 (Cat)</option>
                    <option value="HAMSTER">鼠 (Hamster)</option>
                    <option value="RABBIT">兔 (Rabbit)</option>
                    <option value="BIRD">鳥 (Bird)</option>
                    <option value="OTHER">其他 (Other)</option>
                  </select>
                  <span className="material-symbols-outlined absolute right-4 top-1/2 -translate-y-1/2 pointer-events-none opacity-30">expand_more</span>
                </div>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-6">
              <div className="space-y-2">
                <label className="text-sm font-black uppercase tracking-widest opacity-60 ml-2">
                  出生年<span className="text-error ml-1">*</span>
                </label>
                <div className="relative">
                  <select 
                    required
                    value={formData.birthYear}
                    onChange={e => setFormData(prev => ({ ...prev, birthYear: e.target.value }))}
                    className="w-full p-5 bg-surface-container-low border border-outline-variant/10 rounded-2xl text-sm font-bold outline-none focus:border-primary transition-colors appearance-none"
                  >
                    {years.map(y => <option key={y} value={y}>{y} 年</option>)}
                  </select>
                  <span className="material-symbols-outlined absolute right-4 top-1/2 -translate-y-1/2 pointer-events-none opacity-30">calendar_today</span>
                </div>
              </div>
              <div className="space-y-2">
                <label className="text-sm font-black uppercase tracking-widest opacity-60 ml-2">出生月</label>
                <div className="relative">
                  <select 
                    value={formData.birthMonth}
                    onChange={e => setFormData(prev => ({ ...prev, birthMonth: e.target.value }))}
                    className="w-full p-5 bg-surface-container-low border border-outline-variant/10 rounded-2xl text-sm font-bold outline-none focus:border-primary transition-colors appearance-none"
                  >
                    <option value="">月份 (選填)</option>
                    {months.map(m => <option key={m} value={m}>{m} 月</option>)}
                  </select>
                  <span className="material-symbols-outlined absolute right-4 top-1/2 -translate-y-1/2 pointer-events-none opacity-30">expand_more</span>
                </div>
              </div>
            </div>

            {/* Status Grid: 性別 + 結紮 */}
            <div className="grid grid-cols-2 gap-6">
              <div className="p-4 bg-surface-container-low/50 rounded-3xl space-y-3">
                <label className="text-sm font-black uppercase tracking-widest opacity-60 ml-1 block">
                   性別<span className="text-error ml-1">*</span>
                </label>
                <div className="flex gap-2">
                  {[
                    { value: 'MALE', label: '公' },
                    { value: 'FEMALE', label: '母' },
                    { value: 'UNKNOWN', label: '不清楚' }
                  ].map(opt => (
                    <button
                      key={opt.value}
                      type="button"
                      onClick={() => setFormData(prev => ({ ...prev, gender: opt.value }))}
                      className={`flex-1 py-4 rounded-2xl text-xs font-black transition-all ${
                        formData.gender === opt.value
                          ? 'bg-primary text-on-primary shadow-lg shadow-primary/20 scale-[1.02]'
                          : 'bg-surface-container text-on-surface-variant hover:bg-surface-container-high'
                      }`}
                    >
                      {opt.label}
                    </button>
                  ))}
                </div>
              </div>

              <div className="p-4 bg-surface-container-low/50 rounded-3xl space-y-3">
                <label className="text-sm font-black uppercase tracking-widest opacity-60 ml-1 block">
                   結紮<span className="text-error ml-1">*</span>
                </label>
                <div className="flex gap-2">
                  {HEALTH_OPTIONS.map(opt => (
                    <button
                      key={opt.value}
                      type="button"
                      onClick={() => setFormData(prev => ({ ...prev, neuteredStatus: opt.value }))}
                      className={`flex-1 py-4 rounded-2xl text-xs font-black transition-all ${
                        formData.neuteredStatus === opt.value
                          ? 'bg-primary text-on-primary shadow-lg shadow-primary/20 scale-[1.02]'
                          : 'bg-surface-container text-on-surface-variant hover:bg-surface-container-high'
                      }`}
                    >
                      {opt.label}
                    </button>
                  ))}
                </div>
              </div>
            </div>

            {/* Health Grid: 疫苗 + 驅蟲 */}
            <div className="grid grid-cols-2 gap-6">
              <div className="p-4 bg-surface-container-low/50 rounded-3xl space-y-3">
                <label className="text-sm font-black uppercase tracking-widest opacity-60 ml-1 block">
                   定期打疫苗<span className="text-error ml-1">*</span>
                </label>
                <div className="flex gap-2">
                  {HEALTH_OPTIONS.map(opt => (
                    <button
                      key={opt.value}
                      type="button"
                      onClick={() => setFormData(prev => ({ ...prev, vaccinationStatus: opt.value }))}
                      className={`flex-1 py-4 rounded-2xl text-xs font-black transition-all ${
                        formData.vaccinationStatus === opt.value
                          ? 'bg-primary text-on-primary shadow-lg shadow-primary/20 scale-[1.02]'
                          : 'bg-surface-container text-on-surface-variant hover:bg-surface-container-high'
                      }`}
                    >
                      {opt.label}
                    </button>
                  ))}
                </div>
              </div>

              <div className="p-4 bg-surface-container-low/50 rounded-3xl space-y-3">
                <label className="text-sm font-black uppercase tracking-widest opacity-60 ml-1 block">
                   定期點驅蟲藥<span className="text-error ml-1">*</span>
                </label>
                <div className="flex gap-2">
                  {HEALTH_OPTIONS.map(opt => (
                    <button
                      key={opt.value}
                      type="button"
                      onClick={() => setFormData(prev => ({ ...prev, dewormingStatus: opt.value }))}
                      className={`flex-1 py-4 rounded-2xl text-xs font-black transition-all ${
                        formData.dewormingStatus === opt.value
                          ? 'bg-primary text-on-primary shadow-lg shadow-primary/20 scale-[1.02]'
                          : 'bg-surface-container text-on-surface-variant hover:bg-surface-container-high'
                      }`}
                    >
                      {opt.label}
                    </button>
                  ))}
                </div>
              </div>
            </div>

            <div className="space-y-4">
              <label className="text-sm font-black uppercase tracking-widest opacity-60 ml-2">照護筆記 (飲食/用藥/性格)</label>
              <textarea 
                value={formData.personalityNotes}
                onChange={e => setFormData(prev => ({ ...prev, personalityNotes: e.target.value }))}
                className="w-full h-40 p-6 bg-surface-container-low border border-outline-variant/10 rounded-[40px] text-sm font-medium outline-none focus:border-primary transition-colors resize-none italic leading-relaxed"
                placeholder="例如：喜歡玩羽毛棒、對鮮蝦過敏..."
              />
            </div>
          </div>

          <div className="flex gap-4 pt-4">
            <button type="button" onClick={onClose} className="flex-1 py-6 bg-surface-container-high text-on-surface rounded-full text-sm font-black uppercase tracking-widest hover:bg-surface-container-highest transition-colors">取消</button>
            <button 
              disabled={isSubmitting || isUploading}
              type="submit" 
              className="flex-1 py-6 bg-on-surface text-surface rounded-full text-sm font-black uppercase tracking-widest shadow-2xl shadow-on-surface/20 hover:scale-[1.02] active:scale-95 transition-all disabled:opacity-50"
            >
              {isSubmitting ? '儲存中...' : (initialData ? '更新毛孩' : '儲存毛孩')}
            </button>
          </div>
        </form>
      </motion.div>
    </div>
  )
}

export default PetFormModal
