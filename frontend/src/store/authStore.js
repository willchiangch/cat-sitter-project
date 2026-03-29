import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'

export const useAuthStore = create(
  persist(
    (set) => ({
      user: null,
      token: null,
      isAuthenticated: false,
      
      setAuth: (user, token) => set({ 
        user, 
        token, 
        isAuthenticated: !!token 
      }),
      
      logout: () => set({ 
        user: null, 
        token: null, 
        isAuthenticated: false 
      }),
      
      updateUser: (user) => set((state) => ({ 
        user: { ...state.user, ...user } 
      })),
      
      setEmailVerified: (verified) => set((state) => ({
        user: state.user ? { ...state.user, emailVerified: verified } : null
      })),
    }),
    {
      name: 'whiskerwatch-auth-storage',
      storage: createJSONStorage(() => localStorage),
    }
  )
)
