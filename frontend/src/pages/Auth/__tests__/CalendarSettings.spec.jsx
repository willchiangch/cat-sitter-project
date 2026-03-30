import React from 'react'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import Profile from '../Profile'
import { profileService, calendarService } from '../../../services/api'
import { useAuthStore } from '../../../store/authStore'

// Mock services
vi.mock('../../../services/api', () => ({
  profileService: { 
    getSitterMe: vi.fn(), 
    updateSitterMe: vi.fn() 
  },
  storageService: { uploadFile: vi.fn() },
  calendarService: {
    getStatus: vi.fn(),
    getAuthUrl: vi.fn(),
    disconnect: vi.fn(),
    resetToken: vi.fn(),
  }
}))

// Mock Zustand store
vi.mock('../../../store/authStore', () => ({
  useAuthStore: vi.fn()
}))

// Mock translations
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key })
}))

// Mock Framer Motion to prevent async animation issues
vi.mock('framer-motion', () => ({
    motion: {
        main: ({ children, className }) => <main className={className}>{children}</main>,
        div: ({ children, className }) => <div className={className}>{children}</div>
    },
    AnimatePresence: ({ children }) => <>{children}</>
}))

describe('Profile - Calendar Settings (Vitest RTL)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.confirm = vi.fn().mockReturnValue(true) // Auto-confirm prompts
    Object.assign(navigator, {
      clipboard: {
        writeText: vi.fn()
      }
    })

    // Default User Store Mock to Sitter Role
    useAuthStore.mockImplementation((selector) => {
      return selector({ 
        user: { role: 'ROLE_SITTER', name: 'James', email: 'james@example.com' },
        logout: vi.fn()
      })
    })

    // Setup base happy path responses for component mount
    profileService.getSitterMe.mockResolvedValue({ 
      isVerified: true, 
      professionalLabels: [] 
    })
  })

  it('should render correct [Not Connected] state and handle OAuth redirect', async () => {
    calendarService.getStatus.mockResolvedValue({ linked: false, feedUrl: null })

    render(<Profile />)

    await waitFor(() => {
      expect(screen.getByText('未連結')).toBeInTheDocument()
    })

    // Mock window location for OAuth redirect
    const originalLocation = window.location
    delete window.location
    window.location = { href: '' }
    
    calendarService.getAuthUrl.mockResolvedValue({ url: 'https://google.com/oauth' })
    
    // Action: click the connection setting item
    fireEvent.click(screen.getByText('未連結'))
    
    await waitFor(() => {
      expect(calendarService.getAuthUrl).toHaveBeenCalledTimes(1)
      expect(window.location.href).toBe('https://google.com/oauth')
    })

    // Restore window.location
    window.location = originalLocation
  })

  it('should show [Connected] state and allow iCal copying & token reset', async () => {
    calendarService.getStatus.mockResolvedValue({ linked: true, feedUrl: '/api/v1/sitter/calendar/feed/123' })
    calendarService.resetToken.mockResolvedValue({ feedUrl: '/api/v1/sitter/calendar/feed/new-token' })

    render(<Profile />)

    await waitFor(() => {
      expect(screen.getByText('服務同步中')).toBeInTheDocument()
    })
    
    expect(screen.getByText('點擊複製網址')).toBeInTheDocument()

    // Test: Copy to clipboard
    window.alert = vi.fn()
    fireEvent.click(screen.getByText('點擊複製網址'))
    
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(expect.stringContaining('/api/v1/sitter/calendar/feed/123'))
    expect(window.alert).toHaveBeenCalledWith('已複製 Ical 網址')

    // Test: Reset Token
    const resetButton = screen.getByText('重置訂閱 Token')
    fireEvent.click(resetButton)

    await waitFor(() => {
      expect(calendarService.resetToken).toHaveBeenCalledTimes(1)
    })
  })

  it('should accurately process the disconnect calendar flow', async () => {
    calendarService.getStatus.mockResolvedValueOnce({ linked: true, feedUrl: '/api/v1/sitter/calendar/feed/456' })
    calendarService.getStatus.mockResolvedValueOnce({ linked: false, feedUrl: null }) // Mount #2 after discount
    
    render(<Profile />)

    await waitFor(() => {
      expect(screen.getByText('服務同步中')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByText('服務同步中'))

    expect(window.confirm).toHaveBeenCalledWith('確定要斷開 Google 行事曆連結嗎？')
    
    await waitFor(() => {
      expect(calendarService.disconnect).toHaveBeenCalledTimes(1)
      expect(calendarService.getStatus).toHaveBeenCalledTimes(2) // 1st init, 2nd after flush
    })
  })
})
