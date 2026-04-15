import React from 'react'
import { authService, devService } from '../services/api'
import { useAuthStore } from '../store/authStore'

const DevAuthTools = () => {
  const { user, updateUser } = useAuthStore()
  if (!import.meta.env.DEV) return null
  
  const isSitter = user?.lastActiveRole === 'SITTER'

  const handleVerify = async (verified) => {
    try {
      await devService.verifySitter(verified)
      // Update local state and reload to see the blue checkmark
      updateUser({ isVerified: verified })
      window.location.reload()
    } catch (e) {
      console.error('Failed to update verification:', e)
      alert('更新失敗')
    }
  }

  const injectAuth = (type) => {
    // These IDs are aligned with SmokeMockAuthFilter.java
    const id = type === 'SOP' ? 'efefefef-0000-0000-0000-000000000001' : 'efefefef-0000-0000-0000-000000000002'
    localStorage.setItem('whiskerwatch-smoke-auth', type === 'SOP' ? 'SITTER' : 'CLIENT')
    localStorage.setItem('whiskerwatch-auth-storage', JSON.stringify({
      state: {
        user: { 
          id, 
          email: type === 'SOP' ? 'sophia@example.com' : 'james@example.com', 
          name: type === 'SOP' ? 'Sophia' : 'James',
          lastActiveRole: type === 'SOP' ? 'SITTER' : 'CLIENT',
          isVerified: type === 'SOP' // Sophia is verified by default in some mocks, but let's be explicit
        },
        token: 'dev-token',
        isAuthenticated: true
      }
    }))
    // Redirect to home/dashboard which will then handle route guarding
    window.location.href = '/'
  }

  const registerRandomClient = async () => {
    const timestamp = Date.now().toString().slice(-6)
    const email = `client_${timestamp}@test.com`
    const password = '1qaz@WSX'
    const displayName = `Test Client ${timestamp}`
    
    try {
      console.log('Dev Registering:', email)
      // Call register WITHOUT roleType to force onboarding flow
      const regRes = await authService.register({
        email,
        password,
        displayName,
        roleType: null // No role yet!
      })
      
      console.log('Dev Register Success:', regRes)
      
      // Clear injection flags and set the real session info
      localStorage.removeItem('whiskerwatch-smoke-auth')
      localStorage.setItem('whiskerwatch-auth-storage', JSON.stringify({
        state: {
          user: { id: 'temp', email, name: displayName, lastActiveRole: null },
          token: regRes.accessToken,
          isAuthenticated: true
        }
      }))
      // Redirect to onboarding specifically
      window.location.href = '/onboarding'
    } catch (e) {
      console.error('Dev Register Failed:', e)
      alert('註冊失敗：' + (e.response?.data?.message || e.message))
    }
  }

  return (
    <div className="fixed top-24 right-4 z-[999] flex flex-col gap-2 p-3 bg-navy/90 backdrop-blur rounded-2xl border border-white/10 shadow-2xl">
      <p className="text-[9px] font-black text-white/40 uppercase tracking-widest text-center">Dev Identity</p>
      <button onClick={() => injectAuth('SOP')} className="px-3 py-1.5 bg-primary text-white text-[10px] font-bold rounded-lg hover:bg-primary/80 transition-colors">Switch SOPHIA</button>
      <button onClick={() => injectAuth('JAM')} className="px-3 py-1.5 bg-[#f59e0b] text-white text-[10px] font-bold rounded-lg hover:bg-[#f59e0b]/80 transition-colors">Switch JAMES</button>
      
      {isSitter && (
        <>
          <div className="h-px bg-white/10 my-1" />
          <p className="text-[8px] font-bold text-white/30 uppercase text-center">Identity Override</p>
          <button onClick={() => handleVerify(true)} className="px-3 py-1.5 bg-blue-600 text-white text-[10px] font-bold rounded-lg hover:bg-blue-500 transition-colors flex items-center justify-center gap-1">
            <span className="text-[12px]">✅</span> 認證通過
          </button>
          <button onClick={() => handleVerify(false)} className="px-3 py-1.5 bg-red-600/50 text-white text-[10px] font-bold rounded-lg hover:bg-red-500 transition-colors flex items-center justify-center gap-1">
            <span className="text-[12px]">❌</span> 取消認證
          </button>
        </>
      )}

      <div className="h-px bg-white/10 my-1" />
      <button onClick={registerRandomClient} className="px-3 py-1.5 bg-green-600 text-white text-[10px] font-bold rounded-lg hover:bg-green-500 transition-colors">
        + Register New User
        <br/><span className="opacity-50">(Flow: Onboarding)</span>
      </button>
    </div>
  )
}

export default DevAuthTools
