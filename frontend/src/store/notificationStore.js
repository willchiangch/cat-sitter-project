import { create } from 'zustand'

// Allow E2E tests to pre-seed notifications via window.__SMOKE_NOTIFICATIONS__
const initialNotifications =
  (typeof window !== 'undefined' && window.__SMOKE_NOTIFICATIONS__) || []

export const useNotificationStore = create((set, get) => ({
  notifications: initialNotifications,

  getUnreadCount: () => {
    return get().notifications.filter(n => !n.read).length
  },

  getNotificationsForRole: (role) => {
    return get().notifications.filter(n => n.role === 'ALL' || n.role === role)
  },

  getUnreadCountForRole: (role) => {
    return get().notifications.filter(n => (n.role === 'ALL' || n.role === role) && !n.read).length
  },
  
  markAsRead: (id) => set((state) => ({
    notifications: state.notifications.map(n => 
      n.id === id ? { ...n, read: true } : n
    )
  })),
  
  markAllAsRead: () => set((state) => ({
    notifications: state.notifications.map(n => ({ ...n, read: true }))
  })),
  
  addNotification: (notification) => set((state) => ({
    notifications: [
      { id: Date.now().toString(), read: false, time: 'Just now', ...notification },
      ...state.notifications
    ]
  }))
}))
