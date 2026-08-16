import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { codeLoginApi, loginApi, logoutApi, registerApi } from '../api/auth'
import { clearAuthStorage } from '../utils/authSession'

function readUser() {
  try { return JSON.parse(localStorage.getItem('user') || 'null') } catch { return null }
}

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const refreshToken = ref(localStorage.getItem('refreshToken') || '')
  const user = ref(readUser())
  const isLoggedIn = computed(() => Boolean(token.value))
  const isAdmin = computed(() => user.value?.role === 1)

  function setSession(data) {
    token.value = data.token
    refreshToken.value = data.refreshToken
    user.value = data.user
    localStorage.setItem('token', data.token)
    localStorage.setItem('refreshToken', data.refreshToken)
    localStorage.setItem('user', JSON.stringify(data.user))
    localStorage.setItem('authLastActivity', String(Date.now()))
  }

  function syncFromStorage() {
    token.value = localStorage.getItem('token') || ''
    refreshToken.value = localStorage.getItem('refreshToken') || ''
    user.value = readUser()
  }

  async function login(payload, mode = 'password') {
    const result = mode === 'code' ? await codeLoginApi(payload) : await loginApi(payload)
    setSession(result.data)
  }

  async function register(payload) {
    const result = await registerApi(payload)
    setSession(result.data)
  }

  async function logout() {
    const currentRefreshToken = refreshToken.value
    try { if (currentRefreshToken) await logoutApi(currentRefreshToken) } catch { /* local logout still succeeds */ }
    clearAuthStorage('logout')
    syncFromStorage()
  }

  window.addEventListener('auth-token-refreshed', syncFromStorage)
  window.addEventListener('auth-session-cleared', syncFromStorage)

  return { token, refreshToken, user, isLoggedIn, isAdmin, setSession, login, register, logout, syncFromStorage }
})
