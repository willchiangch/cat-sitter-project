import { create } from 'zustand'

export const useNotificationStore = create((set, get) => ({
  notifications: [
    {
      id: 'n1',
      type: 'SERVICE',
      role: 'CLIENT',
      title: '照片已上傳',
      message: '保母 Sophia 剛剛上傳了 Miso 的 3 張新照片。',
      time: '10:42 AM',
      read: false,
      link: '/client/service-log/LATEST'
    },
    {
      id: 'n2',
      type: 'ORDER',
      role: 'SITTER',
      title: '新預約申請',
      message: '您收到一筆來自 James 的春節假期預約。',
      time: '昨天',
      read: true,
      link: '/sitter/orders'
    },
    {
      id: 'n3',
      type: 'SYSTEM',
      role: 'ALL',
      title: '帳號安全性提醒',
      message: '您剛剛在新的設備上登入了 WhiskerWatch。',
      time: '昨天',
      read: true,
      link: '/profile'
    }
  ],

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
