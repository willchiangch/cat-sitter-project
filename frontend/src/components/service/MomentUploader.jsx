import React, { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { motion, AnimatePresence } from 'framer-motion'
import { useServiceStore } from '../../store/serviceStore'

const MomentUploader = () => {
  const { t } = useTranslation()
  const { uploadMoment, isUploading } = useServiceStore()
  const [caption, setCaption] = useState('')
  const [selectedFile, setSelectedFile] = useState(null)
  const [preview, setPreview] = useState(null)

  const handleFileChange = (e) => {
    const file = e.target.files[0]
    if (file) {
      setSelectedFile(file)
      setPreview(URL.createObjectURL(file))
    }
  }

  const handleUpload = async () => {
    if (!selectedFile) return
    await uploadMoment(selectedFile, caption)
    // Reset after success
    setSelectedFile(null)
    setPreview(null)
    setCaption('')
  }

  return (
    <section className="bg-surface-container-low p-6 rounded-[32px] border border-outline-variant/10 space-y-4">
      <div className="flex items-center justify-between">
        <h3 className="text-[10px] font-bold tracking-[0.3em] text-on-surface-variant/40 uppercase">Capture Moment</h3>
        <span className="material-symbols-outlined text-sm opacity-20">photo_camera</span>
      </div>

      {!preview ? (
        <label className="flex flex-col items-center justify-center aspect-[16/9] border-2 border-dashed border-outline-variant/30 rounded-2xl cursor-pointer hover:bg-surface-container-high transition-colors text-on-surface-variant/40">
           <span className="material-symbols-outlined text-4xl mb-2">add_a_photo</span>
           <span className="text-[10px] font-bold uppercase tracking-widest leading-loose">Tap to Share Live Look</span>
           <input type="file" accept="image/*" onChange={handleFileChange} className="hidden" />
        </label>
      ) : (
        <div className="space-y-4">
           <div className="relative aspect-[16/9] rounded-2xl overflow-hidden shadow-xl">
              <img src={preview} className="w-full h-full object-cover" />
              <button 
                onClick={() => {setPreview(null); setSelectedFile(null)}}
                className="absolute top-3 right-3 w-8 h-8 bg-black/50 backdrop-blur-md rounded-full text-white flex items-center justify-center"
              >
                <span className="material-symbols-outlined text-sm">close</span>
              </button>
           </div>
           
           <div className="space-y-2">
              <input 
                type="text"
                placeholder="Write a brief caption..."
                value={caption}
                onChange={(e) => setCaption(e.target.value)}
                className="w-full px-5 py-4 bg-surface-container rounded-2xl text-xs font-medium focus:outline-none focus:ring-1 focus:ring-primary/20 placeholder:opacity-30"
              />
              <button 
                onClick={handleUpload}
                disabled={isUploading}
                className="w-full py-4 bg-primary text-on-primary rounded-2xl text-[10px] font-extrabold uppercase tracking-widest shadow-lg shadow-primary/20 disabled:opacity-20 flex items-center justify-center gap-2"
              >
                {isUploading ? (
                  <span className="animate-spin material-symbols-outlined text-sm">progress_activity</span>
                ) : (
                  <span className="material-symbols-outlined text-sm">send</span>
                )}
                {isUploading ? 'Sending...' : 'Post Moment'}
              </button>
           </div>
        </div>
      )}
    </section>
  )
}

export default MomentUploader
