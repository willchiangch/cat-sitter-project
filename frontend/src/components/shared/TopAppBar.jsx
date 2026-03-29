import React from 'react'
import { useTranslation } from 'react-i18next'
import { useThemeStore } from '../../store/themeStore'

const TopAppBar = () => {
  const { t } = useTranslation()
  const { mode, toggleMode } = useThemeStore()

  return (
    <header className="w-full z-50 glass-effect border-b border-outline-variant/10">
      <div className="flex justify-between items-center px-6 h-16 w-full">
        <div className="flex items-center gap-2">
          <span className={`material-symbols-outlined text-2xl ${mode === 'SITTER' ? 'text-primary' : 'text-secondary'}`}>
            pets
          </span>
          <h1 className={`text-xl font-extrabold font-headline tracking-tighter ${mode === 'SITTER' ? 'text-primary' : 'text-secondary'}`}>
            {t('common.app_name')}
          </h1>
        </div>
        
        <button 
          onClick={toggleMode}
          className="bg-surface-container-low px-4 py-1.5 rounded-full border border-outline-variant/20 active:scale-95 transition-all duration-200"
        >
          <span className={`text-xs font-bold ${mode === 'SITTER' ? 'text-primary' : 'text-secondary'}`}>
            {mode === 'SITTER' ? t('common.switch_to_client') : t('common.switch_to_sitter')}
          </span>
        </button>
      </div>
    </header>
  )
}

export default TopAppBar
