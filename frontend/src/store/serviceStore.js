import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { visitService, storageService } from '../services/api'

export const useServiceStore = create(
  persist(
    (set, get) => ({
      activeVisit: null, // Full VisitDetailResponse
      isLoading: false,
      isUploading: false,
      isFinished: false,

      // Initialize or refresh the visit session from backend
      initVisit: async (id) => {
        try {
          set({ isLoading: true })
          const data = await visitService.getDetail(id)
          set({ activeVisit: data, isFinished: data.status === 'DONE' })
        } catch (error) {
          console.error('Failed to init visit:', error)
        } finally {
          set({ isLoading: false })
        }
      },

      setUploading: (status) => set({ isUploading: status }),

      // Sync checkbox with backend
      toggleTask: async (itemId, currentStatus) => {
        const { activeVisit } = get()
        if (!activeVisit) return
        try {
          const updated = await visitService.updateChecklist(activeVisit.id, {
            itemId: itemId,
            isCompleted: !currentStatus
          })
          set({ activeVisit: updated })
        } catch (error) {
          console.error('Task toggle failed:', error)
        }
      },

      // Integrated GCS upload + Moment registration
      uploadMoment: async (file, caption) => {
        const { activeVisit } = get()
        if (!activeVisit) return
        try {
          set({ isUploading: true })
          // 1. Get Signed URL and upload directly to GCS
          const gcsUrl = await storageService.uploadFile(file)
          
          // 2. Register this moment in backend
          const updated = await visitService.addMedia(activeVisit.id, {
            mediaUrl: gcsUrl,
            caption: caption,
            mediaType: 'IMAGE'
          })
          set({ activeVisit: updated })
        } catch (error) {
          console.error('Moment upload failed:', error)
        } finally {
          set({ isUploading: false })
        }
      },

      updateReport: (text) => set((state) => ({ 
        activeVisit: { ...state.activeVisit, sitterNotes: text } 
      })),

      finishService: async () => {
        const { activeVisit } = get()
        if (!activeVisit) return
        try {
          set({ isLoading: true })
          const updated = await visitService.complete(activeVisit.id)
          set({ activeVisit: updated, isFinished: true })
        } catch (e) {
          console.error('Finish service failed:', e)
        } finally {
          set({ isLoading: false })
        }
      },
      
      resetService: () => set({ 
        activeVisit: null, 
        isFinished: false,
        isUploading: false,
        isLoading: false
      })
    }),
    {
      name: 'whiskerwatch-service-storage',
    }
  )
)
