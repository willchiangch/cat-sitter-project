import React, { useState } from 'react'
import { motion } from 'framer-motion'
import { petService, storageService } from '../../services/api'

const PetFormModal = ({ isOpen, onClose, initialData, onSave }) => {
  const [formData, setFormData] = useState(initialData || {
    name: '',
    species: 'CAT',
    gender: 'MALE',
    isNeutered: true,
    weightKg: 4.0,
    avatarUrl: '',
    medicalNotes: '',
    dietaryNotes: '',
    personalityNotes: '',
    otherNotes: ''
  })
  const [isUploading, setIsUploading] = useState(false)
  const [isSubmitting, setIsSubmitting] = useState(false)

  const handleFileChange = async (e) => {
    const file = e.target.files[0]
    if (!file) return

    try {
      setIsUploading(true)
      // Step 1: Upload to GCS
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
    try {
      setIsSubmitting(true)
      if (initialData?.id) {
        await petService.update(initialData.id, formData)
      } else {
        await petService.create(formData)
      }
      onSave() // Trigger refresh in parent
      onClose()
    } catch (error) {
      console.error('Save failed:', error)
      alert('儲存失敗，請檢查必填欄位。')
    } finally {
      setIsSubmitting(false)
    }
  }

  if (!isOpen) return null

  return (
    <div className="fixed inset-0 z-[100] flex items-end justify-center px-4 pb-10 sm:items-center">
      <motion.div 
        initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
        onClick={onClose} 
        className="absolute inset-0 bg-on-surface/40 backdrop-blur-sm" 
      />
      <motion.div 
        initial={{ y: '100%' }} animate={{ y: 0 }} exit={{ y: '100%' }}
        className="relative w-full max-w-lg bg-surface rounded-[40px] p-8 shadow-2xl space-y-8 max-h-[90vh] overflow-y-auto no-scrollbar"
      >
        <div className="space-y-1">
          <h3 className="text-3xl font-extrabold font-headline tracking-tighter">
            {initialData ? '編輯貓咪資料' : '新增貓咪至保險箱'}
          </h3>
          <p className="text-xs font-bold opacity-30 uppercase tracking-widest">Update your digital cat passport.</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-8">
          {/* Avatar Upload */}
          <div className="flex flex-col items-center gap-4">
            <div className="relative w-32 h-32 rounded-[40px] overflow-hidden border-4 border-surface shadow-2xl ring-1 ring-on-surface/5 group">
              {isUploading ? (
                <div className="absolute inset-0 bg-primary/20 flex items-center justify-center italic text-[10px] font-bold">Uploading...</div>
              ) : (
                <img src={formData.avatarUrl || 'https://via.placeholder.com/200?text=Upload'} className="w-full h-full object-cover" />
              )}
              <label className="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 flex items-center justify-center cursor-pointer transition-opacity">
                <span className="material-symbols-outlined text-white">photo_camera</span>
                <input type="file" className="hidden" accept="image/*" onChange={handleFileChange} />
              </label>
            </div>
            <p className="text-[10px] font-bold opacity-30 uppercase tracking-widest">點擊頭像上傳實體照片至 GCS</p>
          </div>

          <div className="space-y-6">
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <label className="text-[10px] font-bold uppercase tracking-widest opacity-40 ml-2">貓咪姓名</label>
                <input 
                  required
                  value={formData.name}
                  onChange={e => setFormData(prev => ({ ...prev, name: e.target.value }))}
                  className="w-full p-4 bg-surface-container-low border border-outline-variant/10 rounded-2xl text-sm font-bold outline-none focus:border-primary transition-colors" 
                  placeholder="Oliver" 
                />
              </div>
              <div className="space-y-2">
                <label className="text-[10px] font-bold uppercase tracking-widest opacity-40 ml-2">性別</label>
                <select 
                  value={formData.gender}
                  onChange={e => setFormData(prev => ({ ...prev, gender: e.target.value }))}
                  className="w-full p-4 bg-surface-container-low border border-outline-variant/10 rounded-2xl text-sm font-bold outline-none focus:border-primary transition-colors appearance-none"
                >
                  <option value="MALE">公 (Male)</option>
                  <option value="FEMALE">母 (Female)</option>
                </select>
              </div>
            </div>

            <div className="space-y-2">
              <label className="text-[10px] font-bold uppercase tracking-widest opacity-40 ml-2">照護筆記 (飲食/用藥/性格)</label>
              <textarea 
                value={formData.personalityNotes}
                onChange={e => setFormData(prev => ({ ...prev, personalityNotes: e.target.value }))}
                className="w-full h-32 p-5 bg-surface-container-low border border-outline-variant/10 rounded-[32px] text-sm font-medium outline-none focus:border-primary transition-colors resize-none italic"
                placeholder="例如：喜歡玩羽毛棒、對鮮蝦過敏..."
              />
            </div>
          </div>

          <div className="flex gap-4">
            <button type="button" onClick={onClose} className="flex-1 py-5 bg-surface-container-high text-on-surface rounded-full text-xs font-bold uppercase tracking-widest">Cancel</button>
            <button 
              disabled={isSubmitting || isUploading}
              type="submit" 
              className="flex-1 py-5 bg-on-surface text-surface rounded-full text-xs font-extrabold uppercase tracking-widest shadow-2xl shadow-on-surface/20 hover:scale-[1.02] active:scale-95 transition-all disabled:opacity-50"
            >
              {isSubmitting ? 'Saving...' : 'Save Pet'}
            </button>
          </div>
        </form>
      </motion.div>
    </div>
  )
}

export default PetFormModal
