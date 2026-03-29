import { create } from 'zustand'

export const useNotificationStore = create((set, get) => ({
  notifications: [
    { 
      id: 'n1', 
      type: 'SERVICE', 
      title: '照片已上傳', 
      message: '保母 Sophia 剛剛上傳了 Miso 的 3 張新照片。', 
      time: '10:42 AM',
      read: false,
      link: '/client/service-log/LATEST'
    },
    { 
      id: 'n2', 
      type: 'ORDER', 
      title: '新預約申請', 
      message: '您收到一筆來自 James 的春節假期預約。', 
      time: 'Yesterday',
      read: true,
      link: '/sitter/orders'
    },
    { 
      id: 'n3', 
      type: 'SYSTEM', 
      title: '帳號安全性提醒', 
      message: '您剛剛在新的設備上登入了 WhiskerWatch。', 
      time: 'Yesterday',
      read: true,
      link: '/profile'
    }
  ],
  
  getUnreadCount: () => {
    return get().notifications.filter(n => !n.read).length
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
