import axios from 'axios'

const IDLE_TIMEOUT_MS = 30 * 60 * 1000
const REFRESH_BEFORE_EXPIRY_MS = 5 * 60 * 1000
const ACTIVITY_KEY = 'authLastActivity'
let refreshPromise = null
let monitorTimer = null
let lastRecordedAt = 0

export function clearAuthStorage(reason = '') {
  localStorage.removeItem('token')
  localStorage.removeItem('refreshToken')
  localStorage.removeItem('user')
  localStorage.removeItem(ACTIVITY_KEY)
  window.dispatchEvent(new CustomEvent('auth-session-cleared', { detail: { reason } }))
}

export function notifySessionExpired(reason = 'expired') {
  clearAuthStorage(reason)
  window.dispatchEvent(new CustomEvent('auth-session-expired', { detail: { reason } }))
}

export function refreshAccessToken() {
  if (refreshPromise) return refreshPromise
  const refreshToken = localStorage.getItem('refreshToken')
  if (!refreshToken) return Promise.reject(new Error('没有可用的刷新令牌'))

  refreshPromise = axios.post('/api/auth/refresh', { refreshToken }, { timeout: 10000 })
    .then(({ data }) => {
      if (data?.code !== 200 || !data.data?.token) throw new Error(data?.message || '登录状态刷新失败')
      localStorage.setItem('token', data.data.token)
      localStorage.setItem('refreshToken', data.data.refreshToken)
      if (data.data.user) localStorage.setItem('user', JSON.stringify(data.data.user))
      window.dispatchEvent(new CustomEvent('auth-token-refreshed', { detail: data.data }))
      return data.data.token
    })
    .catch(error => {
      notifySessionExpired('expired')
      throw error
    })
    .finally(() => { refreshPromise = null })
  return refreshPromise
}

function tokenExpiresSoon() {
  const token = localStorage.getItem('token')
  if (!token) return false
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
    return !payload.exp || payload.exp * 1000 - Date.now() <= REFRESH_BEFORE_EXPIRY_MS
  } catch {
    return true
  }
}

function checkSession() {
  if (!localStorage.getItem('token')) return
  const lastActivity = Number(localStorage.getItem(ACTIVITY_KEY) || Date.now())
  if (Date.now() - lastActivity >= IDLE_TIMEOUT_MS) {
    notifySessionExpired('idle')
    return
  }
  if (tokenExpiresSoon()) refreshAccessToken().catch(() => {})
}

function recordActivity() {
  if (!localStorage.getItem('token')) return
  const now = Date.now()
  if (now - lastRecordedAt < 5000) return
  lastRecordedAt = now
  localStorage.setItem(ACTIVITY_KEY, String(now))
}

export function startAuthSessionMonitor() {
  if (monitorTimer) return
  if (localStorage.getItem('token') && !localStorage.getItem(ACTIVITY_KEY)) recordActivity()
  ;['click', 'keydown', 'scroll', 'touchstart'].forEach(event =>
    window.addEventListener(event, recordActivity, { passive: true }))
  monitorTimer = window.setInterval(checkSession, 60_000)
  checkSession()
}
