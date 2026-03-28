import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export const useThemeStore = create(
  persist(
    (set) => ({
      mode: 'SITTER', // Default mode
      toggleMode: () => set((state) => ({ 
        mode: state.mode === 'SITTER' ? 'CLIENT' : 'SITTER' 
      })),
      setMode: (mode) => set({ mode }),
    }),
    {
      name: 'whiskerwatch-theme-storage',
    }
  )
)
