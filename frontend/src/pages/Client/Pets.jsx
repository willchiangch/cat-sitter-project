import React, { useState, useEffect } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { petService } from '../../services/api'
import PetFormModal from '../../components/client/PetFormModal'
import { calculateAge } from '../../utils/dateUtils'

// scroll to top on mount
const useScrollToTop = () => useEffect(() => { document.querySelector('main')?.scrollTo({ top: 0, behavior: 'instant' }) }, [])

const Pets = () => {
  const [pets, setPets] = useState([])
  const [isLoading, setIsLoading] = useState(true)
  const [isModalOpen, setIsModalOpen] = useState(false)
  const [editingPet, setEditingPet] = useState(null)
  const navigate = useNavigate()
  const genderMap = { 'MALE': '公', 'FEMALE': '母', 'UNKNOWN': '不詳' }
  useScrollToTop()

  useEffect(() => {
    fetchPets()
  }, [])

  const fetchPets = async () => {
    try {
      setIsLoading(true)
      const data = await petService.list()
      setPets(data)
    } catch (error) {
      console.error('Failed to fetch pets:', error)
    } finally {
      setIsLoading(false)
    }
  }

  const handleDelete = async (id, e) => {
    e.stopPropagation()
    if (window.confirm('確定要移除這隻貓咪嗎？這將無法復原。')) {
      try {
        await petService.delete(id)
        setPets(prev => prev.filter(p => p.petId !== id))
      } catch (error) {
        console.error('Delete failed:', error)
      }
    }
  }

  const handleEdit = (pet, e) => {
    e.stopPropagation()
    setEditingPet(pet)
    setIsModalOpen(true)
  }

  return (
    <div className="min-h-screen bg-surface text-on-surface pb-32">
      <header className="px-6 pt-12 pb-8">
        <div className="flex items-center gap-2 mb-4">
          <button onClick={() => navigate(-1)} className="p-2 -ml-2 text-on-surface-variant hover:text-primary transition-colors">
            <span className="material-symbols-outlined text-2xl">arrow_back</span>
          </button>
        </div>
        <div className="flex justify-between items-end">
          <div className="space-y-1">
            <h1 className="text-4xl font-extrabold font-headline tracking-tighter">我的毛孩</h1>
            <p className="text-xs font-bold opacity-30 uppercase tracking-[0.2em]">My Pets</p>
          </div>
          <button
            onClick={() => { setEditingPet(null); setIsModalOpen(true); }}
            className="w-12 h-12 bg-primary text-on-primary rounded-2xl flex items-center justify-center shadow-xl shadow-primary/20 active:scale-90 transition-transform"
          >
            <span className="material-symbols-outlined">add</span>
          </button>
        </div>
      </header>

      <main className="px-6 space-y-6">
        {isLoading ? (
          <div className="py-20 text-center opacity-20 italic">Loading your vault...</div>
        ) : pets.length === 0 ? (
          <div className="py-20 text-center space-y-4 opacity-40">
            <span className="material-symbols-outlined text-6xl">pets</span>
            <p className="text-sm font-black uppercase tracking-widest text-on-surface-variant/60">您的保險箱目前空空如也</p>
          </div>
        ) : (
          <div className="grid grid-cols-1 gap-6">
            {pets.map(pet => (
              <motion.div 
                key={pet.petId}
                layoutId={pet.petId}
                onClick={() => navigate(`/client/cat-passport/${pet.petId}`)}
                className="group relative bg-surface-container-low rounded-[40px] p-6 border border-outline-variant/10 flex items-center gap-6 hover:bg-surface-container transition-colors cursor-pointer"
              >
                <div className="w-24 h-24 rounded-[32px] overflow-hidden shadow-2xl ring-4 ring-surface">
                  <img src={pet.avatarUrl || 'https://placehold.jp/24/336699/ffffff/200x200.png?text=Cat'} className="w-full h-full object-cover" />
                </div>
                
                <div className="flex-1 space-y-1">
                  <h3 className="text-2xl font-extrabold font-headline tracking-tighter">{pet.name}</h3>
                  <div className="flex gap-3">
                    <span className="text-xs font-black opacity-40 uppercase tracking-widest">{pet.species}</span>
                    <span className="text-xs font-black opacity-30 uppercase tracking-widest">•</span>
                    <span className="text-xs font-black opacity-40 uppercase tracking-widest">{genderMap[pet.gender] || pet.gender}</span>
                    <span className="text-xs font-black opacity-30 uppercase tracking-widest">•</span>
                    <span className="text-xs font-black opacity-40 uppercase tracking-widest">{calculateAge(pet.birthDate) || '年齡不詳'}</span>
                  </div>
                </div>

                <div className="flex flex-col gap-2">
                  <button onClick={(e) => handleEdit(pet, e)} className="p-2 text-on-surface-variant/40 hover:text-primary transition-colors">
                    <span className="material-symbols-outlined text-xl">edit</span>
                  </button>
                  <button onClick={(e) => handleDelete(pet.petId, e)} className="p-2 text-on-surface-variant/40 hover:text-error transition-colors">
                    <span className="material-symbols-outlined text-xl">delete</span>
                  </button>
                </div>
              </motion.div>
            ))}
          </div>
        )}
      </main>

      <AnimatePresence>
        {isModalOpen && (
          <PetFormModal 
            isOpen={isModalOpen}
            onClose={() => setIsModalOpen(false)}
            initialData={editingPet}
            onSave={fetchPets}
          />
        )}
      </AnimatePresence>
    </div>
  )
}

export default Pets
