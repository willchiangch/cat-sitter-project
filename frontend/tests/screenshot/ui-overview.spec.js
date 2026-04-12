import { test } from '@playwright/test'
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const OUTPUT_DIR = path.join(__dirname, '..', '..', 'screenshot-report', 'screenshots')

// Smoke data IDs (from SmokeDataSeeder.java)
const SMOKE_IDS = {
  orderId: 'efefefef-0000-0000-0000-000000000030',
  visitId: 'efefefef-0000-0000-0000-000000000040',
  petId: 'efefefef-0000-0000-0000-000000000021',       // Fluffy
  serviceId: '68511200-0045-6120-0000-000000000001',
  sitterSlug: 'sophia-smoke',
}

// localStorage state for each role (used by Zustand persist for first render)
const SITTER_LOCAL_STATE = {
  id: 'efefefef-0000-0000-0000-000000000001',
  email: 'sitter_smoke@test.com',
  role: 'SITTER',
  lastActiveRole: 'SITTER',
  profiles: [{ id: 'efefefef-0000-0000-0000-000000000011', role: 'SITTER', name: 'Sophia (Smoke Test)' }],
}

const CLIENT_LOCAL_STATE = {
  id: 'efefefef-0000-0000-0000-000000000002',
  email: 'client_smoke@test.com',
  role: 'CLIENT',
  lastActiveRole: 'CLIENT',
  profiles: [{ id: 'efefefef-0000-0000-0000-000000000031', role: 'CLIENT', name: 'James Wilson (Smoke)' }],
}

const SITTER_ROUTES = [
  { path: '/sitter', label: 'Sitter Dashboard 主頁' },
  { path: '/sitter/orders', label: 'Sitter Orders 訂單列表' },
  { path: `/sitter/orders/${SMOKE_IDS.orderId}`, label: 'Sitter Order Detail 訂單詳情' },
  { path: `/sitter/service/${SMOKE_IDS.serviceId}`, label: 'Service Panel 服務管理' },
  { path: '/sitter/finance', label: 'Finance 財務' },
  { path: '/sitter/trust-circle', label: 'Trust Circle' },
  { path: '/sitter/client-gate', label: 'Client Gate' },
  { path: '/sitter/subscription', label: 'Subscription 訂閱方案' },
  { path: '/sitter/service-packages', label: 'Service Packages 服務包' },
  { path: '/sitter/questionnaire', label: 'Questionnaire Editor 問卷編輯' },
  { path: '/notifications', label: 'Notifications 通知 (Sitter)' },
  { path: '/profile', label: 'Profile 個人資料 (Sitter)' },
]

const CLIENT_ROUTES = [
  { path: '/client', label: 'Client Dashboard 主頁' },
  { path: '/client/pets', label: 'Pets 我的貓咪' },
  { path: `/client/cat-passport/${SMOKE_IDS.petId}`, label: 'Cat Passport 貓咪護照' },
  { path: '/client/sitters', label: 'Find Sitters 找保母' },
  { path: '/client/orders', label: 'Client Orders 訂單' },
  { path: `/client/service-log/${SMOKE_IDS.visitId}`, label: 'Service Log Detail 服務日誌' },
  { path: '/notifications', label: 'Notifications 通知 (Client)' },
  { path: '/profile', label: 'Profile 個人資料 (Client)' },
]

const PUBLIC_ROUTES = [
  { path: '/login', label: 'Login 登入' },
  { path: '/register', label: 'Register 註冊' },
  { path: '/onboarding', label: 'Onboarding 入門' },
  { path: `/s/${SMOKE_IDS.sitterSlug}`, label: 'Sitter Public Page 保母公開頁' },
]

// Inject X-Smoke-Auth header (same approach as AuthPage.js)
async function setupAuth(page, smokeRole, localUser) {
  // 1. Inject X-Smoke-Auth for all localhost API calls
  await page.route('**/*', (route) => {
    const url = route.request().url()
    const headers = { ...route.request().headers() }
    if (url.includes('localhost') || url.startsWith('/')) {
      headers['X-Smoke-Auth'] = smokeRole
    }
    route.continue({ headers })
  })

  // 2. Pre-fill localStorage so Zustand has auth state on first render
  await page.context().addInitScript((u) => {
    window.localStorage.setItem(
      'whiskerwatch-auth-storage',
      JSON.stringify({ state: { token: 'smoke-test-token', isAuthenticated: true, user: u }, version: 0 })
    )
    window.localStorage.setItem(
      'whiskerwatch-theme-storage',
      JSON.stringify({ state: { mode: u.lastActiveRole }, version: 0 })
    )
    window.localStorage.setItem('token', 'smoke-test-token')
  }, localUser)
}

async function takeScreenshot(page, dir, filename) {
  const outDir = path.join(OUTPUT_DIR, dir)
  fs.mkdirSync(outDir, { recursive: true })
  await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() =>
    page.waitForLoadState('domcontentloaded')
  )
  await page.waitForTimeout(600) // let animations settle
  await page.screenshot({ path: path.join(outDir, `${filename}.png`), fullPage: true })
}

function routeToFilename(routePath) {
  return routePath.replace(/\//g, '_').replace(/^_/, '') || 'home'
}

// ── Public pages (no auth needed) ───────────────────────────────────────
for (const route of PUBLIC_ROUTES) {
  test(`public: ${route.label}`, async ({ page }) => {
    await page.goto(route.path)
    await takeScreenshot(page, 'public', routeToFilename(route.path))
  })
}

// ── Sitter pages ─────────────────────────────────────────────────────────
for (const route of SITTER_ROUTES) {
  test(`sitter: ${route.label}`, async ({ page }) => {
    await setupAuth(page, 'SITTER', SITTER_LOCAL_STATE)
    await page.goto(route.path)
    await takeScreenshot(page, 'sitter', routeToFilename(route.path))
  })
}

// ── Client pages ─────────────────────────────────────────────────────────
for (const route of CLIENT_ROUTES) {
  test(`client: ${route.label}`, async ({ page }) => {
    await setupAuth(page, 'JAMES', CLIENT_LOCAL_STATE)
    await page.goto(route.path)
    await takeScreenshot(page, 'client', routeToFilename(route.path))
  })
}
