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
    { icon: 'dashboard', label: t('common.tab_itinerary'), path: '/sitter' },
    { icon: 'receipt_long', label: t('common.tab_orders'), path: '/sitter/orders' },
    { icon: 'payments', label: t('common.tab_finance'), path: '/sitter/finance' },
    { icon: 'notifications', label: t('common.tab_notifications'), path: '/notifications', badge: true },
    { icon: 'person', label: t('common.tab_profile'), path: '/profile' },
  ] : [
    { icon: 'dashboard', label: t('common.tab_itinerary'), path: '/client' },
    { icon: 'pets', label: t('common.tab_orders'), path: '/client/orders' },
    { icon: 'search', label: t('common.tab_sitters'), path: '/client/sitters' },
    { icon: 'notifications', label: t('common.tab_notifications'), path: '/notifications' },
    { icon: 'person', label: t('common.tab_profile'), path: '/profile' },
  ]

  return (
    <nav className="fixed bottom-0 w-full z-50 rounded-t-[3rem] glass-effect shadow-top border-t border-outline-variant/10 h-24 pb-6 flex justify-around items-center max-w-md mx-auto left-0 right-0">
      {navItems.map((item) => (
        <NavLink
          key={item.path}
          to={item.path}
          className="flex flex-col items-center justify-center px-3 py-1.5 transition-all duration-200 active:scale-95 relative"
        >
          {({ isActive }) => (
            <>
              <div className={`relative flex items-center justify-center w-10 h-7 rounded-full transition-all duration-300 ${
                isActive
                  ? 'text-[#c69a00] drop-shadow-[0_0_8px_rgba(118,86,0,0.7)]'
                  : 'text-white/80'
              }`}>
                <span className="material-symbols-outlined text-2xl">{item.icon}</span>
              </div>
              <span className={`font-body text-[10px] font-bold uppercase tracking-widest mt-1 transition-colors duration-300 ${
                isActive ? 'text-[#c69a00]' : 'text-white/70'
              }`}>
                {item.label}
              </span>
              {item.icon === 'notifications' && unreadCount > 0 && (
                <div className="absolute top-1 right-2 w-4 h-4 bg-primary text-on-primary text-[8px] font-bold rounded-full flex items-center justify-center border-2 border-surface animate-bounce shadow-lg">
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
