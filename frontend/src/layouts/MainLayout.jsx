import React from 'react'
import { Outlet } from 'react-router-dom'
import { useThemeStore } from '../store/themeStore'
import TopAppBar from '../components/shared/TopAppBar'
import BottomNavBar from '../components/shared/BottomNavBar'
import CommunicationVerify from '../components/auth/CommunicationVerify'

const MainLayout = () => {
  const { mode } = useThemeStore()
  
  const modeClass = mode === 'SITTER' ? 'mode-sitter' : 'mode-client'

  return (
    <div className={`${modeClass} min-h-screen bg-surface text-on-surface`}>
      <div className="max-w-md mx-auto h-screen relative bg-surface overflow-hidden flex flex-col shadow-2xl">
        <TopAppBar />
        <CommunicationVerify />
        
        <main className="flex-1 overflow-y-auto no-scrollbar pt-0 pb-24 px-4 bg-surface">
          <Outlet />
        </main>
        
        <BottomNavBar />
      </div>
    </div>
  )
}

export default MainLayout
