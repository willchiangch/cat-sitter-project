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

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api/v1'

// --- 設定自動生成的 client baseURL ---
client.setConfig({
  baseURL: import.meta.env.VITE_API_BASE_URL
    ? import.meta.env.VITE_API_BASE_URL.replace('/api/v1', '')
    : '',
})

// --- Axios Interceptor: 注入 JWT Token ---
client.instance.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
}, error => Promise.reject(error))

// --- 以下為無法自動生成的自訂服務 ---

// Storage Services (GCS 直傳需要繞過 Axios interceptor)
export const storageService = {
  /**
   * 1. Get Signed URL from Backend
   * 2. Upload file directly to GCS using PUT
   */
  async uploadFile(file, subFolder = 'pets') {
    try {
      const { data } = await client.instance.post(`${API_BASE_URL.includes('/api/v1') ? '' : '/api/v1'}/storage/upload-url`, {
        fileName: file.name,
        subFolder: subFolder
      })

      const { uploadUrl } = data

      // Step 2: Directly PUT to GCS (raw axios, no interceptors)
      await axios.put(uploadUrl, file, {
        headers: {
          'Content-Type': file.type
        }
      })

      return uploadUrl.split('?')[0]
    } catch (error) {
      console.error('File upload failed:', error)
      throw error
    }
  }
}

// Auth Helpers (OAuth URL 不是 REST call)
export const authHelpers = {
  getOAuthUrl: (provider) => {
    const baseUrl = API_BASE_URL.replace('/api/v1', '')
    return `${baseUrl}/oauth2/authorization/${provider}`
  }
}

// Re-export generated SDK for convenience
export * from './gen/sdk.gen'
export type * from './gen/types.gen'
export { client } from './gen/client.gen'
