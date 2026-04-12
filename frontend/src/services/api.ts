/**
 * WhiskerWatch API Client — 膠合層 (Glue Layer)
 *
 * 職責分工：
 * 1. 自動生成的 SDK (services/gen/) → 標準 RESTful API 呼叫
 * 2. 本檔案 → Axios 攔截器、GCS 直傳、OAuth 導向等自訂邏輯
 *
 * 使用方式：
 *   import { client } from '@/services/gen/client.gen'
 *   import { getMe, listPets } from '@/services/gen/sdk.gen'
 *   import { storageService } from '@/services/api'  // GCS 上傳
 */

import axios from 'axios'
import { client } from './gen/client.gen'
import { useAuthStore } from '../store/authStore'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api/v1'

// --- 膠合層 Axios Instance (與舊 api.js 完全一致) ---
const api = axios.create({
  baseURL: API_BASE_URL,
  headers: { 'Content-Type': 'application/json' }
})

// Request Interceptor: Add Auth Token
api.interceptors.request.use(config => {
  const token = useAuthStore.getState().token
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
}, error => Promise.reject(error))

api.interceptors.response.use(
  response => {
    if (response.data && response.data.debugCode) {
      console.log('%c[DEBUG] 驗證碼: ' + response.data.debugCode, 'background: #000; color: #0f0; font-size: 16px; font-family: monospace; padding: 4px; border-radius: 4px; border: 1px solid #0f0;');
    }
    return response
  },
  error => {
    if (error.response?.status === 401) {
      useAuthStore.getState().logout()
      if (!window.location.pathname.startsWith('/login')) {
        window.location.href = '/login'
      }
    }
    return Promise.reject(error)
  }
)

// --- 設定自動生成的 SDK client (獨立設定) ---
client.setConfig({
  baseURL: import.meta.env.VITE_API_BASE_URL
    ? import.meta.env.VITE_API_BASE_URL.replace('/api/v1', '')
    : '',
})
client.instance.interceptors.request.use(config => {
  const token = useAuthStore.getState().token
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
}, error => Promise.reject(error))

client.instance.interceptors.response.use(
  response => {
    if (response.data && response.data.debugCode) {
      console.log('%c[DEBUG] 驗證碼: ' + response.data.debugCode, 'background: #000; color: #0f0; font-size: 16px; font-family: monospace; padding: 4px; border-radius: 4px; border: 1px solid #0f0;');
    }
    return response
  },
  error => {
    if (error.response?.status === 401) {
      useAuthStore.getState().logout()
      if (!window.location.pathname.startsWith('/login')) {
        window.location.href = '/login'
      }
    }
    return Promise.reject(error)
  }
)

// --- 以下為無法自動生成的自訂服務 ---

