import axios from 'axios'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api/v1'

// --- Axios Instance Setup ---
const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json'
  }
})

// Request Interceptor: Add Auth Token
api.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
}, error => Promise.reject(error))

// --- Storage Services ---
export const storageService = {
  /**
   * 1. Get Signed URL from Backend
   * 2. Upload file directly to GCS using PUT
   */
  async uploadFile(file, subFolder = 'pets') {
    try {
      // Step 1: Get Signed URL
      const { data } = await api.post('/storage/upload-url', {
        fileName: file.name,
        subFolder: subFolder
      })
      
      const { uploadUrl } = data

      // Step 2: Directly PUT to GCS (Do not use 'api' instance to avoid interceptors)
      await axios.put(uploadUrl, file, {
        headers: {
          'Content-Type': file.type
        }
      })

      // The backend/GCS uses subFolder/uuid_filename structure. 
      // For now, we return the uploadUrl's base path or a deterministic path if possible.
      // Optimization: In a real app, the backend might return the public URL directly in Step 1.
      return uploadUrl.split('?')[0] 
    } catch (error) {
      console.error('File upload failed:', error)
      throw error
    }
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
  getDetail: (id) => api.get(`/visits/${id}`).then(res => res.data),
  updateChecklist: (visitId, data) => api.patch(`/visits/${visitId}/checklist`, data).then(res => res.data),
  addMedia: (visitId, data) => api.post(`/visits/${visitId}/media`, data).then(res => res.data),
  complete: (visitId) => api.post(`/visits/${visitId}/complete`).then(res => res.data)
}

// --- Profile & Identity Services ---
export const profileService = {
  getSitterMe: () => api.get('/sitters/me/profile').then(res => res.data),
  updateSitterMe: (data) => api.put('/sitters/me/profile', data).then(res => res.data)
}

// --- Whitelist & Trust Services (V31) ---
export const whitelistService = {
  list: () => api.get('/sitters/me/whitelist').then(res => res.data),
  toggleSkip: (clientId, skip) => api.put(`/sitters/me/whitelist/clients/${clientId}`, null, { params: { skipQuestionnaire: skip } }).then(res => res.data),
  remove: (clientId) => api.delete(`/sitters/me/whitelist/clients/${clientId}`).then(res => res.data)
}

export const trustCircleService = {
  listSitters: () => api.get('/sitters/me/trust-circle').then(res => res.data),
  // Add/Remove sitter trust logic can be added here
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
  reorder: (questionIds) => api.patch('/sitters/me/questionnaires/reorder', { questionIds }).then(res => res.data)
}

// --- Auth Services ---
export const authService = {
  getMe: () => api.get('/auth/me').then(res => res.data),
  switchRole: (roleType) => api.post('/auth/switch-role', { roleType }).then(res => res.data),
  requestVerification: () => api.post('/auth/request-verification').then(res => res.data),
  verifyEmail: (code) => api.post('/auth/verify-email', { code }).then(res => res.data),
  
  // Helpers for Social Login redirects
  getOAuthUrl: (provider) => `${API_BASE_URL.replace('/api/v1', '')}/oauth2/authorization/${provider}`
}

export default api
