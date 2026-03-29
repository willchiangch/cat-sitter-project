import React, { useState } from 'react'
import { motion, AnimatePresence } from 'framer-motion'

const QuoteModal = ({ isOpen, onClose, baseAmount, onConfirm }) => {
  const [surcharge, setSurcharge] = useState(0)
  const [discount, setDiscount] = useState(0)
  const [notes, setNotes] = useState('')

  const total = Number(baseAmount) + Number(surcharge) - Number(discount)

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
        className="relative w-full max-w-lg bg-surface rounded-[40px] p-8 shadow-2xl space-y-8"
      >
        <div className="space-y-2">
          <h3 className="text-2xl font-extrabold font-headline tracking-tighter">專業報價與調價</h3>
          <p className="text-xs font-medium opacity-40 uppercase tracking-widest">Adjust pricing based on care complexity.</p>
        </div>

        <div className="space-y-6">
          <div className="flex justify-between items-center px-1">
             <span className="text-xs font-bold opacity-40 uppercase">Base Service Fee</span>
             <span className="text-lg font-extrabold font-headline">${baseAmount}</span>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <label className="text-[10px] font-bold uppercase tracking-widest opacity-40 ml-2">Surcharge (+)</label>
              <input 
                type="number" 
                value={surcharge}
                onChange={(e) => setSurcharge(e.target.value)}
                className="w-full p-4 bg-surface-container-low border border-outline-variant/10 rounded-2xl text-base font-bold outline-none focus:border-primary transition-colors text-primary"
              />
            </div>
            <div className="space-y-2">
              <label className="text-[10px] font-bold uppercase tracking-widest opacity-40 ml-2">Discount (-)</label>
              <input 
                type="number" 
                value={discount}
                onChange={(e) => setDiscount(e.target.value)}
                className="w-full p-4 bg-surface-container-low border border-outline-variant/10 rounded-2xl text-base font-bold outline-none focus:border-error transition-colors text-error"
              />
            </div>
          </div>

          <textarea 
            placeholder="調價原因標記 (例如：春節加成 / 遠距交通費...)"
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            className="w-full h-24 p-4 bg-surface-container-low border border-outline-variant/10 rounded-2xl text-xs font-medium outline-none focus:border-primary transition-colors resize-none"
          />

          <div className="bg-on-surface text-surface p-6 rounded-[32px] flex justify-between items-center">
            <span className="text-xs font-bold uppercase tracking-widest opacity-40">Final Quote</span>
            <span className="text-3xl font-extrabold font-headline">${total}</span>
          </div>
        </div>

        <div className="flex gap-3">
          <button onClick={onClose} className="flex-1 py-4 bg-surface-container-high text-on-surface rounded-full text-sm font-extrabold uppercase transition-all">Cancel</button>
          <button 
            onClick={() => onConfirm({ baseAmount, surcharge, discount, notes })}
            className="flex-1 py-4 bg-primary text-on-primary rounded-full text-sm font-extrabold uppercase shadow-lg shadow-primary/20 hover:scale-[1.02] active:scale-95 transition-all"
          >
            Confirm & Send
          </button>
        </div>
      </motion.div>
    </div>
  )
}

const ReferralModal = ({ isOpen, onClose, partners, onConfirm }) => {
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
        className="relative w-full max-w-lg bg-surface rounded-[40px] p-8 shadow-2xl space-y-8"
      >
        <div className="space-y-2">
          <h3 className="text-2xl font-extrabold font-headline tracking-tighter">轉介信任夥伴</h3>
          <p className="text-xs font-medium opacity-40 uppercase tracking-widest">Connect your client with a trusted peer.</p>
        </div>

        <div className="space-y-3 max-h-96 overflow-y-auto no-scrollbar">
          {partners.map(p => (
            <button 
              key={p.id}
              onClick={() => onConfirm(p)}
              className="w-full p-4 bg-surface-container-low border border-outline-variant/10 rounded-[24px] flex items-center gap-4 hover:border-primary/20 transition-all group"
            >
              <div className="w-12 h-12 rounded-xl overflow-hidden">
                <img src={p.avatar} className="w-full h-full object-cover" />
              </div>
              <div className="text-left flex-1">
                <h4 className="text-sm font-extrabold">{p.name}</h4>
                <p className="text-[10px] font-bold opacity-30 uppercase tracking-widest">{p.location}</p>
              </div>
              <span className="material-symbols-outlined text-on-surface-variant/20 group-hover:text-primary transition-colors">send</span>
            </button>
          ))}
        </div>

        <button onClick={onClose} className="w-full py-4 bg-surface-container-high text-on-surface rounded-full text-sm font-extrabold uppercase">Close</button>
      </motion.div>
    </div>
  )
}

export { QuoteModal, ReferralModal }
