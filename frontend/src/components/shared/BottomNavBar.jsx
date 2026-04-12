import React from 'react'
import { NavLink } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useThemeStore } from '../../store/themeStore'
import { useNotificationStore } from '../../store/notificationStore'

const BottomNavBar = () => {
  const { t } = useTranslation()
  const { mode } = useThemeStore()
  const { getUnreadCountForRole } = useNotificationStore()
  const unreadCount = getUnreadCountForRole(mode)
  
  const navItems = mode === 'SITTER' ? [
    { icon: 'dashboard', label: t('common.tab_itinerary'), path: '/sitter', end: true },
    { icon: 'receipt_long', label: t('common.tab_orders'), path: '/sitter/orders' },
    { icon: 'payments', label: t('common.tab_finance'), path: '/sitter/finance' },
    { icon: 'notifications', label: t('common.tab_notifications'), path: '/notifications', badge: true },
    { icon: 'person', label: t('common.tab_profile'), path: '/profile' },
  ] : [
    { icon: 'dashboard', label: t('common.tab_itinerary'), path: '/client', end: true },
    { icon: 'pets', label: t('common.tab_orders'), path: '/client/orders' },
    { icon: 'search', label: t('common.tab_sitters'), path: '/client/sitters' },
    { icon: 'notifications', label: t('common.tab_notifications'), path: '/notifications' },
    { icon: 'person', label: t('common.tab_profile'), path: '/profile' },
  ]

  return (
    <nav className="fixed bottom-0 w-full z-50 rounded-t-[2rem] bg-[#1e3a8a] shadow-[0_-8px_30px_rgba(30,58,138,0.15)] border-t border-white/10 h-16 flex justify-around items-center max-w-md mx-auto left-0 right-0">
      {navItems.map((item) => (
        <NavLink
          key={item.path}
          to={item.path}
          end={item.end}
          className="flex flex-col items-center justify-center px-3 py-1.5 transition-all duration-200 active:scale-95 relative"
        >
          {({ isActive }) => (
            <>
              <div className={`relative flex items-center justify-center w-10 h-7 rounded-full transition-all duration-300 ${
                isActive
                  ? 'text-[#f59e0b] drop-shadow-[0_0_8px_rgba(245,158,11,0.6)]'
                  : 'text-white/60'
              }`}>
                <span className="material-symbols-outlined text-2xl">{item.icon}</span>
              </div>
              <span className={`font-body text-[10px] font-bold uppercase tracking-widest mt-1 transition-colors duration-300 ${
                isActive ? 'text-[#f59e0b]' : 'text-white/50'
              }`}>
                {item.label}
              </span>
              {item.icon === 'notifications' && unreadCount > 0 && (
                <div className="absolute top-1 right-2 w-4 h-4 bg-[#f59e0b] text-[#1e3a8a] text-[8px] font-bold rounded-full flex items-center justify-center border-2 border-[#1e3a8a] animate-bounce shadow-lg">
                  {unreadCount}
                </div>
              )}
            </>
          )}
        </NavLink>
      ))}
    </nav>
  )
}

export default BottomNavBar
