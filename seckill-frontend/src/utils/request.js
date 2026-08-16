import axios from 'axios'
import { refreshAccessToken } from './authSession'

const request = axios.create({ baseURL: '/api', timeout: 10000 })

request.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

function apiError(message, status, response) {
  const error = new Error(message || '请求失败')
  error.status = status
  error.response = response
  return error
}

function canRefresh(config) {
  return config && !config._retry && !config.skipAuthRefresh && !String(config.url || '').includes('/auth/')
}

async function retryWithFreshToken(config) {
  config._retry = true
  const token = await refreshAccessToken()
  config.headers = config.headers || {}
  config.headers.Authorization = `Bearer ${token}`
  return request(config)
}

request.interceptors.response.use(
  async response => {
    const result = response.data
    if (result?.code === 401 && canRefresh(response.config)) return retryWithFreshToken(response.config)
    if (result && result.code !== 200) throw apiError(result.message, result.code, response)
    return result
  },
  async error => {
    if (error.response?.status === 401 && canRefresh(error.config)) return retryWithFreshToken(error.config)
    throw apiError(error.response?.data?.message || error.message, error.response?.status, error.response)
  }
)

export default request