// Storage Services (GCS 直傳需要繞過 Axios interceptor)
export const storageService = {
  async uploadFile(file, subFolder = 'pets') {
    const form = new FormData()
    form.append('file', file)
    const { data } = await api.post(`/uploads/${subFolder}`, form, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    return data.url
  }
}

// --- Pet Services ---
export const petService = {
  list: () => api.get('/clients/me/pets').then(res => res.data),
  get: (id) => api.get(`/clients/me/pets/${id}`).then(res => res.data),
  create: (data) => api.post('/clients/me/pets', data).then(res => res.data),
  update: (id, data) => api.put(`/clients/me/pets/${id}`, data).then(res => res.data),
  delete: (id) => api.delete(`/clients/me/pets/${id}`).then(res => res.data)
}

// --- Order & Finance Services ---
export const orderService = {
  createBooking: (data) => api.post('/orders/booking', data).then(res => res.data),
  list: () => api.get('/orders').then(res => res.data),
  getDetail: (id) => api.get(`/orders/${id}`).then(res => res.data),
  submitQuote: (id, data) => api.post(`/orders/${id}/quote`, data).then(res => res.data),
  confirmPayment: (id) => api.post(`/orders/${id}/confirm-payment`).then(res => res.data),
  complete: (id) => api.post(`/orders/${id}/complete`).then(res => res.data),
  getFinanceSummary: () => api.get('/payments/payuni/sitter-summary').then(res => res.data)
}

// --- Visit & Service Log Services ---
export const visitService = {
  listSitterVisits: (date) => api.get('/sitters/me/visits', { params: { date } }).then(res => res.data),
  listClientVisits: () => api.get('/clients/me/visits').then(res => res.data),
  getDetail: (id) => api.get(`/visits/${id}`).then(res => res.data),
  updateChecklist: (visitId, data) => api.patch(`/visits/${visitId}/checklist`, data).then(res => res.data),
  addMedia: (visitId, data) => api.post(`/visits/${visitId}/media`, data).then(res => res.data),
  complete: (visitId) => api.post(`/visits/${visitId}/complete`).then(res => res.data)
}

// --- Profile & Identity Services ---
export const profileService = {
  getSitterMe: () => api.get('/sitters/me/profile').then(res => res.data),
  updateSitterMe: (data) => api.put('/sitters/me/profile', data).then(res => res.data),
  getClientMe: () => api.get('/clients/me/profile').then(res => res.data),
  updateClientMe: (data) => api.put('/clients/me/profile', data).then(res => res.data),
}

// --- Whitelist & Trust Services (V31) ---
export const whitelistService = {
  list: () => api.get('/sitters/me/whitelist').then(res => res.data),
  add: (clientId) => api.post('/sitters/me/whitelist/clients', { clientId }).then(res => res.data),
  toggleSkip: (clientId, skip) => api.put(`/sitters/me/whitelist/clients/${clientId}`, null, { params: { skipQuestionnaire: skip } }).then(res => res.data),
  remove: (clientId) => api.delete(`/sitters/me/whitelist/clients/${clientId}`).then(res => res.data),
  search: (q) => api.get('/sitters/me/whitelist/search', { params: { q } }).then(res => res.data),
}

export const blacklistService = {
  list: () => api.get('/sitters/me/blacklist').then(res => res.data),
  add: (clientId) => api.post('/sitters/me/blacklist/clients', { clientId }).then(res => res.data),
  remove: (clientId) => api.delete(`/sitters/me/blacklist/clients/${clientId}`).then(res => res.data),
  search: (q) => api.get('/sitters/me/blacklist/search', { params: { q } }).then(res => res.data),
}

export const trustCircleService = {
  listSitters: () => api.get('/sitters/me/trust-circle').then(res => res.data),
  searchBySlug: (slug) => api.get(`/sitters/${slug}/booking-preview`).then(res => res.data),
  add: (sitterProfileId) => api.post('/sitters/me/trust-circle', { sitterProfileId }).then(res => res.data),
  remove: (id) => api.delete(`/sitters/me/trust-circle/${id}`).then(res => res.data),
}

export const sitterService = {
  list: () => api.get('/sitters/me/services').then(res => res.data),
  create: (data) => api.post('/sitters/me/services', data).then(res => res.data),
  update: (id, data) => api.put(`/sitters/me/services/${id}`, data).then(res => res.data),
  delete: (id) => api.delete(`/sitters/me/services/${id}`).then(res => res.data)
}

export const questionnaireService = {
  list: () => api.get('/sitters/me/questionnaires').then(res => res.data),
  create: (data) => api.post('/sitters/me/questionnaires', data).then(res => res.data),
  update: (id, data) => api.put(`/sitters/me/questionnaires/${id}`, data).then(res => res.data),
  delete: (id) => api.delete(`/sitters/me/questionnaires/${id}`).then(res => res.data),
  reorder: (questionIds) => api.patch('/sitters/me/questionnaires/reorder', { questionIds }).then(res => res.data)
}

export const subscriptionService = {
  getCurrent: () => api.get('/sitters/me/subscription').then(res => res.data),
  cancel: () => api.delete('/sitters/me/subscription').then(res => res.data),
  changePlan: (planId: string, promoCode?: string) =>
    api.put('/sitters/me/subscription', { planId, promoCode }).then(res => res.data),
  validatePromo: (planId: string, promoCode: string) =>
    api.post('/sitters/me/subscription/validate-promo', { planId, promoCode }).then(res => res.data),
}

export const calendarService = {
  getStatus: () => api.get('/sitters/me/calendar/status').then(res => res.data),
  getAuthUrl: () => api.get('/sitters/me/calendar/auth-url').then(res => res.data),
  resetToken: () => api.post('/sitters/me/calendar/reset-token').then(res => res.data),
  disconnect: () => api.delete('/sitters/me/calendar').then(res => res.data)
}

// --- Auth Services ---
export const authService = {
  getMe: () => api.get('/auth/me').then(res => res.data),
  register: (data) => api.post('/auth/register', data).then(res => res.data),
  login: (data) => api.post('/auth/login', data).then(res => res.data),
  updateEmail: (email) => api.put('/auth/me/email', { email }).then(res => res.data),
  switchRole: (roleType) => api.post('/auth/switch-role', { roleType }).then(res => res.data),
  requestVerification: () => api.post('/auth/request-verification').then(res => res.data),
  verifyEmail: (code) => api.post('/auth/verify-email', { code }).then(res => res.data),
  completeOnboarding: (data) => api.post('/auth/complete-onboarding', data).then(res => res.data),
  getOAuthUrl: (provider) => {
    const baseUrl = API_BASE_URL.replace('/api/v1', '')
    return `${baseUrl}/oauth2/authorization/${provider}`
  }
}

// Auth Helpers (backward compat alias)
export const authHelpers = {
  getOAuthUrl: authService.getOAuthUrl
}

// Default export for legacy usage
export default api

// Re-export generated SDK for convenience
export * from './gen/sdk.gen'
export type * from './gen/types.gen'
export { client } from './gen/client.gen'
