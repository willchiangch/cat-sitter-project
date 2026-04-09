import React from 'react'
import { authService } from '../services/api'
import { useAuthStore } from '../store/authStore'

const DevAuthTools = () => {
  if (!import.meta.env.DEV) return null
  
  const injectAuth = (type) => {
    // These IDs are aligned with SmokeMockAuthFilter.java
    const id = type === 'SOP' ? '00000000-0000-0000-0000-000000000001' : '00000000-0000-0000-0000-000000000002'
    localStorage.setItem('whiskerwatch-smoke-auth', type === 'SOP' ? 'SITTER' : 'CLIENT')
    localStorage.setItem('whiskerwatch-auth-storage', JSON.stringify({
      state: {
        user: { 
          id, 
          email: type === 'SOP' ? 'sophia@example.com' : 'james@example.com', 
          name: type === 'SOP' ? 'Sophia' : 'James',
          lastActiveRole: type === 'SOP' ? 'SITTER' : 'CLIENT'
        },
        token: 'dev-token',
        isAuthenticated: true
      }
    }))
    window.location.reload()
  }

  const registerRandomClient = async () => {
    const timestamp = Date.now().toString().slice(-6)
    const email = `client_${timestamp}@test.com`
    const password = '1qaz@WSX'
    const displayName = `Test Client ${timestamp}`
    
    try {
      console.log('Dev Registering:', email)
      const res = await authService.completeOnboarding({
        email,
        password,
        displayName,
        roleType: 'CLIENT'
      })
      // The register/completeOnboarding logic in AuthService returns token or response
      // But wait, the standard register flow is better here.
      // Let's use the register endpoint directly via axios mock or api.
      
      // I'll call the standard axios instance to be safe
      const regRes = await authService.register({
        email,
        password,
        displayName,
        roleType: 'CLIENT'
      })
      
      // Save to store and reload
      // The regRes usually contains { accessToken, refreshToken, expiresIn }
      // We need to fetch 'me' to get the user object
      // But for simplicity in DevTools, we can just trigger login with the new credentials
      // OR manually set the storage. Let's do a real register + reload to the login page with prefill OR auto inject.
      
      // Best way: Register and then inject the new info
      localStorage.removeItem('whiskerwatch-smoke-auth') // Ensure we use real token
      localStorage.setItem('whiskerwatch-auth-storage', JSON.stringify({
        state: {
          user: { id: 'temp', email, name: displayName, lastActiveRole: 'CLIENT' },
          token: regRes.accessToken,
          isAuthenticated: true
        }
      }))
      window.location.href = '/profile'
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
      <div className="h-px bg-white/10 my-1" />
      <button onClick={registerRandomClient} className="px-3 py-1.5 bg-green-600 text-white text-[10px] font-bold rounded-lg hover:bg-green-500 transition-colors">
        + New Client User
        <br/><span className="opacity-50">(Pass: 1qaz@WSX)</span>
      </button>
    </div>
  )
}

export default DevAuthTools
